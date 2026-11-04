package dev.aarso.brwsr.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilterEngineTest {

    private fun engine(vararg lines: String) = FilterEngine.Builder().apply { lines.forEach(::addLine) }.build()

    @Test fun domainAnchorBlocksDomainAndSubdomains() {
        val e = engine("||ads.example.com^")
        assertTrue(e.shouldBlock("https://ads.example.com/x.js", "news.com"))
        assertTrue(e.shouldBlock("https://cdn.ads.example.com/x.js", "news.com"))
        assertFalse(e.shouldBlock("https://example.com/x.js", "news.com"))
        assertFalse(e.shouldBlock("https://badads.example.com/x.js", "news.com"))
    }

    @Test fun hostsFileAndBareDomains() {
        val e = engine("0.0.0.0 tracker.net", "127.0.0.1 localhost", "plain-list.io", "# comment")
        assertTrue(e.shouldBlock("http://tracker.net/p", null))
        assertTrue(e.shouldBlock("http://a.plain-list.io/p", null))
        assertFalse(e.shouldBlock("http://localhost/p", null))
    }

    @Test fun pathPatternsWithWildcardsAndSeparators() {
        val e = engine("/banner/*/img^", "|https://evil.com/track", "||cdn.net/ads.js|")
        assertTrue(e.shouldBlock("https://site.com/banner/123/img?x=1", "site.com"))
        assertTrue(e.shouldBlock("https://site.com/banner/123/img", "site.com"))
        assertFalse(e.shouldBlock("https://site.com/banner/123/imgs", "site.com"))
        assertTrue(e.shouldBlock("https://evil.com/track/1", "x.com"))
        assertFalse(e.shouldBlock("http://x.com/?u=https://evil.com/track", "x.com"))
        assertTrue(e.shouldBlock("https://static.cdn.net/ads.js", "x.com"))
        assertFalse(e.shouldBlock("https://static.cdn.net/ads.js?v=2", "x.com"))
    }

    @Test fun thirdPartyOption() {
        val e = engine("||widgets.com^\$third-party")
        assertTrue(e.shouldBlock("https://widgets.com/w.js", "blog.org"))
        assertFalse(e.shouldBlock("https://widgets.com/w.js", "widgets.com"))
        assertFalse(e.shouldBlock("https://cdn.widgets.com/w.js", "www.widgets.com"))
    }

    @Test fun domainOption() {
        val e = engine("/promo.js\$domain=shop.com|~safe.shop.com")
        assertTrue(e.shouldBlock("https://cdn.x/promo.js", "shop.com"))
        assertTrue(e.shouldBlock("https://cdn.x/promo.js", "m.shop.com"))
        assertFalse(e.shouldBlock("https://cdn.x/promo.js", "safe.shop.com"))
        assertFalse(e.shouldBlock("https://cdn.x/promo.js", "other.com"))
    }

    @Test fun exceptionsWin() {
        val e = engine("||cdn.com^", "@@||cdn.com/jquery.js", "||blocked.org^", "@@||blocked.org^\$document")
        assertTrue(e.shouldBlock("https://cdn.com/ad.js", "x.com"))
        assertFalse(e.shouldBlock("https://cdn.com/jquery.js", "x.com"))
        assertFalse(e.shouldBlock("https://blocked.org/a", "x.com"))
    }

    @Test fun unsupportedAndPageOnlyRulesAreSkippedNotHalfApplied() {
        val b = FilterEngine.Builder()
        b.addLine("||x.com^\$redirect=noop.js")
        b.addLine("||popup.com^\$popup")
        b.addLine("example.com##+js(abort-on-property-read, ads)")
        b.addLine("example.com#?#div:has-text(Sponsored)")
        val e = b.build()
        assertFalse(e.shouldBlock("https://x.com/a", null))
        assertFalse(e.shouldBlock("https://popup.com/a", null))
        assertTrue(e.siteSelectorsFor("example.com").isEmpty())
    }

    @Test fun regexRules() {
        val e = engine("/^https?:\\/\\/[a-z]{8}\\.xyz\\//")
        assertTrue(e.shouldBlock("https://abcdefgh.xyz/p", null))
        assertFalse(e.shouldBlock("https://abc.xyz/p", null))
    }

    @Test fun cosmeticRules() {
        val e = engine("##.ad", "news.com,~m.news.com##.promo", "news.com#@#.ad", "###banner")
        assertEquals(listOf(".ad", "#banner"), e.genericSelectors)
        assertEquals(listOf(".promo"), e.siteSelectorsFor("www.news.com"))
        assertEquals(setOf(".ad"), e.genericSelectorsExcept("news.com"))
    }

    @Test fun genericSelectorsSplitIntoKeyedAndLowly() {
        val e = engine("##.ad-box > span", "###sky", "##[data-ad]", "##div.promo", "##.a\\:b")
        assertEquals(listOf(".ad-box > span"), e.genericFor(listOf(".ad-box", ".other")))
        assertEquals(listOf("#sky"), e.genericFor(listOf("#sky")))
        assertTrue(e.genericLowly.containsAll(listOf("[data-ad]", "div.promo")))
    }

    @Test fun literalTokensOnlyWhenWhole() {
        assertEquals(listOf("cdn", "net", "ads"), FilterEngine.literalTokens("||cdn.net/ads.js|").take(3))
        assertTrue("ads" !in FilterEngine.literalTokens("/ads*"))
        assertTrue("banner" !in FilterEngine.literalTokens("banner/"))
    }

    @Test fun builtinListParsesCleanly() {
        val f = File("src/main/assets/filters/builtin.txt")
        val b = FilterEngine.Builder().addList(f.readText())
        val e = b.build()
        assertEquals(0, b.skipped)
        assertTrue(e.shouldBlock("https://securepubads.g.doubleclick.net/tag/js/gpt.js", "nytimes.com"))
        assertTrue(e.shouldBlock("https://www.google-analytics.com/analytics.js", "a.com"))
        assertTrue(e.shouldBlock("https://www.facebook.com/tr?id=1", "a.com"))
        assertFalse(e.shouldBlock("https://www.facebook.com/home", "a.com"))
        assertFalse(e.shouldBlock("https://en.wikipedia.org/wiki/Main_Page", "en.wikipedia.org"))
    }

    /**
     * Real-list smoke + speed check. Runs only when lists have been fetched into
     * build/filter-cache (see README); skipped on CI so tests never touch the network.
     */
    @Test fun realListsParseAndMatchFast() {
        val dir = File("build/filter-cache")
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return
        val t0 = System.nanoTime()
        val b = FilterEngine.Builder()
        files.forEach { b.addList(it.readText()) }
        val e = b.build()
        val buildMs = (System.nanoTime() - t0) / 1e6
        val urls = listOf(
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://www.googletagmanager.com/gtm.js?id=GTM-X",
            "https://cdn.jsdelivr.net/npm/jquery@3/dist/jquery.min.js",
            "https://upload.wikimedia.org/wikipedia/commons/a/a9/Example.jpg",
            "https://static.xx.fbcdn.net/rsrc.php/v3/y.js",
            "https://news.ycombinator.com/news.css?abc",
        )
        val t1 = System.nanoTime()
        var hits = 0
        repeat(2000) { for (u in urls) if (e.shouldBlock(u, "example.com")) hits++ }
        val perMatchUs = (System.nanoTime() - t1) / 1e3 / (2000 * urls.size)
        println(e.debugStats())
        println("filters: ${e.networkRuleCount} net rules, ${e.genericSelectors.size} generic selectors, skipped ${b.skipped}, build ${"%.0f".format(buildMs)}ms, match ${"%.1f".format(perMatchUs)}µs")
        assertTrue(e.shouldBlock(urls[0], "example.com"))
        assertTrue(e.shouldBlock(urls[1], "example.com"))
        assertFalse(e.shouldBlock(urls[2], "example.com"))
        assertFalse(e.shouldBlock(urls[3], "en.wikipedia.org"))
        assertTrue("match too slow: ${perMatchUs}µs", perMatchUs < 200)
    }
}
