package dev.aarso.brwsr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.brwsr.web.ZapprState
import kotlinx.coroutines.delay

/**
 * Zappr's controls. The page shows what's selected (outlined); this bar walks the tree,
 * zaps, or sends the selector to the CSS editor. The search field finds by CSS selector or
 * by text ("Sponsored", "Recommended for you") and zaps every match — text zaps keep working
 * as the page loads more.
 */
@Composable
fun ZapprBar(
    state: ZapprState,
    atTop: Boolean,
    onParent: () -> Unit,
    onChild: () -> Unit,
    onZap: (similar: Boolean) -> Unit,
    onStyle: (similar: Boolean) -> Unit,
    onSearch: (String, Int) -> Unit,
    onZapSearch: () -> Unit,
    onDone: () -> Unit,
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(query, state.searchUp, searching) {
        if (!searching) return@LaunchedEffect
        delay(250)
        onSearch(query, state.searchUp)
    }
    Box(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),
        contentAlignment = if (atTop) Alignment.TopCenter else Alignment.BottomCenter,
    ) {
        Panel(Modifier.fillMaxWidth()) {
            if (searching) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Ink.Text, fontSize = 15.sp, fontFamily = Ink.Mono),
                        cursorBrush = SolidColor(Ink.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSearch = { onSearch(query, state.searchUp) }),
                        modifier = Modifier.weight(1f).clip(CircleShape).background(Ink.Groove).padding(horizontal = 16.dp, vertical = 12.dp),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text(".selector  or  text on the page", color = Ink.TextFaint, fontSize = 15.sp)
                            inner()
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val n = state.searchCount
                    Text(
                        when {
                            n < 0 -> ""
                            else -> "$n ${if (state.searchMode == "css") "matches" else "found"}"
                        },
                        color = Ink.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    if (state.searchMode == "text") {
                        Chip("Wider ↑${state.searchUp}") { onSearch(query, state.searchUp + 1) }
                        if (state.searchUp > 0) Chip("Narrower") { onSearch(query, state.searchUp - 1) }
                    }
                    Chip("Zap all", accent = true, enabled = n > 0) { onZapSearch() }
                    Chip("Tap") { searching = false; query = "" ; onSearch("", 0) }
                }
            } else {
                Text(
                    state.desc ?: "Tap any element on the page",
                    color = if (state.desc != null) Ink.Text else Ink.TextDim,
                    fontSize = 13.sp, fontFamily = if (state.desc != null) Ink.Mono else null, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val sel = state.desc != null
                    IconChip(Icons.Filled.KeyboardArrowUp, sel, onParent)
                    IconChip(Icons.Filled.KeyboardArrowDown, sel, onChild)
                    Chip("Zap", accent = true, enabled = sel) { onZap(false) }
                    if (state.similarCount > 1) Chip("Zap all ${state.similarCount} alike", enabled = sel) { onZap(true) }
                    Chip("Style", enabled = sel) { onStyle(false) }
                    IconChip(Icons.Filled.Search, true) { searching = true }
                    IconChip(Icons.Filled.Check, true, onDone)
                }
            }
        }
    }
}

@Composable
fun Chip(text: String, accent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .height(40.dp)
            .clip(CircleShape)
            .background(if (accent && enabled) Ink.Accent else Ink.SurfaceHigh)
            .border(1.dp, if (accent) Color.Transparent else Ink.Rim, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            color = when {
                !enabled -> Ink.TextFaint
                accent -> Ink.Near
                else -> Ink.Text
            },
        )
    }
}

@Composable
private fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Ink.SurfaceHigh).border(1.dp, Ink.Rim, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) Ink.Text else Ink.TextFaint) }
}
