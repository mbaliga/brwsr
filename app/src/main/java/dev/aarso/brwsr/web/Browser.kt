package dev.aarso.brwsr.web

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.aarso.brwsr.adblock.FilterLists
import dev.aarso.brwsr.core.Hosts
import dev.aarso.brwsr.core.UrlInput
import dev.aarso.brwsr.data.Settings
import dev.aarso.brwsr.data.SiteRule
import dev.aarso.brwsr.data.SiteRules
import dev.aarso.brwsr.data.TextZap
import dev.aarso.brwsr.data.TopSites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** What the browser needs from its Activity. */
interface BrowserHost {
    fun chooseFiles(intent: Intent, callback: ValueCallback<Array<Uri>>): Boolean
    fun launchExternal(intent: Intent): Boolean
    fun enterVideoFullscreen(view: View, onExit: () -> Unit)
    fun exitVideoFullscreen()
}

/** Zappr's UI state (the page does the inspecting; native shows the controls). */
class ZapprState {
    var active by mutableStateOf(false)
    var desc by mutableStateOf<String?>(null)
    var similarCount by mutableStateOf(0)
    var searchCount by mutableStateOf(-1)
    var searchMode by mutableStateOf("none")
    var searchUp by mutableStateOf(0)
    fun reset() { desc = null; similarCount = 0; searchCount = -1; searchMode = "none"; searchUp = 0 }
}

@SuppressLint("SetJavaScriptEnabled")
class Browser(
    private val context: Context,
    val settings: Settings,
    val rules: SiteRules,
    val topSites: TopSites,
    val filters: FilterLists,
    private val scope: CoroutineScope,
) {
    var host: BrowserHost? = null
    private val scripts = Scripts(context)
    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1L

    val tabs = mutableStateListOf<Tab>()
    var current by mutableStateOf<Tab?>(null)
        private set
    val zappr = ZapprState()
    private val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private val mobileUa: String by lazy {
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv)", ")")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")
    }
    private val desktopUa: String by lazy {
        val chrome = Regex("Chrome/[\\d.]+").find(mobileUa)?.value ?: "Chrome/130.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            // Any change to rules, look, or the compiled filters re-arms every tab's script.
            snapshotFlow {
                listOf(rules.all, settings.globalCss, settings.cosmetic, settings.adblock, settings.sendGpc, filters.generation)
            }.debounce(150).collect { refreshScripts() }
        }
    }

    // ---- tabs ------------------------------------------------------------------------------

    fun newTab(url: String? = null, select: Boolean = true): Tab {
        val tab = Tab(nextId++, createWebView())
        configure(tab)
        tabs += tab
        if (select) select(tab)
        if (url != null) load(tab, url)
        return tab
    }

    fun select(tab: Tab) {
        if (zappr.active) stopZappr()
        current?.takeIf { it !== tab }?.let { captureThumb(it) }
        current = tab
        tab.webView.onResume()
    }

    fun close(tab: Tab) {
        val i = tabs.indexOf(tab)
        if (i < 0) return
        tabs.removeAt(i)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
        if (current === tab) {
            current = null
            if (tabs.isEmpty()) newTab() else select(tabs[(i - 1).coerceAtLeast(0)])
        }
    }

    fun goHome() {
        val t = current ?: return
        if (zappr.active) stopZappr()
        captureThumb(t)
        t.landing = true
    }

    fun showPage() { current?.let { if (it.url != null) it.landing = false } }

    fun submit(input: String) {
        val url = UrlInput.resolve(input, settings.searchEngine)
        if (url.isEmpty()) return
        val t = current ?: newTab()
        load(t, url)
    }

    fun load(tab: Tab, url: String) {
        tab.landing = false
        applySiteSettings(tab, Hosts.hostOf(url))
        val headers = if (settings.sendGpc) mapOf("Sec-GPC" to "1", "DNT" to "1") else emptyMap()
        tab.webView.loadUrl(url, headers)
    }

    fun back(): Boolean {
        val t = current ?: return false
        if (zappr.active) { stopZappr(); return true }
        if (t.landing) {
            if (t.url != null) { t.landing = false; return true }
            return false
        }
        if (t.webView.canGoBack()) { t.webView.goBack(); return true }
        goHome()
        return true
    }

    fun forward() { current?.webView?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { current?.webView?.reload() }

    fun restore(urls: List<String>) {
        urls.forEach { newTab(it, select = false).landing = true }
        if (tabs.isEmpty()) newTab() else select(tabs.last())
    }

    fun openUrls(): List<String> = tabs.mapNotNull { it.url }

    // ---- per-site switches --------------------------------------------------------------

    fun ruleFor(tab: Tab?): SiteRule = rules.of(tab?.pageHost)

    fun updateRule(change: (SiteRule) -> SiteRule) {
        val host = current?.pageHost ?: return
        rules.update(host, change)
    }

    fun setDesktop(on: Boolean) {
        val t = current ?: return
        t.pageHost?.let { h -> rules.update(h) { it.copy(desktop = on) } }
        applyDesktop(t, on)
        t.webView.reload()
    }

    fun setJsEnabled(on: Boolean) {
        updateRule { it.copy(jsOff = !on) }
        current?.let { applySiteSettings(it, it.pageHost); it.webView.reload() }
    }

    fun setAdblockForSite(on: Boolean) {
        updateRule { it.copy(adblockOff = !on) }
        current?.let { it.adblockActive = on && settings.adblock; it.webView.reload() }
    }

    /** Nakd: strip the page of its CSS. [always] remembers it for the site. */
    fun setNakd(on: Boolean, always: Boolean) {
        val t = current ?: return
        t.nakdNow = on
        if (always || !on) updateRule { it.copy(nakd = on && always) }
        if (on) {
            if (!always) t.webView.evaluateJavascript(NAKD_ONCE, null)
        } else {
            t.webView.reload()
        }
    }

    fun setTextZoom(percent: Int) {
        updateRule { it.copy(textZoom = percent) }
        current?.webView?.settings?.textZoom = percent
    }

    // ---- Zappr -------------------------------------------------------------------------

    fun startZappr() {
        val t = current ?: return
        if (t.landing) return
        zappr.reset()
        zappr.active = true
        t.webView.evaluateJavascript(scripts.zappr(t.token), null)
    }

    fun stopZappr() {
        zappr.active = false
        zappr.reset()
        current?.webView?.evaluateJavascript("window.__brwsrZappr&&__brwsrZappr.stop()", null)
    }

    fun zapprParent() = zapprCall("parent()")
    fun zapprChild() = zapprCall("child()")

    fun zap(similar: Boolean) {
        val t = current ?: return
        val host = t.pageHost ?: return
        t.webView.evaluateJavascript("window.__brwsrZappr&&__brwsrZappr.zap($similar)") { raw ->
            val sel = Scripts.decode(raw) ?: return@evaluateJavascript
            rules.update(host) { r -> if (sel in r.zaps) r else r.copy(zaps = r.zaps + sel) }
            zappr.desc = null
        }
    }

    /** Hand the selection to the CSS editor. */
    fun zapprSelector(similar: Boolean, result: (String?) -> Unit) {
        current?.webView?.evaluateJavascript("window.__brwsrZappr&&__brwsrZappr.selector($similar)") { result(Scripts.decode(it)) }
    }

    fun zapprSearch(query: String, up: Int) {
        zappr.searchUp = up
        current?.webView?.evaluateJavascript("window.__brwsrZappr&&__brwsrZappr.search(${JSONObject.quote(query)},$up)") { raw ->
            val o = Scripts.decode(raw)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@evaluateJavascript
            zappr.searchCount = o.optInt("count")
            zappr.searchMode = o.optString("mode")
        }
    }

    fun zapSearch() {
        val t = current ?: return
        val host = t.pageHost ?: return
        t.webView.evaluateJavascript("window.__brwsrZappr&&__brwsrZappr.zapSearch()") { raw ->
            val o = Scripts.decode(raw)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@evaluateJavascript
            rules.update(host) { r ->
                when (o.optString("type")) {
                    "css" -> o.optString("selector").let { s -> if (s.isBlank() || s in r.zaps) r else r.copy(zaps = r.zaps + s) }
                    "text" -> r.copy(textZaps = r.textZaps + TextZap(o.optString("text"), o.optInt("up")))
                    else -> r
                }
            }
            zappr.searchCount = -1
        }
    }

    private fun zapprCall(fn: String) {
        current?.webView?.evaluateJavascript("window.__brwsrZappr&&__brwsrZappr.$fn", null)
    }

    private fun onZapprMessage(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        main.post {
            when (o.optString("type")) {
                "select" -> { zappr.desc = o.optString("desc"); zappr.similarCount = o.optInt("similarCount"); zappr.searchCount = -1 }
                "none" -> zappr.desc = null
            }
        }
    }

    // ---- scripts -------------------------------------------------------------------------

    private fun refreshScripts() {
        for (t in tabs) installPageScript(t, live = true)
        tabs.forEach { it.adblockActive = settings.adblock && !rules.of(it.pageHost).adblockOff }
    }

    private fun installPageScript(t: Tab, live: Boolean) {
        val src = scripts.page(t.token, settings, rules.all, filters.engine)
        if (docStart) {
            t.pageScript?.remove()
            t.pageScript = runCatching { WebViewCompat.addDocumentStartJavaScript(t.webView, src, setOf("*")) }.getOrNull()
        }
        // Re-running the script on the live page applies the new rules without a reload.
        if (live && !t.landing && t.url != null) t.webView.evaluateJavascript(src, null)
    }

    private fun applyDesktop(t: Tab, on: Boolean) {
        t.desktop = on
        val s = t.webView.settings
        s.userAgentString = if (on) desktopUa else mobileUa
        s.useWideViewPort = true
        s.loadWithOverviewMode = on
        if (docStart) {
            t.desktopScript?.remove()
            t.desktopScript = if (on) runCatching {
                WebViewCompat.addDocumentStartJavaScript(t.webView, scripts.desktop, setOf("*"))
            }.getOrNull() else null
        }
    }

    private fun applySiteSettings(t: Tab, host: String?) {
        val r = rules.of(host)
        val s = t.webView.settings
        s.javaScriptEnabled = !r.jsOff
        s.textZoom = r.textZoom
        if (r.desktop != t.desktop) applyDesktop(t, r.desktop)
        t.adblockActive = settings.adblock && !r.adblockOff
        t.pageHost = host
    }

    /** Cosmetic CSS that depends on the page: the list's site-specific rules. */
    private fun pushSiteCss(t: Tab) {
        val host = t.pageHost ?: return
        if (t.siteCssPushedFor == t.url) return
        t.siteCssPushedFor = t.url
        if (!t.adblockActive || !settings.cosmetic) return
        val sels = filters.engine.siteSelectorsFor(host)
        if (sels.isEmpty()) return
        t.webView.evaluateJavascript("window.__brwsr&&__brwsr.addCss(${JSONObject.quote(Scripts.hideCss(sels))})", null)
    }

    /** The page reported class/id keys; answer with the generic rules that could match. */
    private fun onSurvey(t: Tab, keys: List<String>) {
        if (!t.adblockActive || !settings.cosmetic) return
        val engine = filters.engine
        val host = t.pageHost ?: return
        val except = engine.genericSelectorsExcept(host)
        val sels = engine.genericFor(keys).filterNot { it in except }
        if (sels.isEmpty()) return
        val js = "window.__brwsr&&__brwsr.addCss(${JSONObject.quote(Scripts.hideCss(sels))})"
        main.post { if (tabs.contains(t)) t.webView.evaluateJavascript(js, null) }
    }

    // ---- WebView ------------------------------------------------------------------------

    private fun createWebView(): WebView = WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            useWideViewPort = true
            loadWithOverviewMode = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = mobileUa
        }
    }

    private fun configure(t: Tab) {
        val wv = t.webView
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, !settings.blockThirdPartyCookies)
        wv.addJavascriptInterface(JsBridge(t.token, { keys -> onSurvey(t, keys) }, ::onZapprMessage), "BrwsrBridge")
        wv.webViewClient = Client(t)
        wv.webChromeClient = Chrome(t)
        wv.setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime) }
        installPageScript(t, live = false)
    }

    private inner class Client(private val t: Tab) : WebViewClient() {
        private val empty get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame || !t.adblockActive) return null
            val url = request.url.toString()
            if (!url.startsWith("http")) return null
            if (!filters.engine.shouldBlock(url, t.pageHost)) return null
            filters.blockedCount.incrementAndGet()
            main.post { t.blocked++ }
            return empty
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                val host = uri.host?.lowercase()
                // Redirect chains into ad networks that the user didn't tap for.
                if (request.isForMainFrame && !request.hasGesture() && t.adblockActive && host != null &&
                    filters.engine.isBlockedDomain(host) && request.isRedirect.not()
                ) return true
                if (request.isForMainFrame) {
                    val h = Hosts.hostOf(uri.toString())
                    val r = rules.of(h)
                    if (view.settings.javaScriptEnabled == r.jsOff || r.desktop != t.desktop) {
                        applySiteSettings(t, h)
                        view.loadUrl(uri.toString())
                        return true
                    }
                }
                return false
            }
            if (!request.hasGesture()) return true
            return openExternal(uri.toString())
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            t.url = url
            t.landing = false
            t.blocked = 0
            t.nakdNow = false
            t.siteCssPushedFor = null
            val host = Hosts.hostOf(url)
            if (host != t.pageHost || view.settings.textZoom != rules.of(host).textZoom) applySiteSettings(t, host)
            t.pageHost = host
            t.favicon = host?.let { topSites.icons[it] }
            if (!docStart) installPageScript(t, live = false)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (!docStart) view.evaluateJavascript(scripts.page(t.token, settings, rules.all, filters.engine), null)
            pushSiteCss(t)
        }

        override fun onPageFinished(view: WebView, url: String) {
            t.url = url
            t.canGoBack = view.canGoBack()
            t.canGoForward = view.canGoForward()
            pushSiteCss(t)
            if (t.lastRecorded != url && url.startsWith("http")) {
                t.lastRecorded = url
                topSites.recordVisit(url, view.title)
            }
            main.postDelayed({ if (current === t && !t.landing && t.progress >= 100) captureThumb(t) }, 900)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            t.url = url
            t.canGoBack = view.canGoBack()
            t.canGoForward = view.canGoForward()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // A dead renderer must not take the app down: replace the WebView and reload.
            val url = t.url
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            t.webView = createWebView()
            t.pageScript = null
            t.desktopScript = null
            configure(t)
            if (t.desktop) applyDesktop(t, true)
            url?.let { load(t, it) }
            return true
        }
    }

    private inner class Chrome(private val t: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { t.progress = newProgress }

        override fun onReceivedTitle(view: WebView, title: String?) {
            t.title = title.orEmpty()
            t.url?.let { topSites.updateTitle(it, title) }
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap) {
            t.favicon = icon.asImageBitmap()
            t.pageHost?.let { topSites.saveIcon(it, icon) }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (settings.blockPopups && !isUserGesture) return false
            val tab = newTab()
            tab.landing = false
            (resultMsg.obj as WebView.WebViewTransport).webView = tab.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) { tabs.firstOrNull { it.webView === window }?.let(::close) }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            val h = host ?: return false
            return runCatching { h.chooseFiles(params.createIntent(), callback) }.getOrDefault(false)
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            host?.enterVideoFullscreen(view) { callback.onCustomViewHidden() } ?: callback.onCustomViewHidden()
        }

        override fun onHideCustomView() { host?.exitVideoFullscreen() }

        // Privacy by default: sites don't get location, camera or microphone.
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)
    }

    private fun openExternal(url: String): Boolean {
        val intent = runCatching {
            if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }.getOrNull() ?: return true
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        val launched = host?.launchExternal(intent) ?: false
        if (!launched) intent.getStringExtra("browser_fallback_url")?.let { fb -> current?.let { load(it, fb) } }
        return true
    }

    private fun download(url: String, ua: String, disposition: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, disposition, mime)
        runCatching {
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", ua)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(context, name, Toast.LENGTH_SHORT).show()
        }.onFailure {
            try {
                host?.launchExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: ActivityNotFoundException) {}
        }
    }

    /** Thumbnail for the wall and the tab switcher: the top of the page, card-shaped. */
    fun captureThumb(t: Tab) {
        val wv = t.webView
        if (t.landing || t.url == null || wv.width <= 0 || wv.height <= 0 || !wv.isAttachedToWindow) return
        runCatching {
            val scale = 360f / wv.width
            val h = minOf(wv.height.toFloat(), wv.width / CARD_ASPECT)
            val bmp = Bitmap.createBitmap(360, (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            c.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(c)
            t.thumb = bmp.asImageBitmap()
            // Only the top of a site belongs on the wall, not wherever the user scrolled to.
            if (wv.scrollY < 40) t.pageHost?.let { topSites.saveThumb(it, bmp) }
        }
    }

    fun destroy() {
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
    }

    companion object {
        /** Wall cards are 4:5 (width:height). */
        const val CARD_ASPECT = 0.8f

        private const val NAKD_ONCE = """(function(){
  document.querySelectorAll('link[rel~="stylesheet" i],style:not([data-brwsr])').forEach(function(e){e.remove()});
  document.querySelectorAll('[style]').forEach(function(e){if(!e.hasAttribute('data-brwsr-zapped')&&!e.closest('[data-brwsr-ui]'))e.removeAttribute('style')});
  try{document.adoptedStyleSheets=[]}catch(_){}
  var b=document.createElement('style');b.setAttribute('data-brwsr','3');
  b.textContent='img,video,iframe,svg,canvas,table{max-width:100%!important;height:auto}pre{white-space:pre-wrap}';
  (document.head||document.documentElement).appendChild(b);
})()"""
    }
}
