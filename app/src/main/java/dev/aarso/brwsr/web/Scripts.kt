package dev.aarso.brwsr.web

import android.content.Context
import dev.aarso.brwsr.adblock.FilterEngine
import dev.aarso.brwsr.data.Settings
import dev.aarso.brwsr.data.SiteRule
import org.json.JSONArray
import org.json.JSONObject

/** Loads the injected scripts from assets once and fills in their per-WebView parameters. */
class Scripts(context: Context) {
    private val page = context.assets.open("js/page.js").bufferedReader().readText()
    private val zappr = context.assets.open("js/zappr.js").bufferedReader().readText()
    val desktop: String = context.assets.open("js/desktop.js").bufferedReader().readText()

    private var lowlyFor: FilterEngine? = null
    private var lowlyCss = ""

    /** The document-start script: every site's rules + the always-on cosmetic CSS. */
    fun page(token: String, settings: Settings, rules: Map<String, SiteRule>, engine: FilterEngine): String {
        val sites = JSONObject()
        rules.forEach { (host, r) -> sites.put(host, r.toPageJson()) }
        val cfg = JSONObject()
            .put("sites", sites)
            .put("cosmetic", settings.adblock && settings.cosmetic)
            .put("lowly", lowly(engine))
            .put("globalCss", settings.globalCss)
            .put("gpc", settings.sendGpc)
        return page.replace("__CFG__", cfg.toString()).replace("__TOKEN__", token)
    }

    fun zappr(token: String): String = zappr.replace("__TOKEN__", token)

    private fun lowly(engine: FilterEngine): String {
        if (lowlyFor !== engine) {
            lowlyCss = hideCss(engine.genericLowly)
            lowlyFor = engine
        }
        return lowlyCss
    }

    companion object {
        /** One rule per selector: an invalid selector then only drops itself, not its group. */
        fun hideCss(selectors: Collection<String>): String = buildString {
            for (s in selectors) append(s).append("{display:none!important}\n")
        }

        private fun SiteRule.toPageJson(): JSONObject = JSONObject().apply {
            put("zaps", JSONArray(zaps))
            put("textZaps", JSONArray(textZaps.map { JSONObject().put("t", it.text).put("u", it.up) }))
            if (css.isNotBlank()) put("css", css)
            if (nakd) put("nakd", true)
            if (dark) put("dark", true)
            if (unstick) put("unstick", true)
            if (hideImages) put("hideImages", true)
            if (adblockOff) put("adblockOff", true)
            font.css?.let { put("font", it) }
        }

        /** evaluateJavascript hands back JSON; unwrap a returned string (or null). */
        fun decode(raw: String?): String? {
            if (raw == null || raw == "null" || raw == "undefined") return null
            return runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull()?.let {
                if (it == JSONObject.NULL) null else it.toString()
            }
        }
    }
}
