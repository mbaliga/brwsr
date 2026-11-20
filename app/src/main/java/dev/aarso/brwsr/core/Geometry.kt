package dev.aarso.brwsr.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/** Where the orb lives. User-chosen; the orb snaps to the nearest one after a drag. */
enum class Corner(val isBottom: Boolean, val isRight: Boolean) {
    TopLeft(false, false), TopRight(false, true), BottomLeft(true, false), BottomRight(true, true);

    companion object {
        fun nearest(x: Float, y: Float, width: Float, height: Float): Corner {
            val right = x > width / 2f
            val bottom = y > height / 2f
            return entries.first { it.isRight == right && it.isBottom == bottom }
        }
    }
}

/** How the landing wall is laid out. */
enum class WallStyle(val label: String) {
    Curved("Curved wall"), Flat("Flat grid"), Coverflow("Coverflow"), Carousel("Carousel");
}

object Wall {
    /**
     * Sign convention for the 3D wall: RenderNode rotationY. Left of centre gets a negative
     * angle so its outer (left) edge comes toward the viewer and its inner edge recedes — a
     * concave wall you stand inside, like the old Top Sites. Flip this one constant if a
     * device shows it convex.
     */
    const val ROTATION_SIGN = 1f

    /** Per-column yaw for the curved wall: the outermost columns turn [maxDeg]. */
    fun curvedYaw(col: Int, cols: Int, maxDeg: Float = 24f): Float {
        if (cols <= 1) return 0f
        val center = (cols - 1) / 2f
        return ROTATION_SIGN * (col - center) / center * maxDeg
    }

    /**
     * Coverflow: [offset] is the signed distance from the focused item in item-widths.
     * Returns yaw, scale and the horizontal squeeze factor (side items stack tighter).
     */
    data class Flow(val yaw: Float, val scale: Float, val translateFactor: Float, val z: Float)

    fun coverflow(offset: Float, sideDeg: Float = 62f): Flow {
        val a = abs(offset)
        val t = min(a, 1f)
        val yaw = ROTATION_SIGN * sign(offset) * sideDeg * t
        val scale = 1f - 0.18f * t
        // First neighbour sits ~0.62 widths out; further ones stack 0.28 widths apart.
        val translate = if (a <= 1f) 0.62f * a else 0.62f + 0.28f * (a - 1f)
        return Flow(yaw, scale, sign(offset) * translate, -a)
    }

    /** Carousel ring: items spaced evenly on a cylinder; [angleDeg] around the ring, 0 = facing. */
    fun carouselAngle(index: Int, count: Int, rotationDeg: Float): Float =
        if (count == 0) 0f else (index * 360f / count + rotationDeg).let { ((it + 180f).mod(360f)) - 180f }

    private fun sign(v: Float) = if (v < 0f) -1f else if (v > 0f) 1f else 0f
}

/** The puck's centre stick: a deadzone, then a curve so small pushes give precise motion. */
object Joystick {
    const val DEADZONE = 0.12f
    const val EXPONENT = 1.8f

    /** [dx],[dy] = knob displacement in px; returns cursor velocity (px/s) on each axis. */
    fun velocity(dx: Float, dy: Float, radius: Float, maxSpeed: Float): Pair<Float, Float> {
        val d = hypot(dx, dy)
        if (radius <= 0f || d == 0f) return 0f to 0f
        val m = min(d / radius, 1f)
        if (m <= DEADZONE) return 0f to 0f
        val shaped = ((m - DEADZONE) / (1f - DEADZONE)).pow(EXPONENT) * maxSpeed
        return dx / d * shaped to dy / d * shaped
    }

    /** Clamps a drag to the stick's travel circle. */
    fun clamp(dx: Float, dy: Float, radius: Float): Pair<Float, Float> {
        val d = hypot(dx, dy)
        if (d <= radius || d == 0f) return dx to dy
        return dx / d * radius to dy / d * radius
    }
}

/** Trackball: relative motion with momentum that bleeds off exponentially. */
object Trackball {
    const val GAIN = 1.6f
    const val TAU_SECONDS = 0.35f
    const val STOP_SPEED = 12f

    fun decay(velocity: Float, dtSeconds: Float): Float {
        val v = velocity * exp(-dtSeconds / TAU_SECONDS)
        return if (abs(v) < STOP_SPEED) 0f else v
    }
}

/** Which part of the puck a point lands on (see the puck mock: four segments around a ring). */
enum class PuckZone { Center, Top, Bottom, Left, Right, Outside }

object PuckLayout {
    /** Inner (stick) radius as a fraction of the outer radius. */
    const val INNER = 0.56f

    fun zoneAt(x: Float, y: Float, size: Float): PuckZone {
        val r = size / 2f
        val dx = x - r
        val dy = y - r
        val d = hypot(dx, dy)
        if (d > r) return PuckZone.Outside
        if (d <= r * INNER) return PuckZone.Center
        // Diagonals split the ring: |dy| > |dx| is top/bottom.
        return if (abs(dy) >= abs(dx)) {
            if (dy < 0) PuckZone.Top else PuckZone.Bottom
        } else {
            if (dx < 0) PuckZone.Left else PuckZone.Right
        }
    }
}

/**
 * Pinch-in recogniser for "show all tabs". Feed it the distance between two fingers; it fires
 * once per gesture, when they have closed to [threshold] of where they started. Pure maths so
 * the feel can be tested without a device.
 */
class PinchIn(private val threshold: Float = 0.72f, private val minStart: Float = 120f) {
    private var start = 0f
    private var fired = false

    /** Second finger went down [distance] px from the first. Pass armed=false to ignore this gesture. */
    fun begin(distance: Float, armed: Boolean = true) {
        start = if (armed && distance >= minStart) distance else 0f
        fired = false
    }

    fun cancel() { start = 0f; fired = false }

    /** Returns true exactly once, the moment the pinch has closed far enough. */
    fun update(distance: Float): Boolean {
        if (start <= 0f || fired) return false
        if (distance / start <= threshold) { fired = true; return true }
        return false
    }
}

/** The landing wall's density: pinch like a photo grid — spread = fewer, bigger tabs. */
object WallDensity {
    const val MIN = 1
    const val MAX = 6
    const val DEFAULT = 3

    /**
     * Columns for a pinch that started at [startCols] and has since scaled the finger span by
     * [zoom] (>1 = fingers apart). The exponent makes a modest pinch cross a step.
     */
    fun colsFor(startCols: Int, zoom: Float): Int {
        if (zoom <= 0f) return startCols.coerceIn(MIN, MAX)
        return Math.round(startCols / zoom.pow(1.15f)).coerceIn(MIN, MAX)
    }

    /** How often a visible tab's thumbnail is refreshed: the denser the wall, the lazier. */
    fun refreshMillis(cols: Int): Long = when {
        cols <= 2 -> 1_500L
        cols <= 4 -> 3_000L
        else -> 6_000L
    }
}

/** Pull directions of the orb: left = back, right = forward, down = reload, up = share. */
enum class PullDir { Left, Right, Down, Up }

/** The orb's pull physics. Distances are in dp so the feel is the same on every screen. */
object OrbPull {
    /** Finger travel (dp) at which the pull arms. */
    const val ARM_DP = 62f
    /** The orb follows the finger at this fraction, up to [MAX_OFFSET_DP]. */
    const val FOLLOW = 0.62f
    const val MAX_OFFSET_DP = 60f
    /** A pull with nothing to do (no forward page) barely moves. */
    const val DISABLED_MAX_DP = 12f
    const val DISABLED_FOLLOW = 0.15f

    fun direction(dx: Float, dy: Float): PullDir =
        if (abs(dx) > abs(dy)) (if (dx < 0f) PullDir.Left else PullDir.Right)
        else (if (dy > 0f) PullDir.Down else PullDir.Up)

    /** How far (dp) the orb is drawn from home for a finger travel of [distanceDp]. */
    fun offset(distanceDp: Float, enabled: Boolean): Float =
        if (enabled) min(MAX_OFFSET_DP, distanceDp * FOLLOW) else min(DISABLED_MAX_DP, distanceDp * DISABLED_FOLLOW)

    fun armed(distanceDp: Float, enabled: Boolean): Boolean = enabled && distanceDp >= ARM_DP
}

/** The orb's distance from the screen edges, in millimetres: 1 cm top/bottom, 2 cm at the side. */
object OrbMargins {
    const val SIDE_MM = 20f
    const val VERTICAL_MM = 10f

    fun mmToPx(mm: Float, dpi: Float): Float = mm / 25.4f * dpi
}
