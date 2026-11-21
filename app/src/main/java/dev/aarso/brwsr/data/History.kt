package dev.aarso.brwsr.data

import android.content.Context
import dev.aarso.brwsr.core.OmniEntry
import dev.aarso.brwsr.core.OmniIndex
import dev.aarso.brwsr.core.OmniKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Every page you have visited, on this phone only. It feeds the address bar's local results and
 * is wiped by "Clear browsing data". Capped so it never grows without bound.
 */
class History(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "history.json")
    private val pages = LinkedHashMap<String, OmniEntry>(load())
    private var saveJob: Job? = null

    fun entries(): List<OmniEntry> = pages.values.toList()

    fun record(url: String, title: String?, now: Long = System.currentTimeMillis()) {
        if (!url.startsWith("http")) return
        val k = OmniIndex.key(url)
        val prev = pages[k]
        pages[k] = OmniEntry(
            url = url,
            title = title?.takeIf { it.isNotBlank() } ?: prev?.title.orEmpty(),
            kind = OmniKind.History,
            visits = (prev?.visits ?: 0) + 1,
            lastVisit = now,
        )
        if (pages.size > MAX) trim()
        scheduleSave()
    }

    fun updateTitle(url: String, title: String?) {
        if (title.isNullOrBlank()) return
        val k = OmniIndex.key(url)
        val prev = pages[k] ?: return
        if (prev.title == title) return
        pages[k] = prev.copy(title = title)
        scheduleSave()
    }

    fun clear() {
        pages.clear()
        scheduleSave()
    }

    /** Drops the least-recently visited entries once the cap is passed. */
    private fun trim() {
        pages.values.sortedBy { it.lastVisit }.take(pages.size - MAX + TRIM_SLACK)
            .forEach { pages.remove(OmniIndex.key(it.url)) }
    }

    private fun load(): Map<String, OmniEntry> = runCatching {
        val a = JSONArray(file.readText())
        buildMap {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val e = OmniEntry(o.getString("u"), o.optString("t"), OmniKind.History, o.optInt("v", 1), o.optLong("l"))
                put(OmniIndex.key(e.url), e)
            }
        }
    }.getOrDefault(emptyMap())

    private fun scheduleSave() {
        saveJob?.cancel()
        val snapshot = pages.values.toList() // taken here, on the caller's thread, so the writer never races edits
        saveJob = scope.launch(Dispatchers.IO) {
            delay(800)
            val a = JSONArray()
            snapshot.forEach {
                a.put(JSONObject().put("u", it.url).put("t", it.title).put("v", it.visits).put("l", it.lastVisit))
            }
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val MAX = 3000
        const val TRIM_SLACK = 200
    }
}
