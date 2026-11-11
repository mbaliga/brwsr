package dev.aarso.brwsr.web

import android.webkit.JavascriptInterface

/**
 * The only door from page JavaScript into brwsr. Every frame can see `BrwsrBridge`, so each
 * call must carry this WebView's random [token], which only our injected scripts hold (in a
 * closure, never on `window`). A page that calls it without the token is ignored, and even
 * with it, all it can do is ask for cosmetic CSS or report a Zappr tap.
 */
class JsBridge(
    private val token: String,
    private val onSurvey: (keys: List<String>) -> Unit,
    private val onZappr: (json: String) -> Unit,
) {
    @JavascriptInterface
    fun survey(token: String, json: String) {
        if (token != this.token || json.length > 400_000) return
        val keys = runCatching {
            val a = org.json.JSONArray(json)
            List(minOf(a.length(), 8000)) { a.getString(it) }
        }.getOrNull() ?: return
        onSurvey(keys)
    }

    @JavascriptInterface
    fun zappr(token: String, json: String) {
        if (token != this.token || json.length > 20_000) return
        onZappr(json)
    }
}
