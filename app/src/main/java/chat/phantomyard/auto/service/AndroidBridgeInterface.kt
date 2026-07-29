package chat.phantomyard.auto.service

import android.webkit.JavascriptInterface

/**
 * Receives postMessage(json) calls from assets/bridge.js, running inside the
 * PhantomChat WebView. json is {"type": "message"|"ready", "payload": {...}}.
 */
class AndroidBridgeInterface(private val onPayload: (rawJson: String) -> Unit) {

    @JavascriptInterface
    fun postMessage(rawJson: String) {
        onPayload(rawJson)
    }
}
