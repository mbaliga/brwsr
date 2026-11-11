package dev.aarso.brwsr.core

import java.net.URI

/** Host helpers. Pure JVM so the ad-block engine and its tests never touch android.net. */
object Hosts {

    /** Second-level labels under which registrations happen (co.uk, com.au, …). Heuristic, not the full PSL. */
    private val SECOND_LEVEL = setOf(
        "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "nic", "mil", "ltd", "plc",
    )

    fun hostOf(url: String): String? = runCatching {
        URI(url.trim()).host?.lowercase()?.trimEnd('.')?.removePrefix("www.")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Approximate registrable domain ("eTLD+1"): `a.b.example.co.uk` → `example.co.uk`. */
    fun registrable(host: String): String {
        val labels = host.lowercase().trimEnd('.').split('.')
        if (labels.size <= 2) return labels.joinToString(".")
        val tld = labels[labels.size - 1]
        val sld = labels[labels.size - 2]
        val take = if (tld.length == 2 && sld in SECOND_LEVEL) 3 else 2
        return labels.takeLast(take).joinToString(".")
    }

    fun isThirdParty(requestHost: String, pageHost: String?): Boolean =
        pageHost != null && registrable(requestHost) != registrable(pageHost)

    /** `a.b.c.com` → [a.b.c.com, b.c.com, c.com, com]. */
    fun suffixes(host: String): Sequence<String> = sequence {
        var h = host
        while (true) {
            yield(h)
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
    }

    /** A short, readable label for a site — used when there is no favicon. */
    fun displayName(host: String): String {
        val reg = registrable(host)
        return reg.substringBefore('.').replaceFirstChar { it.uppercase() }
    }
}
