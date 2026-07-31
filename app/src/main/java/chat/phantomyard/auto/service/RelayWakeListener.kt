package chat.phantomyard.auto.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Collections
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "RelayWakeListener"
private const val GIFT_WRAP_KIND = 1059
private const val RECONNECT_DELAY_MS = 15_000L
private const val MAX_SEEN_EVENT_IDS = 500
private const val SUBSCRIPTION_ID = "phantomauto-wake"
private const val PING_INTERVAL_MS = 45_000L

/**
 * Watches the account's own relays for incoming gift-wrapped DM events (kind 1059,
 * tagged #p to our pubkey) without decrypting anything - just detecting that one
 * arrived. Exists because Chromium freezes the WebView's JS timer/task queue once it
 * has had no window attached for a while (confirmed via live CDP testing: a plain
 * setTimeout() never fired even after 75s, and WebView.resumeTimers() doesn't undo
 * it), so PhantomChat's own relay pool can't process anything while headless even
 * though its sockets still report "connected". Mirrors the pool's own subscription
 * filter (nostr-relay.ts subscribeMessages: kinds incl. 1059, "#p": [ownPubkey]) and
 * its own current relay list (read live from window.__phantomchatPool.getRelays(),
 * never hardcoded) purely to trigger a wake - the real WebView still does all actual
 * decryption/persistence/rendering once nudged.
 */
class RelayWakeListener(
    private val ownPubkeyHex: String,
    private val relayUrls: List<String>,
    private val onGiftWrapDetected: (eventId: String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private val sockets = mutableMapOf<String, WebSocket>()
    private val seenEventIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private var lastSeenEventCreatedAt = System.currentTimeMillis() / 1000
    @Volatile private var stopped = false

    fun start() {
        stopped = false
        Log.d(TAG, "starting, watching ${relayUrls.size} relay(s) for pubkey=$ownPubkeyHex")
        relayUrls.forEach { connect(it) }
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        sockets.values.forEach { it.close(1000, "stopping") }
        sockets.clear()
    }

    private fun connect(url: String) {
        if (stopped) return
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            Log.w(TAG, "bad relay url $url: ${e.message}")
            return
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val filter = JSONObject().apply {
                    put("kinds", JSONArray(listOf(GIFT_WRAP_KIND)))
                    put("#p", JSONArray(listOf(ownPubkeyHex)))
                    put("since", lastSeenEventCreatedAt)
                }
                val req = JSONArray().apply {
                    put("REQ")
                    put(SUBSCRIPTION_ID)
                    put(filter)
                }
                webSocket.send(req.toString())
                Log.d(TAG, "connected + subscribed: $url (since=$lastSeenEventCreatedAt)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(url, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "relay socket failed: $url (${t.message})")
                sockets.remove(url)
                scheduleReconnect(url)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(url)
                scheduleReconnect(url)
            }
        }
        sockets[url] = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect(url: String) {
        if (stopped) return
        handler.postDelayed({ connect(url) }, RECONNECT_DELAY_MS)
    }

    private fun handleFrame(relayUrl: String, text: String) {
        try {
            val arr = JSONArray(text)
            // NIP-01: ["EVENT", <subscription_id>, <event_object>]
            if (arr.length() < 3 || arr.optString(0) != "EVENT") return
            if (arr.optString(1) != SUBSCRIPTION_ID) return

            val event = arr.getJSONObject(2)
            if (event.optInt("kind", -1) != GIFT_WRAP_KIND) return

            // Native defense-in-depth: some relays might ignore filters or be misconfigured.
            // Ensure the event is actually addressed to us.
            val tags = event.optJSONArray("tags")
            var addressedToMe = false
            if (tags != null) {
                for (i in 0 until tags.length()) {
                    val tag = tags.optJSONArray(i)
                    if (tag != null && tag.length() >= 2 && tag.optString(0) == "p" && tag.optString(1) == ownPubkeyHex) {
                        addressedToMe = true
                        break
                    }
                }
            }
            if (!addressedToMe) return

            val createdAt = event.optLong("created_at", 0)
            val nowSeconds = System.currentTimeMillis() / 1000
            if (createdAt in (lastSeenEventCreatedAt + 1)..nowSeconds) {
                lastSeenEventCreatedAt = createdAt
            }

            val eventId = event.optString("id")
            if (eventId.isEmpty()) return
            val isNew = synchronized(seenEventIds) {
                val added = seenEventIds.add(eventId)
                if (added && seenEventIds.size > MAX_SEEN_EVENT_IDS) {
                    val oldest = seenEventIds.iterator()
                    oldest.next()
                    oldest.remove()
                }
                added
            }
            if (!isNew) return
            Log.d(TAG, "gift-wrap event detected from $relayUrl: $eventId")
            handler.post { onGiftWrapDetected(eventId) }
        } catch (e: Exception) {
            // Not every relay frame is an EVENT array (EOSE/NOTICE/OK/etc) - ignore the rest.
        }
    }
}
