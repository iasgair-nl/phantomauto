package chat.phantomyard.auto.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.car.app.connection.CarConnection
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Observer
import chat.phantomyard.auto.BuildConfig
import chat.phantomyard.auto.MainActivity
import chat.phantomyard.auto.R
import org.json.JSONObject

private const val TAG = "PhantomAutoService"

private const val PHANTOM_CHAT_URL = "https://chat.phantomyard.ai"
private const val CONNECTION_CHANNEL_ID = "phantomauto_connection"
private const val CONNECTION_NOTIFICATION_ID = 1
private const val MESSAGE_CHANNEL_ID = "phantomauto_messages"

// Chromium throttles a backgrounded WebView's own JS timers (the "5-minute rule"),
// independent of the process/Doze-level exemptions a remoteMessaging foreground
// service already gets - confirmed by real-world testing: messages stop arriving
// a few minutes after screen-off even with the service alive. A tick driven from
// the Kotlin side (unaffected by that JS-timer throttling) dispatches the same
// 'online' event PhantomChat's own relay pool already listens to for resuming
// (nostr-relay-pool.ts), just at a sane interval instead of the previous
// unconditional 30s spam - fast while actively driving (when timely delivery
// matters most), backed off aggressively otherwise (when it doesn't).
private const val ENGAGED_TICK_INTERVAL_MS = 60_000L // 1 minute, while connected to Android Auto
private const val IDLE_TICK_INTERVAL_MS = 30 * 60_000L // 30 minutes, otherwise

// Grace period between a raw gift-wrap event being detected (via RelayWakeListener,
// before decryption) and falling back to a content-free alert - gives the WebView a
// chance to wake on its own and deliver the real, decrypted MessagingStyle
// notification first, so the fallback only fires when that doesn't happen in time.
private const val GENERIC_NOTIFICATION_FALLBACK_DELAY_MS = 5_000L
// NIP-17 gift-wraps the same logical message separately per relay (each copy gets its
// own random wrapper/id for privacy), so one message can trigger several detections
// spaced well beyond GENERIC_NOTIFICATION_FALLBACK_DELAY_MS apart - confirmed live via
// DHU, where a single test message read the fallback alert out three times. Suppress
// re-arming for a while after one fires so relay-fanout latency doesn't read out as
// multiple separate alerts.
private const val GENERIC_ALERT_SUPPRESS_WINDOW_MS = 90_000L
// Placeholder "conversation" for the content-free fallback alert - deliberately routed
// through the same notify()/MessagingStyle/shortcut path real conversations use, since
// Android Auto silently drops anything that isn't shaped that way (see notify()).
// There's no real peer behind it, so ReplyReceiver/sendReplyFromNotification must not
// try to actually deliver a reply sent to this id.
private const val GENERIC_ALERT_CONVERSATION_ID = "phantomauto-generic-alert"

/**
 * Owns the single, long-lived WebView running the real PhantomChat PWA. MainActivity
 * reparents this WebView into its own layout while visible; it keeps running headlessly
 * the rest of the time, so there is ever only one live PhantomChat session, whether the
 * phone screen is open or not.
 */
class PhantomAutoService : Service() {

    private val binder = LocalBinder()
    private var webView: WebView? = null
    private var ghostDisplay: VirtualDisplay? = null
    private var ghostSurfaceTexture: SurfaceTexture? = null
    private var ghostWindowManager: WindowManager? = null

    private val tickHandler = Handler(Looper.getMainLooper())
    @Volatile private var engagedViaAndroidAuto = false
    private var lastKnownOwnId: String? = null
    private var lastKnownRelays: List<String>? = null

    /**
     * Whether the MainActivity is currently in the foreground (hosting the WebView).
     * Notifications are generally gated when the user is actively looking at the app on the phone,
     * unless we are engaged via Android Auto (where notifications on the car screen are still
     * desired even if the phone app is open).
     */
    var isActivityVisible: Boolean = false
        set(value) {
            if (field != value) {
                Log.d(TAG, "activity visible=$value")
                field = value
                updateState()
                if (value && !engagedViaAndroidAuto) {
                    clearGenericNotifications()
                }
            }
        }

    private fun clearGenericNotifications() {
        val manager = NotificationManagerCompat.from(this)
        pendingGenericNotification?.let { tickHandler.removeCallbacks(it) }
        pendingGenericNotification = null
        messagingStyles.remove(GENERIC_ALERT_CONVERSATION_ID)
        manager.cancel(GENERIC_ALERT_CONVERSATION_ID.hashCode())
    }

    private lateinit var carConnection: CarConnection
    private val carConnectionObserver = Observer<Int> { type ->
        val nowEngaged = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
            type == CarConnection.CONNECTION_TYPE_NATIVE
        Log.d(TAG, "car connection type=$type engaged=$nowEngaged")
        if (nowEngaged != engagedViaAndroidAuto) {
            engagedViaAndroidAuto = nowEngaged
            updateState()
        }
    }
    private val tickRunnable = object : Runnable {
        override fun run() {
            val interval = if (engagedViaAndroidAuto) ENGAGED_TICK_INTERVAL_MS else IDLE_TICK_INTERVAL_MS
            Log.d(TAG, "resume tick (engaged=$engagedViaAndroidAuto, next in ${interval}ms)")
            wakeWebView()
            tickHandler.postDelayed(this, interval)
        }
    }

    private fun wakeWebView() {
        val view = webView ?: return
        
        // Chromium background throttling is notoriously hard to bypass.
        // resumeTimers() is essential for background execution.
        view.onResume()
        view.resumeTimers()
        view.setNetworkAvailable(true)

        if (engagedViaAndroidAuto || isActivityVisible) {
            // Full visibility cycle for active driving or foreground app
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            
            view.visibility = View.VISIBLE
            view.dispatchWindowVisibilityChanged(View.VISIBLE)
            view.dispatchWindowFocusChanged(true)

            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, width, height)
            view.requestLayout()
        }
        
        // Dispatching 'online' is enough to nudge the Nostr pool in background
        view.evaluateJavascript("window.dispatchEvent(new Event('online')); void 0;", null)
    }

    private fun updateState() {
        Log.d(TAG, "updating state: engaged=$engagedViaAndroidAuto, visible=$isActivityVisible")
        
        // 1. Tick Cadence: Reschedule immediately to apply new interval
        tickHandler.removeCallbacks(tickRunnable)
        tickHandler.post(tickRunnable)
        
        // 2. RelayWakeListener: Only run if engaged with car
        if (engagedViaAndroidAuto) {
            val ownId = lastKnownOwnId
            val relays = lastKnownRelays
            if (ownId != null && relays != null) {
                if (relayWakeListener == null) {
                    Log.d(TAG, "starting RelayWakeListener for car connection")
                    relayWakeListener = RelayWakeListener(ownId, relays) { eventId -> onGiftWrapWake(eventId) }
                        .also { it.start() }
                }
            }
        } else {
            if (relayWakeListener != null) {
                Log.d(TAG, "stopping RelayWakeListener: not engaged")
                relayWakeListener?.stop()
                relayWakeListener = null
            }
        }
        
        // 3. Ghost Window: Only needed if engaged but app not visible on phone
        if (engagedViaAndroidAuto && !isActivityVisible) {
            if (ghostWindowManager == null) {
                setupGhostWindow()
            }
            moveWebViewToBackground()
        } else if (!engagedViaAndroidAuto && !isActivityVisible) {
            // Not engaged and not visible: release ghost window resources
            releaseGhostWindow()
        }
    }

    private fun releaseGhostWindow() {
        Log.d(TAG, "releasing ghost window")
        webView?.let { removeFromParent(it) }
        ghostDisplay?.release()
        ghostDisplay = null
        ghostSurfaceTexture?.release()
        ghostSurfaceTexture = null
        ghostWindowManager = null
    }

    // Context (needed for getString) isn't attached yet when property initializers run,
    // so this must stay lazy rather than eagerly built.
    private val mePerson by lazy {
        Person.Builder()
            .setName(getString(R.string.me_label))
            .setKey("me")
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .build()
    }

    // One MessagingStyle per conversation, kept in memory so new messages append to the
    // existing thread instead of replacing it, and so an optimistic reply can be added
    // immediately when the user answers from the notification.
    private val messagingStyles = mutableMapOf<String, NotificationCompat.MessagingStyle>()
    private val conversationPeerNames = mutableMapOf<String, String>()
    private val conversationPeerKeys = mutableMapOf<String, String>()

    private var relayWakeListener: RelayWakeListener? = null
    private var pendingGenericNotification: Runnable? = null
    private var lastGenericNotificationFiredAtMs = 0L

    inner class LocalBinder : Binder() {
        fun getService(): PhantomAutoService = this@PhantomAutoService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        ServiceCompat.startForeground(
            this,
            CONNECTION_NOTIFICATION_ID,
            buildConnectionNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        )
        carConnection = CarConnection(applicationContext)
        carConnection.type.observeForever(carConnectionObserver)
        updateState()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky: if the system kills this service under memory pressure, restart it and
        // reload PhantomChat fresh - its own bootstrap re-establishes identity/relays.
        return START_STICKY
    }

    /** The single, always-running WebView, created lazily on first access. */
    fun getWebView(): WebView {
        val view = webView ?: createWebView().also { webView = it }
        // Ensure it's removed from any previous parent (Activity or Ghost Window)
        removeFromParent(view)
        return view
    }

    private fun removeFromParent(view: View) {
        val parent = view.parent ?: return
        if (parent is ViewGroup) {
            parent.removeView(view)
        } else {
            // If it's attached to a WindowManager (like our ghost window), 
            // view.parent will be a ViewRootImpl, not a ViewGroup.
            try {
                ghostWindowManager?.removeViewImmediate(view)
            } catch (e: Exception) {
                // Not attached to this WM or already detached
            }
        }
    }

    /** 
     * Moves the WebView to a headless ghost window so it stays attached to a Window context
     * and keeps its JS/network stack alive even when the phone app is closed.
     */
    fun moveWebViewToBackground() {
        val view = webView ?: return
        val wm = ghostWindowManager ?: return
        
        // Remove from current parent (e.g. MainActivity)
        removeFromParent(view)
        
        // Attach to our invisible ghost window
        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(view, params)
            Log.d(TAG, "WebView moved to ghost window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach WebView to ghost window", e)
        }
    }

    private fun setupGhostWindow() {
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            
            // Create a dummy surface for the virtual display
            val surfaceTexture = SurfaceTexture(10)
            ghostSurfaceTexture = surfaceTexture
            val surface = Surface(surfaceTexture)
            
            ghostDisplay = dm.createVirtualDisplay(
                "PhantomGhostDisplay", width, height, metrics.densityDpi,
                surface, 0
            )
            
            val displayContext = createDisplayContext(ghostDisplay!!.display)
            ghostWindowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            Log.d(TAG, "Ghost window system initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup ghost window", e)
        }
    }

    /** Force the WebView to reload the PWA URL. */
    fun reloadWebView() {
        webView?.loadUrl(PHANTOM_CHAT_URL)
    }

    private fun createWebView(): WebView {
        val view = WebView(applicationContext)
        view.setBackgroundColor(Color.BLACK)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.databaseEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        // Force internal network state to 'available' to discourage Chromium from
        // aggressively cutting off background connections.
        view.setNetworkAvailable(true)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        // Chromium freezes a backgrounded page's JS timer/task queue once it has no window
        // attached for a while - confirmed live via CDP: Date.now() and sync eval kept
        // working, but a plain setTimeout() never fired even after 75s. That queue is what
        // PhantomChat's own reconnect/backoff/catch-up logic runs on, so relay sockets stay
        // "connected" while nothing actually gets processed. resumeTimers() is WebView's own
        // documented override for exactly this ("pauses/resumes JS timers for ALL WebViews"),
        // so this isn't a hack - it's the API this situation exists for.
        view.resumeTimers()

        view.addJavascriptInterface(
            AndroidBridgeInterface { rawJson -> handleBridgePayload(rawJson) },
            "AndroidBridge"
        )
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "Page started loading: $url")
                // Inject polyfills as early as possible to prevent boot-time crashes.
                view?.evaluateJavascript(loadBridgeScript(), null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page finished loading: $url")
                // Re-inject in case it didn't take or page was replaced.
                view?.evaluateJavascript(loadBridgeScript(), null)
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                Log.e(TAG, "WebView Error: ${error?.description} (${error?.errorCode}) for URL: ${request?.url}")
            }

            override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                Log.e(TAG, "HTTP Error: ${errorResponse?.statusCode} for URL: ${request?.url}")
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                Log.e(TAG, "SSL Error: $error")
                handler?.cancel() // Safety first, but logging helps debug.
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                Log.e(TAG, "WebView render process gone! Crashed=${detail?.didCrash()}")
                // If the renderer is gone, we must destroy the old view and start fresh.
                // The service stays alive, but the PhantomChat session is lost.
                webView?.let {
                    (it.parent as? ViewGroup)?.removeView(it)
                    it.destroy()
                }
                webView = null
                // Attempt to recover by creating a new one on next access
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebViewConsole", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} " +
                    "(${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
                return true
            }

            // The web content's QR-scan onboarding calls getUserMedia() for the camera.
            // The OS runtime permission itself can only be requested from an Activity
            // (MainActivity does this on launch) - this just mirrors that grant into the
            // WebView's own permission model.
            override fun onPermissionRequest(request: PermissionRequest) {
                val granted = request.resources.filter { resource ->
                    resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                        ContextCompat.checkSelfPermission(
                            applicationContext, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                }
                if (granted.isNotEmpty()) {
                    request.grant(granted.toTypedArray())
                } else {
                    request.deny()
                }
            }
        }
        view.loadUrl(PHANTOM_CHAT_URL)
        return view
    }

    private fun loadBridgeScript(): String {
        return applicationContext.assets.open("bridge.js").bufferedReader().use { it.readText() }
    }

    private fun handleBridgePayload(rawJson: String) {
        val json = JSONObject(rawJson)
        val type = json.optString("type")
        val payload = json.optJSONObject("payload")
        Log.d(TAG, "bridge payload: type=$type payload=$payload")
        when (type) {
            "message" -> payload?.let { showIncomingMessageNotification(it) }
            "replyResult" -> payload?.let { logReplyResult(it) }
            "ready" -> payload?.let { 
                captureReadyState(it)
                updateState()
            }
        }
    }

    private fun captureReadyState(payload: JSONObject) {
        val ownId = payload.optString("ownId").takeIf { it.isNotEmpty() } ?: return
        val relaysJson = payload.optJSONArray("relays")
        val relays = mutableListOf<String>()
        if (relaysJson != null) {
            for (i in 0 until relaysJson.length()) {
                relaysJson.optString(i).takeIf { it.isNotEmpty() }?.let { relays.add(it) }
            }
        }
        if (relays.isEmpty()) {
            Log.w(TAG, "no relays reported by bridge")
            return
        }
        lastKnownOwnId = ownId
        lastKnownRelays = relays
    }

    /**
     * A gift-wrap event matching our pubkey landed on a relay - detected natively,
     * without decrypting anything. Nudge the WebView in case it's still responsive
     * enough to pick it up on its own, then arm a content-free fallback notification
     * in case it isn't (see GENERIC_NOTIFICATION_FALLBACK_DELAY_MS) - cancelled by
     * showIncomingMessageNotification if the real, decrypted message beats it there.
     */
    private fun onGiftWrapWake(eventId: String) {
        wakeWebView()

        // Only show notifications if engaged via Android Auto. We allow them even if the
        // app is active, because the user might have opened it specifically to keep the
        // JS engine alive for Android Auto.
        if (!engagedViaAndroidAuto) {
            return
        }

        // Arm the fallback only if nothing is already pending, so a burst of several
        // gift-wrap events (e.g. multi-relay fanout, or unrelated kind-1059 traffic
        // like reactions/typing) fires it a fixed delay after the FIRST one - not
        // perpetually deferred by each subsequent detection. Also skip re-arming
        // within GENERIC_ALERT_SUPPRESS_WINDOW_MS of the last one firing, since that's
        // almost always the same message's slower relay copies, not a new message.
        val sinceLastFired = System.currentTimeMillis() - lastGenericNotificationFiredAtMs
        if (pendingGenericNotification == null && sinceLastFired > GENERIC_ALERT_SUPPRESS_WINDOW_MS) {
            val runnable = Runnable {
                pendingGenericNotification = null
                lastGenericNotificationFiredAtMs = System.currentTimeMillis()
                showGenericIncomingNotification()
            }
            pendingGenericNotification = runnable
            tickHandler.postDelayed(runnable, GENERIC_NOTIFICATION_FALLBACK_DELAY_MS)
        }
    }

    /**
     * Content-free alert shown when a gift-wrap event was detected but the WebView
     * didn't wake up in time to decrypt/render it - mirrors PhantomChat's own
     * preview-level 'A' ("show generic notification, never read privkey"). Routed
     * through the same notify()/MessagingStyle/shortcut path as a real conversation
     * (see GENERIC_ALERT_CONVERSATION_ID) so Android Auto actually surfaces it.
     */
    private fun showGenericIncomingNotification() {
        Log.d(TAG, "showing generic fallback notification (real message never arrived in time)")
        val senderName = getString(R.string.generic_message_title)
        conversationPeerNames[GENERIC_ALERT_CONVERSATION_ID] = senderName
        conversationPeerKeys[GENERIC_ALERT_CONVERSATION_ID] = GENERIC_ALERT_CONVERSATION_ID
        val style = messagingStyleFor(GENERIC_ALERT_CONVERSATION_ID, senderName)
        style.addMessage(
            getString(R.string.generic_message_body),
            System.currentTimeMillis(),
            buildPerson(senderName, GENERIC_ALERT_CONVERSATION_ID)
        )
        notify(GENERIC_ALERT_CONVERSATION_ID, style)
    }

    private fun logReplyResult(payload: JSONObject) {
        if (!payload.optBoolean("ok", true)) {
            Log.w(TAG, "reply failed for conversation=${payload.optString("conversationId")}: " +
                payload.optString("error"))
        }
    }

    private fun showIncomingMessageNotification(payload: JSONObject) {
        val conversationId = payload.optString("conversationId").takeIf { it.isNotEmpty() } ?: return
        val text = payload.optString("text")
        if (text.isEmpty()) return
        // Real, decrypted content made it through - no need for the generic fallback
        // (and if it already fired, clear it so it doesn't sit alongside the real one).
        pendingGenericNotification?.let { tickHandler.removeCallbacks(it) }
        pendingGenericNotification = null
        messagingStyles.remove(GENERIC_ALERT_CONVERSATION_ID)
        NotificationManagerCompat.from(this).cancel(GENERIC_ALERT_CONVERSATION_ID.hashCode())

        // Only show notifications if engaged via Android Auto.
        if (!engagedViaAndroidAuto) {
            Log.d(TAG, "suppressing notification: not engaged via Android Auto")
            return
        }

        val senderName = payload.optString("senderName", "PhantomChat")
        val peerPubkey = payload.optString("peerPubkey", conversationId)
        val timestamp = payload.optLong("timestamp", System.currentTimeMillis())
        conversationPeerNames[conversationId] = senderName
        conversationPeerKeys[conversationId] = peerPubkey

        val senderPerson = buildPerson(senderName, peerPubkey)
        val style = messagingStyleFor(conversationId, senderName)
        style.addMessage(text, timestamp, senderPerson)

        notify(conversationId, style)
    }

    private fun buildPerson(name: String, key: String): Person {
        return Person.Builder()
            .setName(name)
            .setKey(key)
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .build()
    }

    /**
     * Invoked by ReplyReceiver when the user replies from the notification (including
     * Android Auto's voice reply). Optimistically appends the reply to the visible
     * notification, then asks the running PhantomChat instance to actually send it.
     */
    fun sendReplyFromNotification(conversationId: String, text: String) {
        if (conversationId == GENERIC_ALERT_CONVERSATION_ID) {
            // No real peer behind this placeholder - nothing to deliver to.
            Log.w(TAG, "ignoring reply to generic fallback alert (no real conversation)")
            return
        }
        val senderName = conversationPeerNames[conversationId] ?: "PhantomChat"
        val style = messagingStyleFor(conversationId, senderName)
        style.addMessage(text, System.currentTimeMillis(), null as Person?)
        notify(conversationId, style)

        // Chromium often freezes the JS engine/network stack when the WebView is detached.
        // Force a wake-up before sending the reply to ensure it hits the wire immediately.
        wakeWebView()

        val script = buildSendReplyScript(conversationId, text)
        webView?.evaluateJavascript(script, null)
    }

    private fun buildSendReplyScript(conversationId: String, text: String): String {
        val conversationIdJs = JSONObject.quote(conversationId)
        val textJs = JSONObject.quote(text)
        return """
            (function() {
              window.sendReply($conversationIdJs, $textJs).then(function(id) {
                window.AndroidBridge && window.AndroidBridge.postMessage(JSON.stringify({
                  type: 'replyResult',
                  payload: {conversationId: $conversationIdJs, ok: true, id: id}
                }));
              }).catch(function(err) {
                window.AndroidBridge && window.AndroidBridge.postMessage(JSON.stringify({
                  type: 'replyResult',
                  payload: {conversationId: $conversationIdJs, ok: false, error: String(err)}
                }));
              });
            })();
        """.trimIndent()
    }

    private fun messagingStyleFor(conversationId: String, senderName: String): NotificationCompat.MessagingStyle {
        return messagingStyles.getOrPut(conversationId) {
            NotificationCompat.MessagingStyle(mePerson).also {
                it.conversationTitle = senderName
                it.isGroupConversation = false
            }
        }
    }

    private fun notify(conversationId: String, style: NotificationCompat.MessagingStyle) {
        val notificationId = conversationId.hashCode()
        val senderName = conversationPeerNames[conversationId] ?: "PhantomChat"
        val peerKey = conversationPeerKeys[conversationId] ?: conversationId
        publishConversationShortcut(conversationId, senderName, peerKey)

        val replyIntent = ReplyReceiver.buildIntent(this, conversationId)
        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            notificationId,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remoteInput = RemoteInput.Builder(ReplyReceiver.KEY_REPLY_TEXT)
            .setLabel(getString(R.string.reply_label))
            .build()
        // Android Auto requires these two exact semantic actions on messaging notifications
        // (https://developer.android.com/training/cars/communication/notification-messaging) -
        // without them it silently drops the notification rather than surfacing it, even
        // though the notification still shows/mirrors fine everywhere else (e.g. Wear OS).
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            getString(R.string.reply_label),
            replyPendingIntent
        ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()

        val markReadIntent = MarkReadReceiver.buildIntent(this, conversationId)
        val markReadPendingIntent = PendingIntent.getBroadcast(
            this,
            notificationId,
            markReadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val markReadAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            getString(R.string.mark_read_label),
            markReadPendingIntent
        ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val contentIntent = PendingIntent.getActivity(
            this, notificationId, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .addAction(replyAction)
            .addInvisibleAction(markReadAction)
            .setAutoCancel(true)
            .setShortcutId(conversationId)
            .build()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
            == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify(notificationId, notification)
        }
    }

    /** Invoked by MarkReadReceiver - Android Auto (and other surfaces) may fire the
     * invisible mark-as-read action without any user-visible interaction. Resets the
     * conversation's notification thread so a future message starts a fresh, unread style. */
    fun markConversationRead(conversationId: String) {
        messagingStyles.remove(conversationId)
        NotificationManagerCompat.from(this).cancel(conversationId.hashCode())
    }

    /**
     * Android Auto (unlike Wear OS's generic notification mirroring) only surfaces
     * MessagingStyle notifications that are backed by a long-lived "Conversation" shortcut -
     * without this, the notification still exists and mirrors fine elsewhere, but Android
     * Auto silently ignores it. The shortcut ID must match the notification's setShortcutId.
     */
    private fun publishConversationShortcut(conversationId: String, senderName: String, peerKey: String) {
        val person = buildPerson(senderName, peerKey)
        val shortcut = ShortcutInfoCompat.Builder(this, conversationId)
            .setLongLived(true)
            .setShortLabel(senderName)
            .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .setPerson(person)
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_CHANNEL_ID,
                getString(R.string.connection_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                getString(R.string.message_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun buildConnectionNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.connection_notification_title))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        instance = null
        tickHandler.removeCallbacks(tickRunnable)
        carConnection.type.removeObserver(carConnectionObserver)
        relayWakeListener?.stop()
        relayWakeListener = null
        
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        
        ghostDisplay?.release()
        ghostDisplay = null
        ghostSurfaceTexture?.release()
        ghostSurfaceTexture = null
        ghostWindowManager = null
        
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PhantomAutoService? = null
            private set
    }
}
