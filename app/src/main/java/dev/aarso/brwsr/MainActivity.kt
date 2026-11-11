package dev.aarso.brwsr

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import dev.aarso.brwsr.ui.BrwsrScreen
import dev.aarso.brwsr.ui.BrwsrTheme
import dev.aarso.brwsr.ui.ScreenActions
import dev.aarso.brwsr.web.Browser
import dev.aarso.brwsr.web.BrowserHost
import dev.aarso.crashrecovery.CrashRecovery

class MainActivity : ComponentActivity(), BrowserHost {
    private var browser: Browser? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var videoView: View? = null
    private var videoExit: (() -> Unit)? = null

    private val files = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashRecovery.maybeShowRecovery(this, appLabel = "brwsr")) return
        enableEdgeToEdge()
        val app = application as BrwsrApp
        val b = Browser(this, app.settings, app.rules, app.topSites, app.filters, lifecycleScope)
        b.host = this
        browser = b
        b.restore(app.settings.restoreUrls.lines().filter { it.isNotBlank() })
        handleIntent(intent)

        setContent {
            BrwsrTheme {
                LaunchedEffect(app.settings.immersive) { applyImmersive(app.settings.immersive) }
                BrwsrScreen(
                    b,
                    ScreenActions(
                        share = ::share,
                        exit = { moveTaskToBack(true) },
                        clearBrowsingData = ::clearBrowsingData,
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val b = browser ?: return
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { b.newTab(it) }
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)?.let { q -> b.newTab(); b.submit(q) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { t ->
                b.newTab()
                b.submit(Regex("https?://\\S+").find(t)?.value ?: t)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val b = browser ?: return
        (application as BrwsrApp).settings.restoreUrls = b.openUrls().joinToString("\n")
        b.current?.webView?.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        browser?.current?.webView?.onResume()
    }

    override fun onDestroy() {
        browser?.destroy()
        super.onDestroy()
    }

    // ---- BrowserHost -------------------------------------------------------------------

    override fun chooseFiles(intent: Intent, callback: ValueCallback<Array<Uri>>): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try { files.launch(intent); true } catch (_: ActivityNotFoundException) { fileCallback = null; false }
    }

    override fun launchExternal(intent: Intent): Boolean =
        try { startActivity(intent); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    override fun enterVideoFullscreen(view: View, onExit: () -> Unit) {
        exitVideoFullscreen()
        videoView = view
        videoExit = onExit
        (window.decorView as ViewGroup).addView(
            view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        applyImmersive(true)
    }

    override fun exitVideoFullscreen() {
        val v = videoView ?: return
        (window.decorView as ViewGroup).removeView(v)
        videoView = null
        videoExit?.invoke()
        videoExit = null
        applyImmersive((application as BrwsrApp).settings.immersive)
    }

    @Deprecated("Back is handled in Compose; this only closes fullscreen video first.")
    override fun onBackPressed() {
        if (videoView != null) exitVideoFullscreen() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun applyImmersive(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun share(url: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
        launchExternal(Intent.createChooser(send, null))
    }

    private fun clearBrowsingData() {
        val b = browser ?: return
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        b.tabs.forEach { it.webView.clearCache(true); it.webView.clearHistory() }
        (application as BrwsrApp).topSites.clearAll()
    }
}
