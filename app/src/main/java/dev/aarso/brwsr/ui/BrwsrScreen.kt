package dev.aarso.brwsr.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.aarso.brwsr.core.PullDir
import dev.aarso.brwsr.core.UrlInput
import dev.aarso.brwsr.core.WallDensity
import kotlinx.coroutines.delay
import dev.aarso.brwsr.web.Browser
import dev.aarso.brwsr.web.MouseInjector

private sealed interface Overlay {
    data object None : Overlay
    data object Settings : Overlay
    data class Customize(val prefill: String?) : Overlay
}

/** Callbacks into the Activity for things Compose can't do itself. */
class ScreenActions(
    val share: (String, String) -> Unit,
    val exit: () -> Unit,
    val clearBrowsingData: () -> Unit,
)

@Composable
fun BrwsrScreen(browser: Browser, actions: ScreenActions) {
    val settings = browser.settings
    val haptic = LocalHapticFeedback.current
    val tab = browser.current
    var expanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
    var puckHidden by remember { mutableStateOf(false) }
    val mouse = remember { MouseInjector { browser.current?.webView } }

    fun collapse() { expanded = false; menuOpen = false }

    BackHandler {
        when {
            overlay != Overlay.None -> overlay = Overlay.None
            expanded -> collapse()
            !browser.back() -> actions.exit()
        }
    }

    val webArea = Modifier.fillMaxSize().then(if (settings.immersive) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars))

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The page.
        AndroidView(
            factory = { ctx -> PinchFrame(ctx) },
            update = { container ->
                container.canPinch = browser::canPinchToTabs
                container.onPinchIn = {
                    collapse()
                    browser.goHome()
                }
                val wv = browser.current?.webView
                if (wv != null && wv.parent !== container) {
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    container.removeAllViews()
                    container.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
            modifier = webArea,
        )

        val onPage = tab != null && !tab.landing && tab.url != null

        // Desktop mode: the trackball puck.
        if (onPage && tab!!.desktop && !puckHidden && !browser.zappr.active && !expanded && overlay == Overlay.None) {
            Box(webArea) { PuckLayer(settings, mouse, onHide = { puckHidden = true }) }
        }
        LaunchedEffect(tab?.desktop) { if (tab?.desktop == true) puckHidden = false }

        // Landing: a wall of the open pages. Pinch to change how many fit, like a photo library.
        if (!onPage) {
            val open = browser.tabs.filter { it.url != null }
            if (open.isNotEmpty()) {
                // Keep the cards fresh: the denser the wall, the lazier the refresh.
                LaunchedEffect(settings.wallCols) {
                    while (true) {
                        delay(WallDensity.refreshMillis(settings.wallCols))
                        browser.current?.let { browser.captureThumb(it, whileLanding = true) }
                    }
                }
                WallView(
                    items = open.map { t ->
                        WallItem(
                            t.id.toString(), t.title.ifBlank { t.pageHost ?: "New tab" }, t.url?.let(UrlInput::pretty).orEmpty(),
                            t.thumb, t.favicon, highlighted = t === tab,
                        )
                    },
                    style = settings.wallStyle,
                    onOpen = { item ->
                        open.firstOrNull { it.id.toString() == item.key }?.let { browser.select(it); browser.showPage() }
                    },
                    actions = { emptyList() },
                    onClose = { item -> open.firstOrNull { it.id.toString() == item.key }?.let(browser::close) },
                    cols = settings.wallCols,
                    onCols = { settings.wallCols = it; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                )
            } else {
                val sites = browser.topSites.ranked()
                LaunchedEffect(sites.map { it.host }) { browser.topSites.ensureImages(sites.map { it.host }) }
                WallView(
                    items = sites.map {
                        WallItem(it.host, it.title, UrlInput.pretty(it.url), browser.topSites.thumbs[it.host], browser.topSites.icons[it.host], it.pinned, highlighted = it.pinned)
                    },
                    style = settings.wallStyle,
                    onOpen = { item -> sites.firstOrNull { it.host == item.key }?.let { s -> browser.submit(s.url) } },
                    actions = { item ->
                        val s = sites.first { it.host == item.key }
                        listOf(
                            CardAction(if (s.pinned) "Unpin" else "Pin") { browser.topSites.pin(s.host, !s.pinned) },
                            CardAction("Remove") { browser.topSites.hide(s.host) },
                        )
                    },
                )
            }
        }
        // While reading, keep this page's card current for the wall.
        LaunchedEffect(onPage, tab?.id) {
            while (onPage) {
                delay(4_000)
                browser.current?.let { browser.captureThumb(it) }
            }
        }

        // Zappr.
        if (browser.zappr.active) {
            ZapprBar(
                state = browser.zappr,
                atTop = !settings.orbCorner.isBottom,
                onParent = browser::zapprParent,
                onChild = browser::zapprChild,
                onZap = browser::zap,
                onStyle = { similar ->
                    browser.zapprSelector(similar) { sel ->
                        browser.stopZappr()
                        overlay = Overlay.Customize(sel)
                    }
                },
                onSearch = browser::zapprSearch,
                onZapSearch = browser::zapSearch,
                onDone = browser::stopZappr,
            )
        }

        // The orb.
        val insets = rememberOrbInsets(settings.orbInsetDp)
        if (!expanded && !browser.zappr.active && overlay == Overlay.None) {
            val host = tab?.pageHost
            Orb(
                corner = settings.orbCorner,
                insets = insets,
                icon = if (onPage) tab?.favicon ?: host?.let { browser.topSites.icons[it] } else null,
                letter = if (onPage) host?.take(1)?.uppercase().orEmpty() else "",
                progress = if (onPage) tab?.progress ?: 100 else 100,
                insecure = onPage && tab?.url?.startsWith("http://") == true,
                pullEnabled = { d -> onPage && (d != PullDir.Right || tab?.canGoForward == true) },
                glyphFor = { d ->
                    when (d) {
                        // Back with no history returns to the wall of open pages.
                        PullDir.Left -> if (tab?.canGoBack == true) OrbGlyph.Back else OrbGlyph.Wall
                        PullDir.Right -> OrbGlyph.Forward
                        PullDir.Down -> OrbGlyph.Reload
                        PullDir.Up -> OrbGlyph.Share
                    }
                },
                onCornerChange = { settings.orbCorner = it },
                onTap = { expanded = true },
                onPull = { d ->
                    when (d) {
                        PullDir.Left -> { browser.back() }
                        PullDir.Right -> browser.forward()
                        PullDir.Down -> browser.reload()
                        PullDir.Up -> tab?.url?.let { actions.share(it, tab.title) }
                    }
                },
            )
        }

        // The pill.
        if (expanded) {
            val rule = browser.ruleFor(tab)
            Pill(
                corner = settings.orbCorner,
                insets = insets,
                initialText = if (onPage) tab?.url.orEmpty() else "",
                suggestions = { q ->
                    if (q.isBlank() || (onPage && q == tab?.url)) emptyList()
                    else browser.topSites.ranked(200).asSequence()
                        .filter { it.host.contains(q, true) || it.title.contains(q, true) }
                        .take(5).map { it.title to it.url }.toList()
                },
                menuOpen = menuOpen,
                onGo = { text ->
                    collapse()
                    // From the wall, a typed address opens a new tab rather than replacing an open page.
                    if (!onPage && tab?.url != null) browser.newTab()
                    browser.submit(text)
                },
                onMenu = { menuOpen = !menuOpen },
                onDismiss = ::collapse,
            ) {
                SiteMenu(
                    tab = tab,
                    rule = rule,
                    tabCount = browser.tabs.size,
                    adblockGlobal = settings.adblock,
                    onBack = { collapse(); browser.back() },
                    onForward = { collapse(); browser.forward() },
                    onReload = { collapse(); browser.reload() },
                    onHome = { collapse(); browser.goHome() },
                    onShare = { collapse(); tab?.url?.let { actions.share(it, tab.title) } },
                    onTabs = { collapse(); browser.goHome() },
                    onDesktop = { browser.setDesktop(it) },
                    onAdblock = { browser.setAdblockForSite(it) },
                    onJs = { browser.setJsEnabled(it) },
                    onNakd = { now, always -> collapse(); browser.setNakd(now, always) },
                    onZappr = { collapse(); browser.startZappr() },
                    onCustomize = { collapse(); overlay = Overlay.Customize(null) },
                    onPin = { collapse(); tab?.url?.let { browser.topSites.addPinned(it, tab.title) } },
                    onSettings = { collapse(); overlay = Overlay.Settings },
                )
            }
        }

        when (val o = overlay) {
            Overlay.None -> Unit
            Overlay.Settings -> SettingsSheet(
                settings = settings,
                ruleCount = browser.filters.ruleCount,
                updating = browser.filters.updating,
                listError = browser.filters.lastError,
                blockedTotal = browser.filters.blockedCount.get(),
                onUpdateLists = { browser.filters.update() },
                onListsChanged = { browser.filters.update() },
                onClearBrowsing = actions.clearBrowsingData,
                onDismiss = { overlay = Overlay.None },
            )
            is Overlay.Customize -> {
                val host = tab?.pageHost
                if (host == null) overlay = Overlay.None
                else CustomizeSheet(
                    host = host,
                    rule = browser.ruleFor(tab),
                    globalCss = settings.globalCss,
                    prefill = o.prefill,
                    onRule = { change -> browser.updateRule(change) },
                    onTextZoom = browser::setTextZoom,
                    onGlobalCss = { settings.globalCss = it },
                    onDismiss = { overlay = Overlay.None },
                )
            }
        }
    }
}
