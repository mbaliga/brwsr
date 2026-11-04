package dev.aarso.brwsr.adblock

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aarso.brwsr.data.FilterListCatalog
import dev.aarso.brwsr.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the live [FilterEngine]. Starts with the bundled floor list so blocking works on the
 * very first page, then compiles whatever lists are cached, then refreshes stale lists in the
 * background (every [MAX_AGE_MS]) and swaps the new engine in atomically.
 */
class FilterLists(
    private val context: Context,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    @Volatile
    var engine: FilterEngine = FilterEngine.empty()
        private set

    /** Bumped on every swap so injected cosmetic CSS can be rebuilt. */
    var generation by mutableIntStateOf(0)
        private set
    var ruleCount by mutableIntStateOf(0)
        private set
    var updating by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Running total for the session — the menu shows it as a number, nothing more. */
    val blockedCount = AtomicLong(0)

    private val dir = File(context.filesDir, "filters").apply { mkdirs() }
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            compile()
            if (System.currentTimeMillis() - settings.listsUpdatedAt > MAX_AGE_MS || cachedIds().isEmpty()) update()
        }
    }

    /** Downloads every enabled list, then recompiles. Safe to call repeatedly. */
    fun update() {
        if (updating) return
        updating = true
        scope.launch {
            val errors = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (spec in FilterListCatalog.all.filter { it.id in settings.enabledLists }) {
                    runCatching { download(spec.url, File(dir, "${spec.id}.txt")) }
                        .onFailure { errors += spec.title }
                }
            }
            lastError = errors.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Couldn't fetch: ")
            if (errors.size < settings.enabledLists.size) settings.listsUpdatedAt = System.currentTimeMillis()
            compile()
            updating = false
        }
    }

    /** Recompile from the bundled list + cached copies of the enabled lists. */
    suspend fun compile() {
        val enabled = settings.enabledLists
        val built = withContext(Dispatchers.Default) {
            val b = FilterEngine.Builder()
            runCatching { context.assets.open("filters/builtin.txt").bufferedReader().useLines { it.forEach(b::addLine) } }
            for (spec in FilterListCatalog.all) {
                if (spec.id !in enabled) continue
                val f = File(dir, "${spec.id}.txt")
                if (f.exists()) runCatching { f.bufferedReader().useLines { it.forEach(b::addLine) } }
            }
            b.build()
        }
        engine = built
        ruleCount = built.networkRuleCount
        generation++
    }

    private fun cachedIds() = dir.listFiles()?.map { it.nameWithoutExtension }.orEmpty()

    private fun download(url: String, target: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "brwsr")
        if (target.exists()) conn.ifModifiedSince = target.lastModified()
        try {
            when (conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> { target.setLastModified(System.currentTimeMillis()); return }
                HttpURLConnection.HTTP_OK -> Unit
                else -> error("HTTP ${conn.responseCode}")
            }
            val tmp = File(target.parentFile, target.name + ".part")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() < 64) { tmp.delete(); error("empty list") }
            tmp.renameTo(target)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MAX_AGE_MS = 4L * 24 * 60 * 60 * 1000
    }
}
