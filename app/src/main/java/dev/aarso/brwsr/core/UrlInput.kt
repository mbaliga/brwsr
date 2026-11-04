package dev.aarso.brwsr.core

import java.net.URLEncoder

enum class SearchEngine(val label: String, val template: String) {
    DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    Startpage("Startpage", "https://www.startpage.com/do/search?q=%s"),
    Brave("Brave", "https://search.brave.com/search?q=%s"),
    Google("Google", "https://www.google.com/search?q=%s"),
    Kagi("Kagi", "https://kagi.com/search?q=%s"),
}

/** Turns whatever was typed into the pill into something loadable. */
object UrlInput {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val HOSTLIKE = Regex("^(localhost|[\\p{L}0-9-]+(\\.[\\p{L}0-9-]+)+|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?$")

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun resolve(raw: String, engine: SearchEngine): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (text.contains(' ')) return search(text, engine)
        if (SCHEME.containsMatchIn(text)) {
            val scheme = text.substringBefore(':').lowercase()
            if (scheme in setOf("http", "https", "file", "about", "data", "javascript")) return text
            // "localhost:8080" and "example.com:443/x" parse as a scheme; treat them as hosts.
            if (!HOSTLIKE.matches(text)) return search(text, engine)
        }
        if (HOSTLIKE.matches(text)) {
            val host = text.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            val tld = host.substringAfterLast('.', "")
            val local = host == "localhost" || IPV4.matches(host)
            // "3.14" or "v1.2" style input is not a site.
            if (!local && (tld.length < 2 || tld.all { it.isDigit() })) return search(text, engine)
            return (if (local) "http://" else "https://") + text
        }
        return search(text, engine)
    }

    fun search(query: String, engine: SearchEngine): String =
        engine.template.replace("%s", URLEncoder.encode(query, "UTF-8"))

    /** What the pill shows for a loaded page: no scheme, no www, no trailing slash. */
    fun pretty(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
}
