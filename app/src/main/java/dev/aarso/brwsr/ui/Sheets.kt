package dev.aarso.brwsr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.brwsr.core.Corner
import dev.aarso.brwsr.core.SearchEngine
import dev.aarso.brwsr.core.WallStyle
import dev.aarso.brwsr.data.FilterListCatalog
import dev.aarso.brwsr.data.FontChoice
import dev.aarso.brwsr.data.Hand
import dev.aarso.brwsr.data.PuckMode
import dev.aarso.brwsr.data.Settings
import dev.aarso.brwsr.data.SiteRule
import java.text.DateFormat
import java.util.Date

/** A bottom sheet over a dimmed page. */
@Composable
fun Sheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        )
        BoxWithConstraints(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(10.dp),
        ) {
            Panel(Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight * .88f)) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Ink.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Close, null, tint = Ink.TextDim)
                    }
                }
                Column(Modifier.verticalScroll(rememberScrollState()), content = content)
            }
        }
    }
}

/** Per-site look: presets, zapped elements, and a CSS editor (site or all sites). */
@Composable
fun CustomizeSheet(
    host: String,
    rule: SiteRule,
    globalCss: String,
    prefill: String?,
    onRule: ((SiteRule) -> SiteRule) -> Unit,
    onTextZoom: (Int) -> Unit,
    onGlobalCss: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var global by remember { mutableStateOf(false) }
    var siteCss by remember(host) {
        mutableStateOf(if (prefill != null) (rule.css.trimEnd() + "\n\n$prefill {\n  \n}\n").trimStart() else rule.css)
    }
    // Zappr's "Style" arrives with a selector: persist the new block straight away.
    LaunchedEffect(prefill) { if (prefill != null) onRule { it.copy(css = siteCss) } }
    Sheet(host, onDismiss) {
        SectionLabel("Look")
        ToggleRow("Dark", rule.dark) { v -> onRule { it.copy(dark = v) } }
        ToggleRow("Unstick", rule.unstick, detail = "Un-pin sticky headers and nag bars") { v -> onRule { it.copy(unstick = v) } }
        ToggleRow("Hide images", rule.hideImages) { v -> onRule { it.copy(hideImages = v) } }
        SectionLabel("Font")
        Segmented(FontChoice.entries, rule.font, { it.label }, { f -> onRule { it.copy(font = f) } })
        SectionLabel("Text size  ${rule.textZoom}%")
        Slider(
            value = rule.textZoom.toFloat(), onValueChange = { onTextZoom((it / 5).toInt() * 5) }, valueRange = 50f..200f,
            colors = SliderDefaults.colors(thumbColor = Ink.Light, activeTrackColor = Ink.Accent, inactiveTrackColor = Ink.Groove),
            modifier = Modifier.padding(horizontal = 18.dp),
        )

        if (rule.zaps.isNotEmpty() || rule.textZaps.isNotEmpty()) {
            SectionLabel("Zapped")
            rule.zaps.forEach { sel ->
                RemovableRow(sel) { onRule { r -> r.copy(zaps = r.zaps - sel) } }
            }
            rule.textZaps.forEach { tz ->
                RemovableRow("“${tz.text}”" + if (tz.up > 0) "  ↑${tz.up}" else "") { onRule { r -> r.copy(textZaps = r.textZaps - tz) } }
            }
        }

        SectionLabel("CSS")
        Segmented(listOf(false, true), global, { if (it) "All sites" else "This site" }, { global = it })
        val text = if (global) globalCss else siteCss
        BasicTextField(
            value = text,
            onValueChange = {
                if (global) onGlobalCss(it) else { siteCss = it; onRule { r -> r.copy(css = it) } }
            },
            textStyle = TextStyle(color = Ink.Text, fontSize = 13.sp, fontFamily = Ink.Mono, lineHeight = 19.sp),
            cursorBrush = SolidColor(Ink.Accent),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.None),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ink.Groove)
                .padding(14.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("body { max-width: 40em; margin: auto }", color = Ink.TextFaint, fontSize = 13.sp, fontFamily = Ink.Mono)
                inner()
            },
        )
        Text(
            "Applies live as you type.",
            color = Ink.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
        )
        MenuRow("Reset this site", onClick = { siteCss = ""; onRule { SiteRule() }; onTextZoom(100) })
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun RemovableRow(text: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Ink.Text, fontSize = 13.sp, fontFamily = Ink.Mono, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, null, tint = Ink.TextDim, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun SettingsSheet(
    settings: Settings,
    ruleCount: Int,
    updating: Boolean,
    listError: String?,
    blockedTotal: Long,
    onUpdateLists: () -> Unit,
    onListsChanged: () -> Unit,
    onClearBrowsing: () -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet("Settings", onDismiss) {
        SectionLabel("Orb")
        Segmented(
            Corner.entries, settings.orbCorner,
            { when (it) { Corner.TopLeft -> "↖"; Corner.TopRight -> "↗"; Corner.BottomLeft -> "↙"; Corner.BottomRight -> "↘" } },
            { settings.orbCorner = it },
        )
        ToggleRow("Full screen", settings.immersive, detail = "Hide the status and navigation bars") { settings.immersive = it }

        SectionLabel("Landing")
        Segmented(WallStyle.entries, settings.wallStyle, { it.label.substringBefore(' ') }, { settings.wallStyle = it })

        SectionLabel("Search")
        Segmented(SearchEngine.entries, settings.searchEngine, { it.label.take(10) }, { settings.searchEngine = it })

        SectionLabel("Blocking")
        ToggleRow("Block ads & trackers", settings.adblock, detail = "$ruleCount rules · $blockedTotal blocked this session") { settings.adblock = it }
        ToggleRow("Hide ad spaces & nags", settings.cosmetic, detail = "Cosmetic filtering") { settings.cosmetic = it }
        ToggleRow("Block pop-ups", settings.blockPopups) { settings.blockPopups = it }
        ToggleRow("Block third-party cookies", settings.blockThirdPartyCookies, detail = "Applies to new tabs") { settings.blockThirdPartyCookies = it }
        ToggleRow("Send Global Privacy Control", settings.sendGpc) { settings.sendGpc = it }

        SectionLabel("Filter lists")
        FilterListCatalog.all.forEach { spec ->
            ToggleRow(spec.title, spec.id in settings.enabledLists, detail = spec.what) { on ->
                settings.enabledLists = if (on) settings.enabledLists + spec.id else settings.enabledLists - spec.id
                onListsChanged()
            }
        }
        val updated = settings.listsUpdatedAt.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
        MenuRow(
            if (updating) "Updating…" else "Update lists now",
            detail = listError ?: updated?.let { "Updated $it" },
            onClick = if (updating) null else onUpdateLists,
        )

        SectionLabel("Desktop mode puck")
        Segmented(Hand.entries, settings.hand, { "${it.name} hand" }, { settings.hand = it; settings.puckX = -1f })
        Segmented(PuckMode.entries, settings.puckMode, { it.label }, { settings.puckMode = it })

        SectionLabel("Data")
        MenuRow("Clear browsing data", detail = "History, top sites, cookies, cache", onClick = onClearBrowsing)
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.Center) {
            Text("brwsr", color = Ink.TextFaint, fontSize = 12.sp)
        }
    }
}
