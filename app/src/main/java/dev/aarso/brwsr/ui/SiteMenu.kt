package dev.aarso.brwsr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aarso.brwsr.data.SiteRule
import dev.aarso.brwsr.web.Tab

/** Everything about the current site, behind the pill's hamburger. */
@Composable
fun SiteMenu(
    tab: Tab?,
    rule: SiteRule,
    tabCount: Int,
    adblockGlobal: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onHome: () -> Unit,
    onShare: () -> Unit,
    onTabs: () -> Unit,
    onDesktop: (Boolean) -> Unit,
    onAdblock: (Boolean) -> Unit,
    onJs: (Boolean) -> Unit,
    onNakd: (now: Boolean, always: Boolean) -> Unit,
    onZappr: () -> Unit,
    onCustomize: () -> Unit,
    onPin: () -> Unit,
    onSettings: () -> Unit,
) {
    val onPage = tab != null && !tab.landing && tab.url != null
    Panel(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NavIcon(Icons.Filled.ArrowBack, enabled = tab?.canGoBack == true || tab?.landing == true && tab.url != null, onBack)
            NavIcon(Icons.Filled.ArrowForward, enabled = tab?.canGoForward == true, onForward)
            NavIcon(Icons.Filled.Refresh, enabled = onPage, onReload)
            NavIcon(Icons.Filled.Home, enabled = true, onHome)
            TabsCount(tabCount, onTabs)
            NavIcon(Icons.Filled.Share, enabled = onPage, onShare)
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (onPage) {
                tab!!
                Text(
                    tab.pageHost ?: "",
                    color = Ink.TextDim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 18.dp, top = 6.dp),
                )
                MenuRow("Zappr", detail = "Tap or search elements to zap", onClick = onZappr)
                MenuRow("Customize", detail = "Site CSS, dark, fonts, zapped elements", onClick = onCustomize)
                MenuRow(
                    "Nakd", detail = if (rule.nakd) "Always stripped here" else "Strip this page of its CSS",
                    onClick = { onNakd(!(tab.nakdNow || rule.nakd), false) },
                    trailing = {
                        Box(
                            Modifier.clip(CircleShape)
                                .background(if (rule.nakd) Ink.Accent else Ink.Groove)
                                .clickable { onNakd(!rule.nakd, true) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("Always", color = if (rule.nakd) Ink.Near else Ink.TextDim, fontSize = 12.sp) }
                    },
                )
                ToggleRow("Desktop site", tab.desktop, detail = if (tab.desktop) "Trackball puck on" else null, onChange = onDesktop)
                ToggleRow(
                    "Block ads & trackers", adblockGlobal && !rule.adblockOff,
                    detail = if (tab.blocked > 0) "${tab.blocked} blocked on this page" else null,
                    onChange = onAdblock,
                )
                ToggleRow("JavaScript", !rule.jsOff, onChange = onJs)
                MenuRow("Pin to landing", onClick = onPin)
            }
            MenuRow("Settings", onClick = onSettings)
        }
    }
}

@Composable
private fun NavIcon(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else .3f),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Ink.Text) }
}

@Composable
private fun TabsCount(n: Int, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Ink.Groove)
                .padding(1.dp),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = Ink.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}
