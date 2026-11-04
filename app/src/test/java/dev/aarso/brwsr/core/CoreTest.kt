package dev.aarso.brwsr.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class CoreTest {
    private val ddg = SearchEngine.DuckDuckGo

    @Test fun urlInputResolves() {
        assertEquals("https://example.com", UrlInput.resolve("example.com", ddg))
        assertEquals("https://example.com/a?b=1", UrlInput.resolve(" example.com/a?b=1 ", ddg))
        assertEquals("http://localhost:8080", UrlInput.resolve("localhost:8080", ddg))
        assertEquals("http://192.168.1.1", UrlInput.resolve("192.168.1.1", ddg))
        assertEquals("https://x.org", UrlInput.resolve("https://x.org", ddg))
        assertEquals("https://duckduckgo.com/?q=hello+world", UrlInput.resolve("hello world", ddg))
        assertEquals("https://duckduckgo.com/?q=kotlin", UrlInput.resolve("kotlin", ddg))
        assertEquals("https://duckduckgo.com/?q=3.14", UrlInput.resolve("3.14", ddg))
        assertEquals("https://duckduckgo.com/?q=what%3A+is", UrlInput.resolve("what: is", ddg))
        assertEquals("", UrlInput.resolve("   ", ddg))
    }

    @Test fun hosts() {
        assertEquals("example.co.uk", Hosts.registrable("a.b.example.co.uk"))
        assertEquals("example.com", Hosts.registrable("cdn.example.com"))
        assertEquals("news.ycombinator.com", Hosts.hostOf("https://www.news.ycombinator.com/x"))
        assertTrue(Hosts.isThirdParty("cdn.other.com", "www.site.com"))
        assertEquals(listOf("a.b.com", "b.com", "com"), Hosts.suffixes("a.b.com").toList())
    }

    @Test fun cornersSnap() {
        assertEquals(Corner.TopLeft, Corner.nearest(10f, 10f, 100f, 200f))
        assertEquals(Corner.BottomRight, Corner.nearest(90f, 190f, 100f, 200f))
        assertEquals(Corner.BottomLeft, Corner.nearest(40f, 120f, 100f, 200f))
    }

    @Test fun joystickHasDeadzoneAndCurve() {
        assertEquals(0f to 0f, Joystick.velocity(5f, 0f, 100f, 1000f))
        val (half, _) = Joystick.velocity(50f, 0f, 100f, 1000f)
        val (full, _) = Joystick.velocity(100f, 0f, 100f, 1000f)
        assertEquals(1000f, full, 0.01f)
        assertTrue(half in 1f..400f) // curve: half push is well under half speed
        val (cx, cy) = Joystick.clamp(300f, 400f, 100f)
        assertEquals(100f, hypot(cx, cy), 0.01f)
    }

    @Test fun trackballDecays() {
        var v = 2000f
        repeat(120) { v = Trackball.decay(v, 1 / 60f) }
        assertEquals(0f, v, 0f)
    }

    @Test fun puckZones() {
        val s = 200f
        assertEquals(PuckZone.Center, PuckLayout.zoneAt(100f, 100f, s))
        assertEquals(PuckZone.Top, PuckLayout.zoneAt(100f, 5f, s))
        assertEquals(PuckZone.Bottom, PuckLayout.zoneAt(100f, 195f, s))
        assertEquals(PuckZone.Left, PuckLayout.zoneAt(5f, 100f, s))
        assertEquals(PuckZone.Right, PuckLayout.zoneAt(195f, 100f, s))
        assertEquals(PuckZone.Outside, PuckLayout.zoneAt(2f, 2f, s))
    }

    @Test fun wallGeometryIsSymmetric() {
        assertEquals(0f, Wall.curvedYaw(1, 3), 0f)
        assertEquals(-Wall.curvedYaw(0, 4), Wall.curvedYaw(3, 4), 0.001f)
        val l = Wall.coverflow(-2f)
        val r = Wall.coverflow(2f)
        assertEquals(-l.yaw, r.yaw, 0.001f)
        assertEquals(0f, Wall.coverflow(0f).yaw, 0f)
        assertTrue(abs(Wall.carouselAngle(0, 4, 0f)) < 0.001f)
        assertEquals(90f, Wall.carouselAngle(1, 4, 0f), 0.001f)
    }
}
