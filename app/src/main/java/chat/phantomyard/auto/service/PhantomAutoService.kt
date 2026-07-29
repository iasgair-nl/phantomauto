package chat.phantomyard.auto.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import chat.phantomyard.auto.BuildConfig
import chat.phantomyard.auto.MainActivity
import chat.phantomyard.auto.R
import org.json.JSONObject

private const val TAG = "PhantomAutoService"

private const val PHANTOM_CHAT_URL = "https://chat.phantomyard.ai"
private const val CONNECTION_CHANNEL_ID = "phantomauto_connection"
private const val CONNECTION_NOTIFICATION_ID = 1
private const val MESSAGE_CHANNEL_ID = "phantomauto_messages"

/**
 * Owns the single, long-lived WebView running the real PhantomChat PWA. MainActivity
 * reparents this WebView into its own layout while visible; it keeps running headlessly
 * the rest of the time, so there is ever only one live PhantomChat session, whether the
 * phone screen is open or not.
 */
class PhantomAutoService : Service() {

    private val binder = LocalBinder()
    private var webView: WebView? = null

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
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky: if the system kills this service under memory pressure, restart it and
        // reload PhantomChat fresh - its own bootstrap re-establishes identity/relays.
        return START_STICKY
    }

    /** The single, always-running WebView, created lazily on first access. */
    fun getWebView(): WebView {
        return webView ?: createWebView().also { webView = it }
    }

    /** Detach the WebView from whatever ViewGroup currently holds it, if any. */
    fun detachWebViewFromParent() {
        val view = webView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun createWebView(): WebView {
        val view = WebView(applicationContext)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        view.addJavascriptInterface(
            AndroidBridgeInterface { rawJson -> handleBridgePayload(rawJson) },
            "AndroidBridge"
        )
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(loadBridgeScript(), null)
            }
        }
        view.webChromeClient = object : WebChromeClient() {
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
        }
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
        val senderName = conversationPeerNames[conversationId] ?: "PhantomChat"
        val style = messagingStyleFor(conversationId, senderName)
        style.addMessage(text, System.currentTimeMillis(), null as Person?)
        notify(conversationId, style)

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

        NotificationManagerCompat.from(this).notify(notificationId, notification)
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
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PhantomAutoService? = null
            private set
    }
}
