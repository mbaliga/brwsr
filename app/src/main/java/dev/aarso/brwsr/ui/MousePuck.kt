package dev.aarso.brwsr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import dev.aarso.brwsr.core.Joystick
import dev.aarso.brwsr.core.PuckLayout
import dev.aarso.brwsr.core.PuckZone
import dev.aarso.brwsr.core.Trackball
import dev.aarso.brwsr.data.Hand
import dev.aarso.brwsr.data.PuckMode
import dev.aarso.brwsr.data.Settings
import dev.aarso.brwsr.web.MouseInjector
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed class Role {
    class Stick(val start: Offset, val startTime: Long) : Role() { var travelled = 0f }
    class Click(val secondary: Boolean, val zone: PuckZone) : Role()
    class Wheel(val zone: PuckZone) : Role() { var accY = 0f; var accX = 0f }
    class Handle(val startTime: Long) : Role() { var travelled = 0f; var pos = Offset.Zero }
    class Pinch(val handleId: PointerId, val startDist: Float, val startSize: Float) : Role() { var pos = Offset.Zero }
}

/**
 * Desktop mode's mouse: a cursor over the page and a puck to drive it.
 *
 *  - centre: joystick (push to move, speed grows with the push) or trackball (roll, and it
 *    keeps rolling); a quick tap on it is a click
 *  - left/right segments: the two mouse buttons — primary on the left for right hands,
 *    mirrored for left hands. Hold the primary and steer to drag.
 *  - top segment: scroll wheel (drag along it)
 *  - bottom segment (☰): drag to move the puck, tap for its settings; pinch with a second
 *    finger to resize
 */
@Composable
fun PuckLayer(settings: Settings, mouse: MouseInjector, onHide: () -> Unit) {
    val density = LocalDensity.current
    var area by remember { mutableStateOf(IntSize.Zero) }
    var cursor by remember { mutableStateOf(Offset.Unspecified) }
    var knob by remember { mutableStateOf(Offset.Zero) }
    var ballPhase by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(setOf<PuckZone>()) }
    var menu by remember { mutableStateOf(false) }
    val sizePx = with(density) { settings.puckSizeDp.dp.toPx() }
    val margin = with(density) { 20.dp.toPx() }
    var puck by remember { mutableStateOf(Offset.Unspecified) }
    val scope = rememberCoroutineScope()
    var stickJob by remember { mutableStateOf<Job?>(null) }
    var momentumJob by remember { mutableStateOf<Job?>(null) }
    var vx by remember { mutableFloatStateOf(0f) }
    var vy by remember { mutableFloatStateOf(0f) }
    val tracker = remember { VelocityTracker() }

    fun clampCursor(p: Offset) = Offset(
        p.x.coerceIn(0f, (area.width - 1).toFloat().coerceAtLeast(0f)),
        p.y.coerceIn(0f, (area.height - 1).toFloat().coerceAtLeast(0f)),
    )
    fun moveCursor(by: Offset) {
        if (cursor == Offset.Unspecified) return
        cursor = clampCursor(cursor + by)
        mouse.hover(cursor.x, cursor.y)
    }
    fun clampPuck(p: Offset) = Offset(
        p.x.coerceIn(0f, (area.width - sizePx).coerceAtLeast(0f)),
        p.y.coerceIn(0f, (area.height - sizePx).coerceAtLeast(0f)),
    )

    LaunchedEffect(area, settings.hand) {
        if (area == IntSize.Zero) return@LaunchedEffect
        if (cursor == Offset.Unspecified) cursor = Offset(area.width / 2f, area.height / 3f)
        puck = if (settings.puckX in 0f..1f && settings.puckY in 0f..1f) {
            clampPuck(Offset(settings.puckX * area.width, settings.puckY * area.height))
        } else {
            val x = if (settings.hand == Hand.Right) area.width - sizePx - margin else margin
            clampPuck(Offset(x, area.height - sizePx - margin * 4))
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { area = it }) {
        // The cursor.
        if (cursor != Offset.Unspecified) {
            Canvas(Modifier.fillMaxSize()) { drawCursor(cursor) }
        }
        if (puck == Offset.Unspecified) return@Box
        val maxSpeed = with(density) { 900.dp.toPx() } * settings.puckSpeed
        val wheelUnit = with(density) { 22.dp.toPx() }

        Box(
            Modifier
                .offset { IntOffset(puck.x.roundToInt(), puck.y.roundToInt()) }
                .size(with(density) { sizePx.toDp() })
                .shadow(16.dp, CircleShape)
                .pointerInput(sizePx, settings.hand, settings.puckMode, maxSpeed) {
                    awaitEachGesture {
                        val roles = HashMap<PointerId, Role>()
                        val r = sizePx / 2f
                        val center = Offset(r, r)
                        do {
                            val ev = awaitPointerEvent()
                            for (c in ev.changes) {
                                when {
                                    c.changedToDown() -> {
                                        val handle = roles.entries.firstOrNull { it.value is Role.Handle }
                                        val zone = PuckLayout.zoneAt(c.position.x, c.position.y, sizePx)
                                        val role: Role? = when {
                                            handle != null -> Role.Pinch(handle.key, (c.position - (handle.value as Role.Handle).pos).getDistance(), settings.puckSizeDp)
                                            zone == PuckZone.Center -> {
                                                momentumJob?.cancel(); vx = 0f; vy = 0f
                                                tracker.resetTracking()
                                                Role.Stick(c.position, c.uptimeMillis).also {
                                                    if (settings.puckMode == PuckMode.Joystick) {
                                                        stickJob?.cancel()
                                                        stickJob = scope.launch {
                                                            var last = 0L
                                                            while (isActive) withFrameNanos { t ->
                                                                if (last != 0L) {
                                                                    val dt = (t - last) / 1e9f
                                                                    val (sx, sy) = Joystick.velocity(knob.x, knob.y, r * PuckLayout.INNER * .6f, maxSpeed)
                                                                    if (sx != 0f || sy != 0f) moveCursor(Offset(sx * dt, sy * dt))
                                                                }
                                                                last = t
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            zone == PuckZone.Left || zone == PuckZone.Right -> {
                                                val primaryLeft = settings.hand == Hand.Right
                                                val secondary = (zone == PuckZone.Left) != primaryLeft
                                                if (cursor != Offset.Unspecified) mouse.press(cursor.x, cursor.y, secondary)
                                                Role.Click(secondary, zone)
                                            }
                                            zone == PuckZone.Top -> Role.Wheel(zone)
                                            zone == PuckZone.Bottom -> Role.Handle(c.uptimeMillis).also { it.pos = c.position }
                                            else -> null
                                        }
                                        if (role != null) {
                                            roles[c.id] = role
                                            pressed = pressed + zone
                                            c.consume()
                                        }
                                    }
                                    c.changedToUp() -> {
                                        when (val role = roles.remove(c.id)) {
                                            is Role.Stick -> {
                                                stickJob?.cancel(); stickJob = null
                                                knob = Offset.Zero
                                                val quick = c.uptimeMillis - role.startTime < 220 && role.travelled < 12f
                                                if (quick && cursor != Offset.Unspecified) {
                                                    mouse.click(cursor.x, cursor.y, secondary = false)
                                                } else if (settings.puckMode == PuckMode.Trackball) {
                                                    val v = tracker.calculateVelocity()
                                                    vx = v.x * Trackball.GAIN * settings.puckSpeed
                                                    vy = v.y * Trackball.GAIN * settings.puckSpeed
                                                    momentumJob = scope.launch {
                                                        var last = 0L
                                                        while (isActive && (vx != 0f || vy != 0f)) withFrameNanos { t ->
                                                            if (last != 0L) {
                                                                val dt = (t - last) / 1e9f
                                                                moveCursor(Offset(vx * dt, vy * dt))
                                                                ballPhase += Offset(vx * dt, vy * dt)
                                                                vx = Trackball.decay(vx, dt); vy = Trackball.decay(vy, dt)
                                                            }
                                                            last = t
                                                        }
                                                    }
                                                }
                                                pressed = pressed - PuckZone.Center
                                            }
                                            is Role.Click -> {
                                                if (cursor != Offset.Unspecified) mouse.release(cursor.x, cursor.y)
                                                pressed = pressed - role.zone
                                            }
                                            is Role.Wheel -> pressed = pressed - role.zone
                                            is Role.Handle -> {
                                                pressed = pressed - PuckZone.Bottom
                                                if (role.travelled < 12f && c.uptimeMillis - role.startTime < 300) menu = !menu
                                                else if (area.width > 0) {
                                                    settings.puckX = puck.x / area.width
                                                    settings.puckY = puck.y / area.height
                                                }
                                            }
                                            is Role.Pinch, null -> Unit
                                        }
                                        c.consume()
                                    }
                                    c.pressed -> {
                                        val d = c.positionChange()
                                        when (val role = roles[c.id]) {
                                            is Role.Stick -> {
                                                role.travelled += d.getDistance()
                                                if (settings.puckMode == PuckMode.Joystick) {
                                                    val (kx, ky) = Joystick.clamp(c.position.x - center.x, c.position.y - center.y, r * PuckLayout.INNER * .6f)
                                                    knob = Offset(kx, ky)
                                                } else {
                                                    tracker.addPosition(c.uptimeMillis, c.position)
                                                    moveCursor(d * Trackball.GAIN * settings.puckSpeed)
                                                    ballPhase += d
                                                }
                                            }
                                            is Role.Wheel -> {
                                                role.accY += d.y; role.accX += d.x
                                                val ny = role.accY / wheelUnit
                                                val nx = role.accX / wheelUnit
                                                if (cursor != Offset.Unspecified && (kotlin.math.abs(ny) >= .25f || kotlin.math.abs(nx) >= .25f)) {
                                                    mouse.scroll(cursor.x, cursor.y, nx, -ny)
                                                    role.accY = 0f; role.accX = 0f
                                                }
                                            }
                                            is Role.Handle -> {
                                                role.travelled += d.getDistance()
                                                role.pos = c.position
                                                if (role.travelled >= 12f && roles.values.none { it is Role.Pinch }) puck = clampPuck(puck + d)
                                            }
                                            is Role.Pinch -> {
                                                val h = roles[role.handleId] as? Role.Handle
                                                if (h != null && role.startDist > 0f) {
                                                    val dist = (c.position - h.pos).getDistance()
                                                    settings.puckSizeDp = (role.startSize * dist / role.startDist).coerceIn(88f, 260f)
                                                }
                                            }
                                            is Role.Click, null -> Unit
                                        }
                                        c.consume()
                                    }
                                }
                            }
                        } while (ev.changes.any { it.pressed })
                        // Lifted everything: make sure nothing stays latched.
                        stickJob?.cancel(); knob = Offset.Zero
                        pressed = emptySet()
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawPuck(knob, ballPhase, pressed, primaryLeft = settings.hand == Hand.Right, trackball = settings.puckMode == PuckMode.Trackball)
            }
        }

        if (menu) {
            val menuW = 260.dp
            val menuWPx = with(density) { menuW.toPx() }
            val x = if (puck.x + sizePx / 2 > area.width / 2) (puck.x - menuWPx - 12f) else (puck.x + sizePx + 12f)
            Panel(
                Modifier
                    .offset { IntOffset(x.coerceIn(8f, (area.width - menuWPx - 8f).coerceAtLeast(8f)).roundToInt(), (puck.y - 40f).coerceAtLeast(8f).roundToInt()) }
                    .width(menuW),
            ) {
                SectionLabel("Hand")
                Segmented(Hand.entries, settings.hand, { it.name }, { settings.hand = it; settings.puckX = -1f })
                SectionLabel("Centre")
                Segmented(PuckMode.entries, settings.puckMode, { it.label }, { settings.puckMode = it })
                SectionLabel("Size")
                PuckSlider(settings.puckSizeDp, 88f..260f) { settings.puckSizeDp = it }
                SectionLabel("Speed")
                PuckSlider(settings.puckSpeed, .3f..2.5f) { settings.puckSpeed = it }
                MenuRow("Hide puck", onClick = { menu = false; onHide() })
            }
        }
    }
}

@Composable
private fun PuckSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp)) {
        Slider(
            value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Ink.Light, activeTrackColor = Ink.Accent, inactiveTrackColor = Ink.Groove),
        )
    }
}

/** The puck, after the mock: dark top/bottom, light left/right, a black ring around a lit stick. */
private fun DrawScope.drawPuck(knob: Offset, ballPhase: Offset, pressed: Set<PuckZone>, primaryLeft: Boolean, trackball: Boolean) {
    val r = size.minDimension / 2f
    val c = center
    val dark = Color(0xFF2B2B2E)
    val light = Color(0xFFD9D9D9)
    fun shade(base: Color, zone: PuckZone) = if (zone in pressed) lerp(base, Color(0xFF8E7BFF), .35f) else base

    // Left/right (light) and top/bottom (dark) segments, split on the diagonals.
    drawArc(shade(light, PuckZone.Right), -45f, 90f, useCenter = true, topLeft = Offset.Zero, size = size)
    drawArc(shade(dark, PuckZone.Bottom), 45f, 90f, useCenter = true, topLeft = Offset.Zero, size = size)
    drawArc(shade(light, PuckZone.Left), 135f, 90f, useCenter = true, topLeft = Offset.Zero, size = size)
    drawArc(shade(dark, PuckZone.Top), 225f, 90f, useCenter = true, topLeft = Offset.Zero, size = size)
    drawCircle(Color(0x33FFFFFF), r - 1f, c, style = Stroke(1.5f))

    // Primary button mark: a small accent dot near the rim (handedness, without a word).
    val px = if (primaryLeft) c.x - r * .8f else c.x + r * .8f
    drawCircle(Color(0xFF8E7BFF), r * .045f, Offset(px, c.y))

    // Scroll-wheel ridges on the top segment.
    for (i in -1..1) {
        val y = c.y - r * .78f + i * r * .07f
        drawLine(Color(0x66FFFFFF), Offset(c.x - r * .12f, y), Offset(c.x + r * .12f, y), strokeWidth = r * .025f, cap = StrokeCap.Round)
    }
    // Hamburger on the bottom segment: the handle.
    for (i in -1..1) {
        val y = c.y + r * .8f + i * r * .085f
        drawLine(Color(0xFFECE8E4), Offset(c.x - r * .15f, y), Offset(c.x + r * .15f, y), strokeWidth = r * .035f, cap = StrokeCap.Round)
    }

    // Centre: black ring, lit disc that moves with the stick (or rolls, as a trackball).
    val ring = r * PuckLayout.INNER
    drawCircle(Brush.radialGradient(listOf(Color(0xFF1A1A1E), Color(0xFF000000)), c, ring), ring, c)
    drawCircle(Color(0x40FFFFFF), ring, c, style = Stroke(1.5f))
    val disc = ring * .66f
    val dc = c + knob
    drawCircle(Color(0xFF000000).copy(alpha = .5f), disc * 1.04f, dc + Offset(0f, disc * .06f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFF4F2EF), light, Color(0xFFBDBDBD)), dc - Offset(disc * .3f, disc * .3f), disc * 1.6f), disc, dc)
    if (trackball) {
        // Speckles that slide with the roll so the ball reads as turning.
        val step = disc * .7f
        for (gx in -2..2) for (gy in -2..2) {
            val ox = ((ballPhase.x * .35f) % step + step) % step
            val oy = ((ballPhase.y * .35f) % step + step) % step
            val p = Offset(dc.x + gx * step + ox - step / 2, dc.y + gy * step + oy - step / 2)
            if ((p - dc).getDistance() < disc * .85f) drawCircle(Color(0x22000000), disc * .08f, p)
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha,
)

/** A classic arrow pointer, white with a dark outline, tip at [at]. */
private fun DrawScope.drawCursor(at: Offset) {
    val s = 1.15f * density
    val pts = listOf(0f to 0f, 0f to 17f, 4.2f to 13f, 7.2f to 19.6f, 9.6f to 18.6f, 6.6f to 12.2f, 12f to 12.2f)
    val path = Path().apply {
        moveTo(at.x, at.y)
        pts.drop(1).forEach { (x, y) -> lineTo(at.x + x * s, at.y + y * s) }
        close()
    }
    drawPath(path, Color.White)
    drawPath(path, Color.Black, style = Stroke(1.4f * density, join = StrokeJoin.Round))
}
