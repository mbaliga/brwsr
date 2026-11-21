package dev.aarso.brwsr.core

import kotlin.math.exp
import kotlin.math.ln

enum class OmniKind { Open, History }

/** One candidate for the address bar: an open page or a visited one. Pure data, so it tests on the JVM. */
data class OmniEntry(
    val url: String,
    val title: String,
    val kind: OmniKind,
    val visits: Int = 1,
    val lastVisit: Long = 0L,
)

/**
 * The address bar's local search: open pages first, then history, each ranked by how well the
 * typed words match and (for history) how recently and often the page is visited. Nothing here
 * leaves the device; web suggestions are a separate, opt-in layer.
 */
object OmniIndex {
    /** Identity of a page for de-duplication: no scheme, no www, no fragment, no trailing slash, lower-case. */
    fun key(url: String): String =
        url.trim().lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .substringBefore('#').trimEnd('/')

    fun search(
        query: String,
        open: List<OmniEntry>,
        history: List<OmniEntry>,
        now: Long,
        limit: Int = 6,
    ): List<OmniEntry> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        val openHits = open.mapNotNull { e -> match(e, tokens)?.let { e to it } }
            .sortedByDescending { it.second }.map { it.first }
        val seen = open.map { key(it.url) }.toHashSet()
        val histHits = history.asSequence()
            .filter { key(it.url) !in seen }
            .mapNotNull { e -> match(e, tokens)?.let { m -> e to (m + frecency(e, now)) } }
            .sortedByDescending { it.second }.map { it.first }.toList()

        return (openHits + histHits).take(limit)
    }

    /** Every typed word must hit the address or the title; null means this entry does not match. */
    private fun match(e: OmniEntry, tokens: List<String>): Double? {
        val k = key(e.url)
        val host = k.substringBefore('/')
        val title = e.title.lowercase()
        var score = 0.0
        for (t in tokens) {
            score += when {
                host.startsWith(t) -> 3.0
                host.contains(t) -> 2.2
                titleWordStarts(title, t) -> 2.0
                k.contains(t) -> 1.5
                title.contains(t) -> 1.0
                else -> return null
            }
        }
        return score
    }

    private fun titleWordStarts(title: String, t: String): Boolean =
        title.startsWith(t) || title.contains(" $t")

    /** Visits count for a little, and fade with a two-week half-life. */
    private fun frecency(e: OmniEntry, now: Long): Double {
        val ageDays = (now - e.lastVisit).coerceAtLeast(0) / 86_400_000.0
        return 0.5 * ln(e.visits + 1.0) * exp(-ageDays * 0.0495)
    }
}
