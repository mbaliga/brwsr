package dev.aarso.brwsr.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** brwsr's palette: near-black materials, warm ink, one violet accent. */
object Ink {
    val Void = Color(0xFF000000)
    val Near = Color(0xFF0A0809)
    val Raised = Color(0xFF121212)
    val Surface = Color(0xFF16161A)
    val SurfaceRaised = Color(0xFF212128)
    val SurfaceHigh = Color(0xFF2C2C34)
    val Groove = Color(0xFF050506)
    val Edge = Color(0xFF3A3A44)
    val Rim = Color(0x29FFFFFF)
    val Hairline = Color(0x14FFFFFF)
    val Text = Color(0xEBECE8E4)
    val TextDim = Color(0x8AECE8E4)
    val TextFaint = Color(0x45ECE8E4)
    val Light = Color(0xFFD9D9D9)
    val Accent = Color(0xFF8E7BFF)
    val AccentBright = Color(0xFFA593FF)
    val Danger = Color(0xFFE5564B)
    val Warning = Color(0xFFE0941A)
    val Mono = FontFamily.Monospace
}

@Composable
fun BrwsrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Accent, onPrimary = Ink.Near, secondary = Ink.AccentBright,
            background = Ink.Void, surface = Ink.Raised, onSurface = Ink.Text, onBackground = Ink.Text,
            surfaceVariant = Ink.SurfaceRaised, onSurfaceVariant = Ink.TextDim, outline = Ink.Edge,
            error = Ink.Danger,
        ),
        content = content,
    )
}

val PanelShape = RoundedCornerShape(22.dp)

/** A raised glass-dark panel — the one container shape used for menus and sheets. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(PanelShape)
            .background(Brush.verticalGradient(listOf(Ink.SurfaceRaised, Ink.Surface)))
            .border(BorderStroke(1.dp, Ink.Hairline), PanelShape)
            .padding(vertical = 8.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = TextStyle(fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextFaint),
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
fun MenuRow(
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(label, color = Ink.Text, fontSize = 15.sp)
            if (detail != null) Text(detail, color = Ink.TextDim, fontSize = 12.sp)
        }
        trailing?.invoke(this)
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    MenuRow(label, detail = detail, onClick = { onChange(!checked) }, trailing = {
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink.Near, checkedTrackColor = Ink.Accent,
                uncheckedThumbColor = Ink.TextDim, uncheckedTrackColor = Ink.Groove, uncheckedBorderColor = Ink.Edge,
            ),
        )
    })
}

/** A row of mutually exclusive pills. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(CircleShape)
            .background(Ink.Groove)
            .padding(3.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (on) Ink.SurfaceHigh else Color.Transparent)
                    .clickable { onSelect(o) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), color = if (on) Ink.Text else Ink.TextDim, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}
