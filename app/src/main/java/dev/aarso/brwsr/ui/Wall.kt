package dev.aarso.brwsr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.aarso.brwsr.core.Wall
import dev.aarso.brwsr.core.WallStyle
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class WallItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val thumb: ImageBitmap?,
    val icon: ImageBitmap?,
    val pinned: Boolean = false,
    val highlighted: Boolean = false,
)

/** Long-press actions on a card. Empty = none. */
data class CardAction(val label: String, val run: () -> Unit)

/**
 * The landing: Top Sites as a wall of page thumbnails, in the user's chosen arrangement.
 * Also reused for the tab switcher ([onClose] non-null shows a close mark on each card).
 */
@Composable
fun WallView(
    items: List<WallItem>,
    style: WallStyle,
    onOpen: (WallItem) -> Unit,
    actions: (WallItem) -> List<CardAction>,
    modifier: Modifier = Modifier,
    onClose: ((WallItem) -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF050506), Color(0xFF0C0C10), Color(0xFF020203))))
            .drawBehind {
                // A soft spotlight on the wall, and a faint glow where the floor would be.
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x1F8E7BFF), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * .38f), radius = size.width * 1.1f,
                    ),
                )
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x14FFFFFF)),
                        startY = size.height * .78f, endY = size.height,
                    ),
                )
            },
    ) {
        when (style) {
            WallStyle.Curved, WallStyle.Flat -> GridWall(items, curved = style == WallStyle.Curved, onOpen, actions, onClose)
            WallStyle.Coverflow -> FlowWall(items, onOpen, actions, onClose, ring = false)
            WallStyle.Carousel -> FlowWall(items, onOpen, actions, onClose, ring = true)
        }
        if (onAdd != null) {
            Box(
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 12.dp),
            ) {
                RoundButton(onClick = onAdd, size = 44.dp) { Icon(Icons.Filled.Add, null, tint = Ink.Text) }
            }
        }
    }
}

@Composable
private fun GridWall(
    items: List<WallItem>,
    curved: Boolean,
    onOpen: (WallItem) -> Unit,
    actions: (WallItem) -> List<CardAction>,
    onClose: ((WallItem) -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = when {
            maxWidth < 420.dp -> 2
            maxWidth < 720.dp -> 3
            else -> 4
        }.coerceAtMost(maxOf(items.size, 1))
        val side = 20.dp
        val gap = if (curved) 4.dp else 14.dp
        val rowGap = 22.dp
        val labelH = 28.dp
        // Centre the wall vertically when it is shorter than the screen (the common case).
        val cardW = (maxWidth - side * 2 - gap * (cols - 1)) / cols
        val rowH = cardW / dev.aarso.brwsr.web.Browser.CARD_ASPECT + labelH
        val rows = (items.size + cols - 1) / cols
        val content = rowH * rows + rowGap * (rows - 1).coerceAtLeast(0)
        val top = ((maxHeight - content) / 2 - 24.dp).coerceAtLeast(76.dp)
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(start = side, end = side, top = top, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(rowGap),
        ) {
            itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                val col = i % cols
                val mid = (cols - 1) / 2f
                val edge = if (cols <= 1) 0f else kotlin.math.abs(col - mid) / mid // 0 centre … 1 outer
                Column(
                    Modifier.graphicsLayer {
                        if (curved) {
                            rotationY = Wall.curvedYaw(col, cols, maxDeg = 22f)
                            cameraDistance = 12f * density
                            // Pivot on the inner edge so the outer edges swing toward the viewer,
                            // and shrink the outer columns a touch so the wall recedes at its ends.
                            transformOrigin = TransformOrigin(if (col < mid) 1f else if (col > mid) 0f else .5f, .5f)
                            val s = 1f - .06f * edge
                            scaleX = s; scaleY = s
                        }
                    },
                ) {
                    Card(item, onOpen, actions, onClose, Modifier.fillMaxWidth())
                    Text(
                        item.title, color = Ink.Text.copy(alpha = .78f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 9.dp, start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Card(
    item: WallItem,
    onOpen: (WallItem) -> Unit,
    actions: (WallItem) -> List<CardAction>,
    onClose: ((WallItem) -> Unit)?,
    modifier: Modifier = Modifier,
    reflect: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Box(modifier) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(dev.aarso.brwsr.web.Browser.CARD_ASPECT)
                    .clip(shape)
                    .background(Ink.Raised)
                    .border(
                        if (item.highlighted) 2.dp else 1.dp,
                        if (item.highlighted) SolidColor(Ink.Accent)
                        else Brush.verticalGradient(listOf(Color(0x38FFFFFF), Color(0x0DFFFFFF))),
                        shape,
                    )
                    .combinedClickable(onClick = { onOpen(item) }, onLongClick = { if (actions(item).isNotEmpty()) menu = true }),
            ) { CardFace(item) }
            if (reflect) {
                // A floor reflection: the card mirrored and fading into the dark.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(dev.aarso.brwsr.web.Browser.CARD_ASPECT * 2.5f)
                        .clip(RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp))
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Transparent)),
                                blendMode = BlendMode.DstIn,
                            )
                        },
                ) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(dev.aarso.brwsr.web.Browser.CARD_ASPECT)
                            .graphicsLayer { scaleY = -1f; transformOrigin = TransformOrigin(.5f, 0f); translationY = size.height }
                            .clip(shape),
                    ) { CardFace(item) }
                }
            }
        }
        if (onClose != null) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp).size(26.dp).clip(CircleShape)
                    .background(Ink.SurfaceHigh).border(1.dp, Ink.Rim, CircleShape).clickable { onClose(item) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, null, tint = Ink.Text, modifier = Modifier.size(14.dp)) }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            actions(item).forEach { a ->
                DropdownMenuItem(text = { Text(a.label) }, onClick = { menu = false; a.run() })
            }
        }
    }
}

@Composable
private fun CardFace(item: WallItem) {
    if (item.thumb != null) {
        Image(item.thumb, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
        return
    }
    // No capture yet: a tinted card (a stable colour per site) with a page skeleton and the
    // site's icon or initial, so an empty wall still looks like a wall of pages.
    val hue = ((item.key.hashCode() and 0x7fffffff) % 360).toFloat()
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.hsv(hue, .38f, .34f), Color.hsv(hue, .5f, .13f)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.align(Alignment.TopStart).padding(start = 14.dp, end = 14.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            for (w in listOf(.62f, .9f, .78f, .45f)) {
                Box(Modifier.fillMaxWidth(w).height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = .10f)))
            }
        }
        if (item.icon != null) {
            Image(item.icon, null, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
        } else {
            Text(
                item.title.take(1).uppercase(), color = Color.White.copy(alpha = .88f),
                fontSize = 44.sp, fontWeight = FontWeight.Light,
            )
        }
    }
}

/**
 * Coverflow (a row of cards, side ones turned toward the centre) or Carousel (a ring).
 * Horizontal drags spin it; it settles on the nearest card; tapping the centred card opens it.
 */
@Composable
private fun FlowWall(
    items: List<WallItem>,
    onOpen: (WallItem) -> Unit,
    actions: (WallItem) -> List<CardAction>,
    onClose: ((WallItem) -> Unit)?,
    ring: Boolean,
) {
    if (items.isEmpty()) return
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val widthPx = constraints.maxWidth.toFloat()
        val cardW = minOf(maxWidth * if (ring) .46f else .56f, 340.dp)
        val cardWPx = with(LocalDensity.current) { cardW.toPx() }
        val stepPx = if (ring) cardWPx * .9f else cardWPx * .62f
        val n = items.size
        fun settle(velocity: Float) = scope.launch {
            val target = if (ring) {
                val raw = (pos.value - velocity / stepPx * .25f).roundToInt()
                raw.toFloat()
            } else {
                (pos.value - velocity / stepPx * .25f).roundToInt().coerceIn(0, n - 1).toFloat()
            }
            pos.animateTo(target, spring(dampingRatio = .82f, stiffness = 180f))
        }
        val tracker = remember { VelocityTracker() }
        Box(
            Modifier.fillMaxSize().pointerInput(n, ring) {
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch {
                            val next = pos.value - dx / stepPx
                            pos.snapTo(if (ring) next else next.coerceIn(-.4f, n - .6f))
                        }
                    },
                    onDragEnd = { settle(tracker.calculateVelocity().x) },
                    onDragCancel = { settle(0f) },
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            val p = pos.value
            items.forEachIndexed { i, item ->
                val offset: Float
                val visible: Boolean
                if (ring) {
                    val center = ((p % n) + n) % n
                    var d = i - center
                    if (d > n / 2f) d -= n
                    if (d < -n / 2f) d += n
                    offset = d
                    visible = true
                } else {
                    offset = i - p
                    visible = abs(offset) < 7f
                }
                if (!visible) return@forEachIndexed
                val focused = abs(offset) < .5f
                var tx: Float
                var yaw: Float
                var scale: Float
                var z: Float
                var alpha = 1f
                if (ring) {
                    val a = Wall.carouselAngle(i, n, -p * 360f / n)
                    val rad = Math.toRadians(a.toDouble())
                    val radius = widthPx * .36f
                    tx = (sin(rad) * radius).toFloat()
                    val depth = cos(rad).toFloat() // 1 front, -1 back
                    yaw = a * Wall.ROTATION_SIGN
                    scale = .62f + .38f * (depth + 1f) / 2f
                    z = depth
                    alpha = .35f + .65f * (depth + 1f) / 2f
                } else {
                    val f = Wall.coverflow(offset)
                    tx = f.translateFactor * cardWPx
                    yaw = f.yaw
                    scale = f.scale
                    z = f.z
                }
                Box(
                    Modifier
                        .zIndex(z)
                        .width(cardW)
                        .graphicsLayer {
                            translationX = tx
                            rotationY = yaw
                            scaleX = scale; scaleY = scale
                            this.alpha = alpha
                            cameraDistance = 12f * density
                        },
                ) {
                    Card(
                        item,
                        onOpen = { if (focused) onOpen(it) else scope.launch { pos.animateTo(p + offset, tween(320)) } },
                        actions = actions,
                        onClose = if (focused) onClose else null,
                        reflect = true,
                    )
                }
            }
            val center = if (ring) (((p.roundToInt() % n) + n) % n) else p.roundToInt().coerceIn(0, n - 1)
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(items[center].title, color = Ink.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(items[center].subtitle, color = Ink.TextDim, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}
