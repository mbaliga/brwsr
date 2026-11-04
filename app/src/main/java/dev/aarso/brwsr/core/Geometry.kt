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
