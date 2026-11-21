package dev.aarso.brwsr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun open(url: String, title: String) = OmniEntry(url, title, OmniKind.Open)
    private fun hist(url: String, title: String, visits: Int = 1, ageDays: Long = 0) =
        OmniEntry(url, title, OmniKind.History, visits, now - ageDays * day)

    @Test fun keyIgnoresSchemeWwwFragmentAndSlash() {
        val k = OmniIndex.key("https://www.Example.com/a/#top")
        assertEquals("example.com/a", k)
        assertEquals(k, OmniIndex.key("http://example.com/a"))
    }

    @Test fun openPagesComeBeforeHistory() {
        val r = OmniIndex.search(
            "wiki",
            open = listOf(open("https://en.wikipedia.org/wiki/Kotlin", "Kotlin - Wikipedia")),
            history = listOf(hist("https://wikihow.com/", "wikiHow", visits = 50)),
            now = now,
        )
        assertEquals(listOf(OmniKind.Open, OmniKind.History), r.map { it.kind })
    }

    @Test fun anOpenPageIsNotListedAgainFromHistory() {
        val r = OmniIndex.search(
            "example",
            open = listOf(open("https://example.com/a", "Example")),
            history = listOf(hist("http://www.example.com/a/", "Example"), hist("https://example.com/b", "Example B")),
            now = now,
        )
        assertEquals(listOf("https://example.com/a", "https://example.com/b"), r.map { it.url })
    }

    @Test fun everyWordMustMatchAddressOrTitle() {
        val h = listOf(
            hist("https://news.ycombinator.com/item?id=1", "Show HN: a tiny browser"),
            hist("https://news.ycombinator.com/item?id=2", "Ask HN: best editor?"),
        )
        val r = OmniIndex.search("tiny browser", emptyList(), h, now)
        assertEquals(listOf("https://news.ycombinator.com/item?id=1"), r.map { it.url })
        assertTrue(OmniIndex.search("tiny cats", emptyList(), h, now).isEmpty())
    }

    @Test fun hostMatchesOutrankTitleMatches() {
        val h = listOf(
            hist("https://blog.example.org/post", "Why github is great"),
            hist("https://github.com/mbaliga", "mbaliga"),
        )
        val r = OmniIndex.search("github", emptyList(), h, now)
        assertEquals("https://github.com/mbaliga", r.first().url)
    }

    @Test fun frecencyBreaksTiesTowardFrequentAndRecent() {
        val h = listOf(
            hist("https://a.com/", "Notes", visits = 1, ageDays = 60),
            hist("https://b.com/", "Notes", visits = 20, ageDays = 1),
        )
        assertEquals("https://b.com/", OmniIndex.search("notes", emptyList(), h, now).first().url)
    }

    @Test fun blankQueryAndLimit() {
        val h = (1..20).map { hist("https://site$it.com/", "Site $it") }
        assertTrue(OmniIndex.search("   ", emptyList(), h, now).isEmpty())
        assertEquals(6, OmniIndex.search("site", emptyList(), h, now).size)
        assertEquals(3, OmniIndex.search("site", emptyList(), h, now, limit = 3).size)
    }
}
