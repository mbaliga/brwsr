# brwsr

A fast, minimal browser. Android first; iOS / iPadOS later.

<img src="art/icon-512.png" width="96" alt="brwsr icon">

## No chrome — one orb

The only UI is a single **orb** in a corner (bottom-right by default). It shows the site's
icon, and a progress arc around its rim while a page loads.

- **Drag** it anywhere — it settles into the nearest corner and remembers it.
- **Tap** it and it opens into a **pill**: the URL field, with the orb's side becoming **Go**
  and the far side a **☰ site menu**. Behind it, a black gradient fades from 100% at the
  pill's edge to clear.
- **Long-press** it for your tabs.

The site menu holds back/forward/reload/home/tabs/share, and per-site switches: **Zappr**,
**Customize**, **Nakd**, **Desktop site**, **Block ads & trackers**, **JavaScript**,
**Pin to landing**, and **Settings**.

## Landing — Top Sites

New tabs open on a wall of the sites you visit most (pinned first; visits fade with a
two-week half-life), each shown as a thumbnail captured from the page. Pick the arrangement
in Settings:

| Style | |
|---|---|
| **Curved wall** | Columns turned inward — the concave wall of old Top Sites |
| **Flat grid** | The same wall, flat |
| **Coverflow** | A row of cards, side cards turned toward the centre, with floor reflections |
| **Carousel** | A ring you spin |

Long-press a card to pin or remove it. The tab switcher uses the same wall.

## Ad blocking, to the max

On by default, everything:

- **Network blocking** with EasyList, EasyPrivacy, uBlock filters, uBlock Privacy, AdGuard
  DNS, Peter Lowe's list, StevenBlack hosts, Fanboy Annoyances and URLhaus — ~300k rules.
  A built-in floor list ([`builtin.txt`](app/src/main/assets/filters/builtin.txt)) works offline from the
  first page. Lists refresh every 4 days (or on demand in Settings).
- **Cosmetic filtering**: site-specific rules, plus the ~40k generic rules served the way
  uBlock does it — the page reports the classes/ids it contains and only the rules that can
  match are injected, so pages don't pay for 40k selectors.
- **Pop-ups** blocked unless you tapped; **redirects into ad domains** without a tap blocked;
  **third-party cookies** off; **Global Privacy Control** + DNT sent; location, camera and
  microphone denied.

The engine ([`FilterEngine`](app/src/main/java/dev/aarso/brwsr/adblock/FilterEngine.kt)) understands
hosts files, `||domain^`, wildcard/anchor/separator patterns, `/regex/`, `@@` exceptions,
`$third-party`, `$domain=`, and `##` / `#@#` cosmetic rules. Rules it can't honour faithfully
(scriptlets, `$redirect`, `$csp`, procedural selectors) are skipped, never half-applied.
On a desktop JVM, the full set compiles in ~1.8s and a request check takes ~50µs.

## Zappr

An inspect-element view for getting rid of things. Open it from the site menu, then:

- **Tap** any element — it's outlined with its tag/class and size.
- **↑ / ↓** walk to the parent / back down.
- **Zap** hides it on this site, for good. **Zap all alike** hides every element of the same kind.
- **Style** opens the CSS editor with a ready-made rule for that element.
- **🔍 Search** by CSS selector (`.promo`, `aside`) or by **text** (`Sponsored`) — every match is
  outlined; **Wider/Narrower** pick how many ancestors around the text to take; **Zap all**
  saves it. Text zaps keep working on content that loads later (infinite feeds).

Zapped elements are listed in **Customize**, where each can be un-zapped.

## Customize (CSS)

Per site: **Dark** (smart invert that leaves images alone), **Unstick** (un-pin sticky
headers/nag bars and restore scrolling), **Hide images**, a **font** override, **text size**,
and a free-form **CSS editor** that applies live as you type. A second tab holds CSS for **all
sites**.

## Nakd

The old "strip this page of its CSS" bookmarklet, built in: removes every stylesheet,
`<style>` and inline style (and keeps removing any the page adds later), leaving only a tiny
guard so images don't overflow the screen. Tap once for this page, or **Always** for the site.

## Desktop mode — the trackball puck

Turning on **Desktop site** switches to a desktop user agent and a 1280px layout, and brings
up a cursor and a **puck**:

```
        ┌── scroll wheel (drag along it)
     ╱▔▔▔▔▔╲
   ╱ ╲ ═══ ╱ ╲
  │ L │ ◉ │ R │   ◉ centre: joystick or trackball; tap = click
   ╲ ╱  ☰  ╲ ╱    L/R: mouse buttons (primary ● on the left for right hands)
     ╲▁▁▁▁▁╱      ☰: drag to move · tap for puck settings · pinch to resize
```

- **Joystick**: push the centre; speed grows with how far you push.
- **Trackball**: roll the centre; it keeps rolling and slows down.
- Hold the primary button and steer to **drag** (select text, move sliders).
- **Right / left handed**: mirrors the buttons and moves the puck to that side.
- The cursor sends real mouse events — hover works, right-click opens the page's context
  menu, and the wheel scrolls whatever is under the cursor.

## Build

```bash
git submodule update --init          # shared-libraries (crash recovery)
./gradlew :app:testDebugUnitTest     # filter engine, URL input, geometry
./gradlew :app:assembleDebug
```

Requires JDK 17 and an Android SDK (`local.properties` → `sdk.dir`, or `ANDROID_HOME`).
AGP is pinned to **8.9.1** to match `shared-libraries` (Gradle rejects mixed AGP versions in
one composite build).

Injected-script tests (headless Chromium):

```bash
cd tools/js-test && npm install && npx playwright install chromium && node injected-scripts.test.js
```

The filter speed test runs against real lists if you fetch them into `app/build/filter-cache/`
(it is skipped otherwise, so CI never touches the network in unit tests).

CI builds a debug APK on every push and publishes `main` to the **brwsr-latest** prerelease.

### Layout

| Path | |
|---|---|
| `app/src/main/java/dev/aarso/brwsr/core/` | Pure logic: URL input, hosts, corners, wall geometry, joystick/trackball maths |
| `…/adblock/` | `FilterEngine` (pure) and `FilterLists` (download, cache, compile, swap) |
| `…/data/` | Settings, per-site rules, top sites |
| `…/web/` | `Browser` (tabs, WebView, blocking, injection), `JsBridge`, `MouseInjector` |
| `…/ui/` | Orb & pill, site menu, wall, puck, Zappr bar, sheets |
| `app/src/main/assets/js/` | `page.js` (document-start: cosmetic CSS, zaps, CSS, Nakd, GPC), `zappr.js`, `desktop.js` |
| `art/` | App icon source + generator |
| `shared-libraries/` | Submodule — `dev.aarso:crash-recovery` |

## Icon

`art/make_icon.py` renders the launcher icon at every density. It is currently a recreation of
the binary-tunnel artwork; to use the original, save it as `art/icon-source.png` and run
`python3 art/make_icon.py` — it is used verbatim.

## Not yet verified on a device

This was built and tested without an Android device or emulator: the Kotlin compiles, unit
tests and lint pass, and the injected scripts are tested in headless Chromium. Things that need
a real device to confirm:

- the curved wall's direction (concave vs convex) — one constant, `Wall.ROTATION_SIGN`
- mouse events from the puck: hover, right-click → `contextmenu`, wheel scrolling
- thumbnail capture from the WebView
