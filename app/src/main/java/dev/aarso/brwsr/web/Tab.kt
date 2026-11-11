package dev.aarso.brwsr.web

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.webkit.ScriptHandler
import java.util.UUID

/** One page. Compose reads the state; the WebView is owned here and outlives recomposition. */
class Tab(val id: Long, webView: WebView) {
    var webView: WebView by mutableStateOf(webView)

    /** Bridge secret for this WebView (see [JsBridge]). */
    val token: String = UUID.randomUUID().toString().replace("-", "")

    var url by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(100)
    var favicon by mutableStateOf<ImageBitmap?>(null)
    var thumb by mutableStateOf<ImageBitmap?>(null)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    /** Showing the Top Sites wall instead of the page. */
    var landing by mutableStateOf(true)
    var desktop by mutableStateOf(false)
    var nakdNow by mutableStateOf(false)
    var blocked by mutableIntStateOf(0)

    /** Read from WebView IO threads by the request filter. */
    @Volatile var pageHost: String? = null
    @Volatile var adblockActive: Boolean = true

    internal var pageScript: ScriptHandler? = null
    internal var desktopScript: ScriptHandler? = null
    internal var lastRecorded: String? = null
    internal var siteCssPushedFor: String? = null
}
