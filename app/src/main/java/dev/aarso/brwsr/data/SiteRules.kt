package dev.aarso.brwsr.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aarso.brwsr.core.Hosts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** "Hide every element whose text contains [text], plus [up] ancestors." From Zappr's search. */
data class TextZap(val text: String, val up: Int)

enum class FontChoice(val label: String, val css: String?) {
    Site("Site", null),
    Sans("Sans", "system-ui, -apple-system, Roboto, sans-serif"),
    Serif("Serif", "Georgia, 'Noto Serif', serif"),
    Mono("Mono", "ui-monospace, 'Roboto Mono', monospace"),
}

/** Everything the user has decided about one site. */
data class SiteRule(
    val zaps: List<String> = emptyList(),
    val textZaps: List<TextZap> = emptyList(),
    val css: String = "",
    val nakd: Boolean = false,
    val adblockOff: Boolean = false,
    val desktop: Boolean = false,
    val jsOff: Boolean = false,
    val dark: Boolean = false,
    val unstick: Boolean = false,
    val hideImages: Boolean = false,
    val font: FontChoice = FontChoice.Site,
    val textZoom: Int = 100,
) {
    val isEmpty get() = this == SiteRule()

    fun toJson(): JSONObject = JSONObject().apply {
        put("zaps", JSONArray(zaps))
        put("textZaps", JSONArray(textZaps.map { JSONObject().put("t", it.text).put("u", it.up) }))
        put("css", css); put("nakd", nakd); put("adblockOff", adblockOff); put("desktop", desktop)
        put("jsOff", jsOff); put("dark", dark); put("unstick", unstick); put("hideImages", hideImages)
        put("font", font.name); put("textZoom", textZoom)
    }

    companion object {
        fun fromJson(o: JSONObject) = SiteRule(
            zaps = o.optJSONArray("zaps")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            textZaps = o.optJSONArray("textZaps")?.let { a ->
                List(a.length()) { a.getJSONObject(it).let { t -> TextZap(t.getString("t"), t.optInt("u")) } }
            }.orEmpty(),
            css = o.optString("css"),
            nakd = o.optBoolean("nakd"),
            adblockOff = o.optBoolean("adblockOff"),
            desktop = o.optBoolean("desktop"),
            jsOff = o.optBoolean("jsOff"),
            dark = o.optBoolean("dark"),
            unstick = o.optBoolean("unstick"),
            hideImages = o.optBoolean("hideImages"),
            font = runCatching { FontChoice.valueOf(o.optString("font")) }.getOrDefault(FontChoice.Site),
            textZoom = o.optInt("textZoom", 100),
        )
    }
}

/** Per-site rules keyed by host (without www). Compose state; persisted as one JSON file. */
class SiteRules(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "sites.json")
    var all by mutableStateOf(load())
        private set
    private var saveJob: Job? = null

    fun of(host: String?): SiteRule = host?.let { all[key(it)] } ?: SiteRule()

    fun update(host: String, change: (SiteRule) -> SiteRule) {
        val k = key(host)
        val next = change(all[k] ?: SiteRule())
        all = if (next.isEmpty) all - k else all + (k to next)
        scheduleSave()
    }

    fun clear(host: String) = update(host) { SiteRule() }

    private fun key(host: String) = host.lowercase().removePrefix("www.")

    private fun load(): Map<String, SiteRule> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { SiteRule.fromJson(o.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(300)
            val o = JSONObject()
            all.forEach { (k, v) -> o.put(k, v.toJson()) }
            val tmp = File(file.parentFile, "sites.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }

    companion object {
        fun hostKey(url: String?): String? = url?.let(Hosts::hostOf)
    }
}
