package chat.phantomyard.auto

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import chat.phantomyard.auto.service.PhantomAutoService

/**
 * Hosts the PhantomAutoService's single WebView while the app is in the foreground, so
 * opening this app is indistinguishable from using the PhantomChat PWA directly - it's the
 * same running JS session, not a reload. When the activity stops, the WebView is detached
 * (not destroyed) and PhantomAutoService keeps it running headlessly in the background.
 */
class MainActivity : AppCompatActivity() {

    private var service: PhantomAutoService? = null
    private lateinit var container: FrameLayout
    private var bound = false

    // CAMERA (QR-scan onboarding) and POST_NOTIFICATIONS (Android 13+) both need an
    // Activity to show their system prompt - PhantomAutoService's WebView can only check
    // whether they're already granted, not request them. Requesting both up front here
    // means they're already resolved by the time the web content actually needs them.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* WebChromeClient.onPermissionRequest re-checks grant state on next use */ }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as PhantomAutoService.LocalBinder).getService()
            service = bound
            attachWebView(bound)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        setContentView(container)
        // Apps targeting API 35+ get edge-to-edge enforced with no opt-out - content draws
        // behind the status/nav bars by default. PhantomChat's own top nav (rendered inside
        // the WebView) would end up under the status bar, where taps land on the system bar
        // instead of the page. Pad the container by the system bar insets to keep the WebView
        // clear of them, same as pre-edge-to-edge layout looked.
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        requestNeededPermissions()
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, PhantomAutoService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    override fun onStop() {
        super.onStop()
        service?.detachWebViewFromParent()
        container.removeAllViews()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        service = null
    }

    private fun attachWebView(service: PhantomAutoService) {
        val webView = service.getWebView()
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.addView(
            webView,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
