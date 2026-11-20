package dev.aarso.brwsr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.brwsr.core.Corner
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.aarso.brwsr.core.OrbMargins
import dev.aarso.brwsr.core.OrbPull
import dev.aarso.brwsr.core.PullDir
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val OrbSize = 54.dp
val OrbMargin = 16.dp

/** What the orb's face shows while a pull is armed: the action it will fire on release. */
enum class OrbGlyph(val label: String) {
    Back("back"), Forward("forward"), Reload("reload"), Share("share"), Wall("tabs"), Go("go")
}

/** The orb's distance from the screen edges: 1 cm top/bottom, 2 cm at the side by default. */
data class OrbInsets(val side: Dp, val vertical: Dp)

/** Millimetres → dp using the screen's real pixel density, so "1 cm" is 1 cm on the glass. */
@Composable
fun rememberOrbInsets(sideMm: Float, verticalMm: Float): OrbInsets {
    val dm = LocalContext.current.resources.displayMetrics
    return remember(sideMm, verticalMm, dm.xdpi, dm.ydpi, dm.density) {
        OrbInsets(
            (OrbMargins.mmToPx(sideMm, dm.xdpi) / dm.density).dp,
            (OrbMargins.mmToPx(verticalMm, dm.ydpi) / dm.density).dp,
        )
    }
}

@Composable
private fun GlyphIcon(glyph: OrbGlyph, tint: Color) {
    when (glyph) {
        OrbGlyph.Back -> Icon(Icons.Filled.ArrowBack, null, tint = tint, modifier = Modifier.size(20.dp))
        OrbGlyph.Forward, OrbGlyph.Go -> Icon(Icons.Filled.ArrowForward, null, tint = tint, modifier = Modifier.size(20.dp))
        OrbGlyph.Reload -> Icon(Icons.Filled.Refresh, null, tint = tint, modifier = Modifier.size(20.dp))
        OrbGlyph.Share -> Icon(Icons.Filled.Share, null, tint = tint, modifier = Modifier.size(19.dp))
        OrbGlyph.Wall -> Canvas(Modifier.size(18.dp)) {
            val s = size.width
            val c = s * .42f
            for (x in listOf(0f, s - c)) for (y in listOf(0f, s - c)) {
                drawRoundRect(tint, Offset(x, y), Size(c, c), CornerRadius(s * .1f))
            }
        }
    }
}

/**
 * The orb's face: the site's icon on a lit disc, a progress arc on the rim while loading.
 * While a pull is [armed] the inner disc turns violet and shows the action's [glyph].
 */
@Composable
fun OrbFace(
    icon: ImageBitmap?, letter: String, progress: Int, insecure: Boolean,
    modifier: Modifier = Modifier, glyph: OrbGlyph? = null, armed: Boolean = false,
) {
    Box(
        modifier
            .size(OrbSize)
            .shadow(10.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Ink.SurfaceHigh, Ink.Surface)))
            .border(1.dp, if (insecure) Ink.Warning.copy(alpha = .7f) else Ink.Rim, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (armed) Brush.radialGradient(listOf(Ink.AccentBright, Ink.Accent))
                    else Brush.radialGradient(listOf(Color(0xFFF1EEEA), Ink.Light)),
                )
                .border(3.dp, Ink.Groove, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (glyph != null) {
                GlyphIcon(glyph, Ink.Near)
            } else if (icon != null) {
                Image(icon, null, Modifier.size(20.dp).clip(CircleShape))
            } else if (letter.isNotEmpty()) {
                Text(letter, color = Ink.Near, fontSize = 14.sp)
            }
        }
        if (progress in 1..99) {
            Canvas(Modifier.size(OrbSize)) {
                val sw = 2.5.dp.toPx()
                drawArc(
                    Ink.Accent, startAngle = -90f, sweepAngle = 360f * progress / 100f, useCenter = false,
                    topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
                    style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** Faint chevrons around a pressed orb showing the four pulls; the live one brightens. */
@Composable
private fun PullGhosts(active: PullDir?, enabled: (PullDir) -> Boolean) {
    Canvas(Modifier.requiredSize(OrbSize + 150.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val reach = (OrbSize / 2 + 26.dp).toPx()
        val arm = 5.dp.toPx()
        PullDir.entries.forEach { d ->
            val on = enabled(d)
            val color = when {
                !on -> Color.White.copy(alpha = .12f)
                d == active -> Ink.AccentBright
                else -> Color.White.copy(alpha = .38f)
            }
            val (ux, uy) = when (d) { PullDir.Left -> -1f to 0f; PullDir.Right -> 1f to 0f; PullDir.Up -> 0f to -1f; PullDir.Down -> 0f to 1f }
            val tip = Offset(c.x + ux * reach, c.y + uy * reach)
            // A chevron pointing outward: two strokes back from the tip.
            val p = Path().apply {
                moveTo(tip.x - ux * arm - uy * arm, tip.y - uy * arm - ux * arm)
                lineTo(tip.x, tip.y)
                lineTo(tip.x - ux * arm + uy * arm, tip.y - uy * arm + ux * arm)
            }
            drawPath(p, color, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * The orb, collapsed in its corner.
 *
 * - **Tap** opens the pill.
 * - **Pull** it left / right / down / up for back / forward / reload / share. It follows the
 *   finger with resistance; past the arm distance it turns violet and shows the action, which
 *   fires on release. A pull that has nothing to do (no page forward) barely moves.
 * - **Long-press, then drag** lifts it; it settles into the nearest corner and remembers it.
 */
@Composable
fun Orb(
    corner: Corner,
    insets: OrbInsets,
    icon: ImageBitmap?,
    letter: String,
    progress: Int,
    insecure: Boolean,
    pullEnabled: (PullDir) -> Boolean,
    glyphFor: (PullDir) -> OrbGlyph,
    onCornerChange: (Corner) -> Unit,
    onTap: () -> Unit,
    onPull: (PullDir) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val shift = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    var pressed by remember { mutableStateOf(false) }
    var lifted by remember { mutableStateOf(false) }
    var pull by remember { mutableStateOf<PullDir?>(null) }
    var armed by remember { mutableStateOf(false) }
    val enabledNow by rememberUpdatedState(pullEnabled)
    val glyphNow by rememberUpdatedState(glyphFor)
    val tapNow by rememberUpdatedState(onTap)
    val pullNow by rememberUpdatedState(onPull)
    val cornerNow by rememberUpdatedState(onCornerChange)

    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = insets.side, vertical = insets.vertical),
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val orbPx = with(LocalDensity.current) { OrbSize.toPx() }
        val align = when (corner) {
            Corner.TopLeft -> Alignment.TopStart
            Corner.TopRight -> Alignment.TopEnd
            Corner.BottomLeft -> Alignment.BottomStart
            Corner.BottomRight -> Alignment.BottomEnd
        }
        Box(
            Modifier
                .align(align)
                .size(OrbSize)
                .offset { IntOffset(shift.value.x.roundToInt(), shift.value.y.roundToInt()) }
                .pointerInput(corner, w, h) {
                    val longPress = viewConfiguration.longPressTimeoutMillis
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        var total = Offset.Zero
                        // 3 = lifted finger (tap), 1 = moved past slop (pull), null = held (lift).
                        val first = withTimeoutOrNull(longPress) {
                            var result = 0
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (c.changedToUpIgnoreConsumed()) { result = 3; break }
                                total += c.positionChange()
                                if (total.getDistance() > slop) { result = 1; break }
                            }
                            result
                        }
                        when (first) {
                            3 -> {
                                pressed = false
                                tapNow()
                            }
                            null -> {
                                // Lifted: follow the finger anywhere, then settle into a corner.
                                lifted = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                var offset = Offset.Zero
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (c.changedToUpIgnoreConsumed()) break
                                    offset += c.positionChange()
                                    c.consume()
                                    val o = offset
                                    scope.launch { shift.snapTo(o) }
                                }
                                lifted = false
                                pressed = false
                                val baseX = if (corner.isRight) w - orbPx else 0f
                                val baseY = if (corner.isBottom) h - orbPx else 0f
                                val next = Corner.nearest(baseX + offset.x + orbPx / 2, baseY + offset.y + orbPx / 2, w, h)
                                scope.launch {
                                    if (next != corner) {
                                        // Re-anchor at the new corner, keeping the orb where the finger left it.
                                        val nbx = if (next.isRight) w - orbPx else 0f
                                        val nby = if (next.isBottom) h - orbPx else 0f
                                        shift.snapTo(Offset(baseX + offset.x - nbx, baseY + offset.y - nby))
                                        cornerNow(next)
                                    }
                                    shift.animateTo(Offset.Zero, tween(320))
                                }
                            }
                            else -> {
                                var dir = OrbPull.direction(total.x, total.y)
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (c.changedToUpIgnoreConsumed()) break
                                    total += c.positionChange()
                                    c.consume()
                                    dir = OrbPull.direction(total.x, total.y)
                                    val distDp = maxOf(abs(total.x), abs(total.y)) / density
                                    val enabled = enabledNow(dir)
                                    val offPx = OrbPull.offset(distDp, enabled) * density
                                    val target = when (dir) {
                                        PullDir.Left -> Offset(-offPx, 0f)
                                        PullDir.Right -> Offset(offPx, 0f)
                                        PullDir.Up -> Offset(0f, -offPx)
                                        PullDir.Down -> Offset(0f, offPx)
                                    }
                                    scope.launch { shift.snapTo(target) }
                                    val nowArmed = OrbPull.armed(distDp, enabled)
                                    if (nowArmed != armed) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        armed = nowArmed
                                    }
                                    pull = dir
                                }
                                val fire = armed
                                pressed = false
                                armed = false
                                pull = null
                                scope.launch { shift.animateTo(Offset.Zero, spring(dampingRatio = .55f, stiffness = 420f)) }
                                if (fire) pullNow(dir)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (pressed && !lifted) PullGhosts(pull) { enabledNow(it) }
            OrbFace(
                icon, letter, progress, insecure,
                modifier = Modifier.graphicsLayer { if (lifted) { scaleX = 1.14f; scaleY = 1.14f } },
                glyph = if (armed) pull?.let { glyphNow(it) } else null,
                armed = armed,
            )
            val d = pull
            if (armed && d != null) {
                val (dx, dy) = when (d) { PullDir.Left -> -96 to 0; PullDir.Right -> 96 to 0; PullDir.Up -> 0 to -82; PullDir.Down -> 0 to 82 }
                Text(
                    glyphNow(d).label.uppercase(), color = Ink.AccentBright, fontSize = 10.sp, fontFamily = Ink.Mono, letterSpacing = 1.sp,
                    modifier = Modifier.requiredSize(width = 64.dp, height = 24.dp).offset(dx.dp, dy.dp)
                        .clip(CircleShape).background(Ink.Near.copy(alpha = .9f)).padding(top = 6.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The expanded pill. The orb's side becomes Go; the far side is the site menu. Behind it, a
 * black gradient from fully opaque at the pill's edge to clear.
 */
@Composable
fun Pill(
    corner: Corner,
    insets: OrbInsets,
    initialText: String,
    suggestions: (String) -> List<Pair<String, String>>,
    menuOpen: Boolean,
    onGo: (String) -> Unit,
    onMenu: () -> Unit,
    onDismiss: () -> Unit,
    menu: @Composable () -> Unit,
) {
    var field by remember { mutableStateOf(TextFieldValue(initialText, TextRange(0, initialText.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(menuOpen, shown) {
        if (menuOpen) { keyboard?.hide(); return@LaunchedEffect }
        if (!shown) return@LaunchedEffect
        // The field animates in; give it a frame to attach before asking for the keyboard.
        delay(60)
        runCatching { focus.requestFocus(); keyboard?.show() }
    }

    val bottom = corner.isBottom
    Box(Modifier.fillMaxSize()) {
        // Scrim: 100% black at the pill's edge fading to 0.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        if (bottom) listOf(Color.Transparent, Color.Black.copy(alpha = .55f), Color.Black)
                        else listOf(Color.Black, Color.Black.copy(alpha = .55f), Color.Transparent),
                    ),
                )
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        Column(
            Modifier
                .align(if (bottom) Alignment.BottomCenter else Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(
                    start = if (corner.isRight) 16.dp else insets.side,
                    end = if (corner.isRight) insets.side else 16.dp,
                    top = insets.vertical, bottom = insets.vertical,
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val list = if (menuOpen) emptyList() else suggestions(field.text)
            val above: @Composable () -> Unit = {
                if (menuOpen) menu()
                else if (list.isNotEmpty()) Suggestions(list) { url -> onGo(url) }
            }
            if (bottom) above()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val go: @Composable () -> Unit = {
                    RoundButton(onClick = { onGo(field.text) }, filled = true) {
                        Icon(Icons.Filled.ArrowForward, null, tint = Ink.Near)
                    }
                }
                val hamburger: @Composable () -> Unit = {
                    RoundButton(onClick = onMenu) {
                        Icon(if (menuOpen) Icons.Filled.Close else Icons.Filled.MoreVert, null, tint = Ink.Text)
                    }
                }
                if (corner.isRight) hamburger() else go()
                AnimatedVisibility(
                    visible = shown,
                    modifier = Modifier.weight(1f),
                    enter = expandHorizontally(tween(320), expandFrom = if (corner.isRight) Alignment.End else Alignment.Start) + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut(),
                ) {
                    Row(
                        Modifier
                            .height(OrbSize)
                            .clip(CircleShape)
                            .background(Ink.Surface)
                            .border(1.dp, Ink.Rim, CircleShape)
                            .padding(start = 20.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = field,
                            onValueChange = { field = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Ink.Text, fontSize = 16.sp),
                            cursorBrush = SolidColor(Ink.Accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onGo = { onGo(field.text) }),
                            modifier = Modifier.weight(1f).focusRequester(focus),
                        )
                        if (field.text.isNotEmpty()) {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable { field = TextFieldValue("") },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Clear, null, tint = Ink.TextDim, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
                if (corner.isRight) go() else hamburger()
            }
            if (!bottom) above()
        }
    }
}

@Composable
fun RoundButton(onClick: () -> Unit, filled: Boolean = false, size: androidx.compose.ui.unit.Dp = OrbSize, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) Brush.radialGradient(listOf(Color(0xFFF1EEEA), Ink.Light)) else Brush.radialGradient(listOf(Ink.SurfaceHigh, Ink.Surface)))
            .border(1.dp, Ink.Rim, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Suggestions(items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        items.forEach { (title, url) ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(url) }.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = Ink.Text, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(dev.aarso.brwsr.core.UrlInput.pretty(url), color = Ink.TextDim, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}
