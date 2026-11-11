package dev.aarso.brwsr.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.aarso.brwsr.core.Hosts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.exp

data class Site(
    val host: String,
    val url: String,
    val title: String,
    val visits: Int = 0,
    val lastVisit: Long = 0L,
    val pinned: Boolean = false,
    val hidden: Boolean = false,
)

/**
 * The landing wall's data: sites ranked by "frecency" (visits that fade with a two-week
 * half-life), pinned first; plus a thumbnail and favicon per host on disk.
 */
class TopSites(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "topsites.json")
    private val thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val iconDir = File(context.filesDir, "icons").apply { mkdirs() }

    var sites by mutableStateOf(load())
        private set

    /** Loaded images, keyed by host. Compose reads these, so a fresh capture repaints the wall. */
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    val icons = mutableStateMapOf<String, ImageBitmap>()
    private var saveJob: Job? = null

    fun ranked(limit: Int = 24, now: Long = System.currentTimeMillis()): List<Site> =
        sites.values.filterNot { it.hidden }
            .sortedWith(compareByDescending<Site> { it.pinned }.thenByDescending { score(it, now) })
            .take(limit)

    fun recordVisit(url: String, title: String?) {
        val host = Hosts.hostOf(url) ?: return
        if (!url.startsWith("http")) return
        val prev = sites[host]
        val root = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/') + "/"
        sites = sites + (host to Site(
            host = host,
            url = prev?.url ?: root,
            title = title?.takeIf { it.isNotBlank() } ?: prev?.title ?: Hosts.displayName(host),
            visits = (prev?.visits ?: 0) + 1,
            lastVisit = System.currentTimeMillis(),
            pinned = prev?.pinned ?: false,
            hidden = false,
        ))
        scheduleSave()
    }

    fun updateTitle(url: String, title: String?) {
        val host = Hosts.hostOf(url) ?: return
        val prev = sites[host] ?: return
        if (title.isNullOrBlank() || prev.title == title) return
        // Keep the site-level title short: the first page's title often is the site name.
        if (prev.visits <= 1) sites = sites + (host to prev.copy(title = title))
        scheduleSave()
    }

    fun pin(host: String, pinned: Boolean) = edit(host) { it.copy(pinned = pinned, hidden = false) }
    fun hide(host: String) = edit(host) { it.copy(hidden = true, pinned = false) }

    fun addPinned(url: String, title: String) {
        val host = Hosts.hostOf(url) ?: return
        sites = sites + (host to (sites[host]?.copy(pinned = true, hidden = false)
            ?: Site(host, url, title, pinned = true, lastVisit = System.currentTimeMillis())))
        scheduleSave()
    }

    private fun edit(host: String, f: (Site) -> Site) {
        val s = sites[host] ?: return
        sites = sites + (host to f(s))
        scheduleSave()
    }

    fun hasThumb(host: String) = thumbs.containsKey(host) || File(thumbDir, "$host.webp").exists()

    fun saveThumb(host: String, bitmap: Bitmap) {
        thumbs[host] = bitmap.asImageBitmap()
        scope.launch(Dispatchers.IO) {
            runCatching {
                File(thumbDir, "$host.webp").outputStream().use {
                    @Suppress("DEPRECATION")
                    bitmap.compress(Bitmap.CompressFormat.WEBP, 78, it)
                }
            }
        }
    }

    fun saveIcon(host: String, bitmap: Bitmap) {
        if (icons.containsKey(host) && bitmap.width < 32) return
        icons[host] = bitmap.asImageBitmap()
        scope.launch(Dispatchers.IO) {
            runCatching { File(iconDir, "$host.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    /** Loads images for [hosts] that aren't in memory yet. */
    fun ensureImages(hosts: Collection<String>) {
        val missing = hosts.filter { it !in thumbs || it !in icons }
        if (missing.isEmpty()) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                missing.map { h ->
                    Triple(h, decode(File(thumbDir, "$h.webp")), decode(File(iconDir, "$h.png")))
                }
            }
            loaded.forEach { (h, t, i) ->
                if (t != null && h !in thumbs) thumbs[h] = t
                if (i != null && h !in icons) icons[h] = i
            }
        }
    }

    fun clearAll() {
        sites = seeds()
        thumbs.clear(); icons.clear()
        thumbDir.listFiles()?.forEach { it.delete() }
        iconDir.listFiles()?.forEach { it.delete() }
        scheduleSave()
    }

    private fun decode(f: File): ImageBitmap? =
        if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() else null

    private fun score(s: Site, now: Long): Double {
        val ageDays = (now - s.lastVisit).coerceAtLeast(0) / 86_400_000.0
        return (s.visits + 1) * exp(-ageDays * 0.0495) // ~14-day half-life
    }

    private fun load(): Map<String, Site> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { k ->
            val s = o.getJSONObject(k)
            Site(k, s.getString("url"), s.optString("title"), s.optInt("visits"), s.optLong("last"),
                s.optBoolean("pinned"), s.optBoolean("hidden"))
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: seeds()

    private fun seeds(): Map<String, Site> = listOf(
        "en.wikipedia.org" to "Wikipedia", "duckduckgo.com" to "DuckDuckGo", "news.ycombinator.com" to "Hacker News",
        "github.com" to "GitHub", "reddit.com" to "Reddit", "youtube.com" to "YouTube",
        "archive.org" to "Internet Archive", "theverge.com" to "The Verge",
    ).mapIndexed { i, (h, t) -> h to Site(h, "https://$h/", t, lastVisit = System.currentTimeMillis() - i * 60_000L) }.toMap()

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(500)
            val o = JSONObject()
            sites.forEach { (k, s) ->
                o.put(k, JSONObject().put("url", s.url).put("title", s.title).put("visits", s.visits)
                    .put("last", s.lastVisit).put("pinned", s.pinned).put("hidden", s.hidden))
            }
            val tmp = File(file.parentFile, "topsites.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
    }
}
