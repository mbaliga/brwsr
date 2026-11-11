package dev.aarso.brwsr.adblock

import dev.aarso.brwsr.core.Hosts

/**
 * The blocker. Understands the useful core of Adblock Plus / uBlock syntax plus hosts files:
 *
 *  - hosts lines (`0.0.0.0 ads.example`) and bare domain lines → whole-domain blocks
 *  - `||domain^` → whole-domain block (O(labels) hash lookups per request)
 *  - other network patterns (`/ads/` + wildcard, `|https://x.com/track^`, `||cdn.com/ads.js`) →
 *    compiled once, indexed by their rarest literal token, so a request only tests the
 *    handful of rules that could possibly match it
 *  - `@@` exceptions, `$third-party`, `$~third-party`, `$domain=a.com|~b.com`
 *  - cosmetic `##selector` (generic) and `a.com,b.com##selector` (site-specific)
 *
 * Rules we cannot honour faithfully (scriptlets, `$redirect`, `$csp`, `$removeparam`,
 * procedural `#?#`, …) are skipped, never half-applied. Immutable once built: one instance is
 * read concurrently from WebView's IO threads, and a rebuilt one is swapped in atomically.
 */
class FilterEngine private constructor(
    private val blockedDomains: Set<String>,
    private val allowedDomains: Set<String>,
    private val blockIndex: Map<String, List<NetRule>>,
    private val blockUntokened: List<NetRule>,
    private val allowIndex: Map<String, List<NetRule>>,
    private val allowUntokened: List<NetRule>,
    val genericSelectors: List<String>,
    private val genericKeyed: Map<String, List<String>>,
    /** Generic selectors not anchored on a class or id — always injected (few, ~1k). */
    val genericLowly: List<String>,
    private val siteSelectors: Map<String, List<String>>,
    private val siteSelectorExceptions: Map<String, Set<String>>,
) {
    val networkRuleCount: Int get() = blockedDomains.size + blockIndex.values.sumOf { it.size } + blockUntokened.size

    /** Should a sub-resource request to [url] made by a page on [pageHost] be refused? */
    fun shouldBlock(url: String, pageHost: String?): Boolean {
        val lower = url.lowercase()
        val host = hostOf(lower) ?: return false
        val thirdParty = Hosts.isThirdParty(host, pageHost)

        for (h in Hosts.suffixes(host)) if (h in allowedDomains) return false
        var blocked = Hosts.suffixes(host).any { it in blockedDomains }
        if (!blocked) blocked = matchAny(lower, host, pageHost, thirdParty, blockIndex, blockUntokened)
        if (!blocked) return false
        return !matchAny(lower, host, pageHost, thirdParty, allowIndex, allowUntokened)
    }

    /** Domain-only check (used for top-level navigations a page tries without a user gesture). */
    fun isBlockedDomain(host: String): Boolean =
        Hosts.suffixes(host).none { it in allowedDomains } && Hosts.suffixes(host).any { it in blockedDomains }

    /** Site-specific element-hiding selectors for [host] (generic ones are injected separately). */
    fun siteSelectorsFor(host: String): List<String> {
        val out = LinkedHashSet<String>()
        for (h in Hosts.suffixes(host)) siteSelectors[h]?.let(out::addAll)
        val except = Hosts.suffixes(host).flatMap { siteSelectorExceptions[it].orEmpty() }.toSet()
        out.removeAll(except)
        return out.toList()
    }

    /**
     * Generic selectors that could match given the `.class` / `#id` keys present in a page
     * (the page reports them as it changes). Shipping all ~40k generic selectors into every
     * page would cost more than the ads; this ships the few dozen that can apply.
     */
    fun genericFor(keys: Collection<String>): List<String> {
        val out = ArrayList<String>()
        for (k in keys) genericKeyed[k]?.let(out::addAll)
        return out
    }

    internal fun debugStats(): String {
        val biggest = blockIndex.entries.sortedByDescending { it.value.size }.take(5).joinToString { "${it.key}=${it.value.size}" }
        return "domains=${blockedDomains.size} indexed=${blockIndex.values.sumOf { it.size }} untokened=${blockUntokened.size} allowUntokened=${allowUntokened.size} biggest=[$biggest] keyed=${genericKeyed.size} lowly=${genericLowly.size}"
    }

    /** Generic selectors minus any `site#@#selector` exceptions for [host]. */
    fun genericSelectorsExcept(host: String): Set<String> =
        Hosts.suffixes(host).flatMap { siteSelectorExceptions[it].orEmpty() }.toSet()

    private fun matchAny(
        url: String,
        host: String,
        pageHost: String?,
        thirdParty: Boolean,
        index: Map<String, List<NetRule>>,
        untokened: List<NetRule>,
    ): Boolean {
        for (token in tokens(url)) {
            val rules = index[token] ?: continue
            for (r in rules) if (r.matches(url, host, pageHost, thirdParty)) return true
        }
        for (r in untokened) if (r.matches(url, host, pageHost, thirdParty)) return true
        return false
    }

    internal class NetRule(
        private val regex: Regex,
        private val thirdParty: Boolean?,
        private val includeDomains: Set<String>,
        private val excludeDomains: Set<String>,
    ) {
        fun matches(url: String, host: String, pageHost: String?, isThirdParty: Boolean): Boolean {
            if (thirdParty != null && thirdParty != isThirdParty) return false
            if (includeDomains.isNotEmpty() || excludeDomains.isNotEmpty()) {
                val ph = pageHost ?: return false
                val suffixes = Hosts.suffixes(ph).toList()
                if (suffixes.any { it in excludeDomains }) return false
                if (includeDomains.isNotEmpty() && suffixes.none { it in includeDomains }) return false
            }
            return regex.containsMatchIn(url)
        }
    }

    class Builder {
        private val blockedDomains = HashSet<String>()
        private val allowedDomains = HashSet<String>()
        private val blockIndex = HashMap<String, MutableList<NetRule>>()
        private val blockUntokened = ArrayList<NetRule>()
        private val allowIndex = HashMap<String, MutableList<NetRule>>()
        private val allowUntokened = ArrayList<NetRule>()
        private val generic = LinkedHashSet<String>()
        private val site = HashMap<String, MutableList<String>>()
        private val siteExceptions = HashMap<String, MutableSet<String>>()
        private val tokenFrequency = HashMap<String, Int>()
        private val pending = ArrayList<Pending>()

        var skipped = 0
            private set

        fun addList(text: String): Builder {
            text.lineSequence().forEach(::addLine)
            return this
        }

        fun addLine(raw: String) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('!') || line.startsWith('[') || line.startsWith('#') && !line.startsWith("##")) return
            if (parseCosmetic(line)) return
            if (parseHosts(line)) return
            parseNetwork(line)
        }

        fun build(): FilterEngine {
            // Index each pattern by its least common token so hot tokens ("com", "www") don't
            // turn into long candidate lists.
            for (p in pending) {
                val token = p.tokens.minByOrNull { tokenFrequency[it] ?: 0 }
                val (index, untokened) = if (p.allow) allowIndex to allowUntokened else blockIndex to blockUntokened
                if (token == null) untokened += p.rule else index.getOrPut(token) { ArrayList(2) } += p.rule
            }
            pending.clear()
            val keyed = HashMap<String, MutableList<String>>()
            val lowly = ArrayList<String>()
            for (sel in generic) {
                val key = KEYED_SELECTOR.find(sel)?.value
                if (key == null) lowly += sel else keyed.getOrPut(key) { ArrayList(1) } += sel
            }
            return FilterEngine(
                blockedDomains, allowedDomains, blockIndex, blockUntokened, allowIndex, allowUntokened,
                generic.toList(), keyed, lowly, site, siteExceptions,
            )
        }

        private fun parseCosmetic(line: String): Boolean {
            val exc = line.indexOf("#@#")
            val idx = line.indexOf("##")
            if (exc < 0 && idx < 0) {
                // Other cosmetic flavours we deliberately don't run: #?#, #$#, #%#, #+js.
                return line.contains("#?#") || line.contains("#$#") || line.contains("#%#") || line.contains("#@?#") || line.contains("#@$#")
            }
            val isException = exc >= 0 && (idx < 0 || exc < idx)
            val at = if (isException) exc else idx
            val domains = line.substring(0, at)
            val selector = line.substring(at + if (isException) 3 else 2).trim()
            if (selector.isEmpty() || !isPlainSelector(selector)) { skipped++; return true }
            val hosts = domains.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (isException) {
                hosts.filterNot { it.startsWith('~') }.forEach { siteExceptions.getOrPut(it) { HashSet() } += selector }
                return true
            }
            val include = hosts.filterNot { it.startsWith('~') }
            if (include.isEmpty()) {
                if (hosts.isEmpty()) generic += selector else skipped++ // generic-with-exclusions: too broad to honour
            } else {
                include.forEach { site.getOrPut(it) { ArrayList(2) } += selector }
            }
            return true
        }

        private fun isPlainSelector(sel: String): Boolean =
            !sel.startsWith("+js(") && !sel.contains(":-abp-") && !sel.contains(":has-text(") &&
                !sel.contains(":matches-") && !sel.contains(":xpath(") && !sel.contains(":upward(") &&
                !sel.contains(":remove(") && !sel.contains(":style(") && !sel.contains(":min-text-length(") &&
                !sel.contains(":watch-attr(") && !sel.contains(":others(") && !sel.contains(":if(") &&
                !sel.contains(":contains(") && !sel.startsWith("^")

        private fun parseHosts(line: String): Boolean {
            val parts = line.substringBefore('#').trim().split(WS)
            val domain = when {
                parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::" || parts[0] == "::1") -> parts[1]
                parts.size == 1 && BARE_DOMAIN.matches(parts[0]) -> parts[0]
                else -> return false
            }.lowercase()
            if (domain in LOCAL_NAMES || !domain.contains('.')) return true
            blockedDomains += domain
            return true
        }

        private fun parseNetwork(original: String) {
            var line = original
            val allow = line.startsWith("@@")
            if (allow) line = line.substring(2)

            var thirdParty: Boolean? = null
            val include = HashSet<String>()
            val exclude = HashSet<String>()
            val dollar = optionsStart(line)
            if (dollar >= 0) {
                val options = line.substring(dollar + 1).split(',')
                line = line.substring(0, dollar)
                var typed = false
                var nonPageType = false
                for (raw in options) {
                    val opt = raw.trim().lowercase()
                    val name = opt.removePrefix("~").substringBefore('=')
                    when {
                        opt == "third-party" || opt == "3p" -> thirdParty = true
                        opt == "~third-party" || opt == "first-party" || opt == "1p" -> thirdParty = false
                        name == "domain" || name == "from" -> opt.substringAfter('=').split('|').forEach {
                            if (it.startsWith('~')) exclude += it.substring(1) else if (it.isNotEmpty()) include += it
                        }
                        name in UNSUPPORTED -> { skipped++; return }
                        name in PAGE_TYPES -> typed = true
                        name in RESOURCE_TYPES -> { typed = true; nonPageType = true }
                        name in IGNORED -> Unit
                        else -> { skipped++; return }
                    }
                }
                // Popup-/document-only rules describe navigations, not sub-resources.
                if (typed && !nonPageType && !allow) { skipped++; return }
                if (allow && typed && !nonPageType) {
                    // @@||site^$document — whitelist the whole site.
                    domainOnly(line)?.let { allowedDomains += it; return }
                    skipped++; return
                }
            }
            if (line.isEmpty() || line == "*" || line == "|" || line == "||") { skipped++; return }

            val plainDomain = domainOnly(line)
            if (plainDomain != null && thirdParty == null && include.isEmpty() && exclude.isEmpty()) {
                if (allow) allowedDomains += plainDomain else blockedDomains += plainDomain
                return
            }
            val regex = toRegex(line) ?: run { skipped++; return }
            val rule = NetRule(regex, thirdParty, include, exclude)
            val tokens = literalTokens(line)
            tokens.forEach { tokenFrequency[it] = (tokenFrequency[it] ?: 0) + 1 }
            pending += Pending(rule, tokens, allow)
        }

        private fun optionsStart(line: String): Int {
            // A '$' inside a /regex/ is not an options separator.
            if (line.startsWith('/') && line.lastIndexOf('/') > 0) {
                val end = line.lastIndexOf('/')
                val d = line.indexOf('$', end)
                return d
            }
            return line.lastIndexOf('$')
        }

        private class Pending(val rule: NetRule, val tokens: List<String>, val allow: Boolean)
    }

    companion object {
        /** A selector's leading `.class` / `#id` — the page must contain it for the selector to match. */
        private val KEYED_SELECTOR = Regex("^[.#][A-Za-z_-][\\w-]*(?![\\w-])")
        private val WS = Regex("\\s+")
        private val BARE_DOMAIN = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$", RegexOption.IGNORE_CASE)
        private val DOMAIN_ONLY = Regex("^\\|\\|([a-z0-9.-]+)\\^?\\|?$", RegexOption.IGNORE_CASE)
        private val TOKEN = Regex("[a-z0-9%]{3,}")
        private val LOCAL_NAMES = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")
        private val UNSUPPORTED = setOf(
            "redirect", "redirect-rule", "csp", "removeparam", "queryprune", "rewrite", "replace", "header",
            "permissions", "urltransform", "uritransform", "to", "method", "denyallow", "badfilter", "empty", "mp4",
            "generichide", "ghide", "elemhide", "ehide", "specifichide", "shide", "genericblock", "jsonprune",
            "urlskip", "cookie", "stealth", "app", "network", "content", "extension", "jsinject", "hls", "ipaddress",
        )
        private val PAGE_TYPES = setOf("document", "doc", "popup", "popunder", "all")
        private val RESOURCE_TYPES = setOf(
            "script", "image", "stylesheet", "css", "xmlhttprequest", "xhr", "subdocument", "frame", "media",
            "font", "object", "other", "ping", "websocket", "webrtc", "beacon", "object-subrequest",
        )
        private val IGNORED = setOf("important", "match-case", "strict1p", "strict3p", "inline-script", "inline-font")

        fun empty(): FilterEngine = Builder().build()

        private fun domainOnly(pattern: String): String? =
            DOMAIN_ONLY.matchEntire(pattern)?.groupValues?.get(1)?.lowercase()?.takeIf { it.contains('.') }

        /** Tokens of a URL, in the same alphabet the rule index is keyed by. */
        fun tokens(url: String): Sequence<String> = TOKEN.findAll(url).map { it.value }

        /**
         * Literal tokens of a pattern that every matching URL is guaranteed to contain *whole*
         * (as a maximal run of token characters), so they can key the index. A token is whole
         * only if both its edges are bounded: by a non-token literal, a `^` separator, or an
         * anchor — never by `*` or an unanchored pattern edge, where the URL may continue it.
         */
        internal fun literalTokens(pattern: String): List<String> {
            if (pattern.length > 2 && pattern.startsWith('/') && pattern.endsWith('/')) return emptyList()
            val p = pattern.lowercase()
            val out = ArrayList<String>()
            for (m in TOKEN.findAll(p)) {
                val before = m.range.first - 1
                val after = m.range.last + 1
                val leftOk = when {
                    before < 0 -> false                 // unanchored start: URL may extend it
                    p[before] == '*' -> false
                    p[before] == '|' -> true            // | or || anchor
                    else -> true                        // '^' or a non-token literal char
                }
                val rightOk = when {
                    after >= p.length -> false          // unanchored end
                    p[after] == '*' -> false
                    else -> true                        // '^', '|' end anchor, or non-token literal
                }
                if (leftOk && rightOk) out += m.value
            }
            return out
        }

        /** ABP pattern → Regex. `||` host anchor, `|` edge anchor, `^` separator, `*` wildcard. */
        internal fun toRegex(pattern: String): Regex? {
            if (pattern.length > 2 && pattern.startsWith('/') && pattern.endsWith('/')) {
                return runCatching { Regex(pattern.substring(1, pattern.length - 1), RegexOption.IGNORE_CASE) }.getOrNull()
            }
            var p = pattern.lowercase()
            val sb = StringBuilder()
            if (p.startsWith("||")) {
                sb.append("^[a-z][a-z0-9+.-]*://([^/?#]*\\.)?")
                p = p.substring(2)
            } else if (p.startsWith("|")) {
                sb.append('^')
                p = p.substring(1)
            }
            val endAnchor = p.endsWith('|')
            if (endAnchor) p = p.dropLast(1)
            for (c in p) {
                when (c) {
                    '*' -> sb.append(".*")
                    '^' -> sb.append("(?:[^a-z0-9_.%-]|$)")
                    else -> {
                        if ("\\.+?()[]{}$|/".indexOf(c) >= 0) sb.append('\\')
                        sb.append(c)
                    }
                }
            }
            if (endAnchor) sb.append('$')
            return runCatching { Regex(sb.toString()) }.getOrNull()
        }

        private fun hostOf(url: String): String? {
            val start = url.indexOf("://").takeIf { it > 0 }?.plus(3) ?: return null
            var end = url.length
            for (i in start until url.length) {
                val c = url[i]
                if (c == '/' || c == '?' || c == '#') { end = i; break }
            }
            var host = url.substring(start, end)
            host = host.substringAfterLast('@')
            if (host.startsWith('[')) return host
            host = host.substringBefore(':').trimEnd('.')
            return host.takeIf { it.isNotEmpty() }
        }
    }
}
