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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

val OrbSize = 54.dp
val OrbMargin = 16.dp

/** The orb's face: the site's icon on a lit disc, a progress arc on the rim while loading. */
@Composable
fun OrbFace(icon: ImageBitmap?, letter: String, progress: Int, insecure: Boolean, modifier: Modifier = Modifier) {
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
                .background(Brush.radialGradient(listOf(Color(0xFFF1EEEA), Ink.Light)))
                .border(3.dp, Ink.Groove, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
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

/**
 * The orb, collapsed in its corner. Drag it anywhere; it settles into the nearest corner and
 * remembers it. Tap to expand into the pill.
 */
@Composable
fun Orb(
    corner: Corner,
    icon: ImageBitmap?,
    letter: String,
    progress: Int,
    insecure: Boolean,
    onCornerChange: (Corner) -> Unit,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(OrbMargin)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val orbPx = with(LocalDensity.current) { OrbSize.toPx() }
        val align = when (corner) {
            Corner.TopLeft -> Alignment.TopStart
            Corner.TopRight -> Alignment.TopEnd
            Corner.BottomLeft -> Alignment.BottomStart
            Corner.BottomRight -> Alignment.BottomEnd
        }
        OrbFace(
            icon, letter, progress, insecure,
            Modifier
                .align(align)
                .offset { IntOffset(drag.value.x.roundToInt(), drag.value.y.roundToInt()) }
                .pointerInput(corner) {
                    detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
                }
                .pointerInput(corner, w, h) {
                    detectDragGestures(
                        onDrag = { change, d ->
                            change.consume()
                            scope.launch { drag.snapTo(drag.value + d) }
                        },
                        onDragEnd = {
                            val baseX = if (corner.isRight) w - orbPx else 0f
                            val baseY = if (corner.isBottom) h - orbPx else 0f
                            val cx = baseX + drag.value.x + orbPx / 2
                            val cy = baseY + drag.value.y + orbPx / 2
                            val next = Corner.nearest(cx, cy, w, h)
                            scope.launch {
                                if (next != corner) {
                                    // Re-anchor at the new corner, keeping the orb where the finger left it,
                                    // then settle into place.
                                    val nbx = if (next.isRight) w - orbPx else 0f
                                    val nby = if (next.isBottom) h - orbPx else 0f
                                    drag.snapTo(Offset(baseX + drag.value.x - nbx, baseY + drag.value.y - nby))
                                    onCornerChange(next)
                                }
                                drag.animateTo(Offset.Zero, tween(320))
                            }
                        },
                        onDragCancel = { scope.launch { drag.animateTo(Offset.Zero, tween(320)) } },
                    )
                },
        )
    }
}

/**
 * The expanded pill. The orb's side becomes Go; the far side is the site menu. Behind it, a
 * black gradient from fully opaque at the pill's edge to clear.
 */
@Composable
fun Pill(
    corner: Corner,
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
                .padding(OrbMargin),
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
                        Icon(if (menuOpen) Icons.Filled.Close else Icons.Filled.Menu, null, tint = Ink.Text)
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
