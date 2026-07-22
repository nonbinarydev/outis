[Outis](../README.md) › [Docs](README.md) › Getting started on Web

# Getting started on Web (JS)

There are two ways to run Outis in a browser:

- **Compose Multiplatform for JS** — take `outis-ui` and get `PlayerView` with the same controls
  overlay Android and iOS use. The Compose UI composites *over* the engine's `<video>`.
- **`outis-core` alone** — you get a `VideoPlayer` and an `HTMLVideoElement`, and you build the DOM UI.

Three things block a first run and none of them are discoverable from the API: a Gradle property for
the Compose canvas, the host page, and — on the `:core`-only route — the fact that the engine creates
its `<video>` but never puts it in the document. This page covers all three, in that order, plus the
two page-level side effects the Compose surface has on your DOM.

The web engine is [Shaka Player](https://github.com/shaka-project/shaka-player) 4.11.2 driving an
HTML `<video>` element. Adaptive sources (HLS, DASH) go through Shaka; a single progressive file
(mp4, webm) is played straight off `video.src`, which matters more than it sounds — see
[Progressive sources take a different path](#8-progressive-sources-take-a-different-path).

## Use the js target, not wasmJs

`outis-core` publishes both `js` and `wasmJs`, but only `js` has an engine. On `wasmJs`,
`createPlatformPlayer` throws `UnsupportedOperationException` the moment you construct a player
(`core/src/wasmJsMain/kotlin/dev/nonbinary/outis/core/PlayerFactory.wasmJs.kt:12-13`); the target
exists so the engine-agnostic core compiles there, nothing more. `outis-ui` does not publish a
`wasmJs` target at all (`ui/build.gradle.kts:27-53` declares android, iosArm64, iosSimulatorArm64
and `js(IR)` only), so a `commonMain` dependency on it from a module that also targets `wasmJs` will
not resolve.

## 1. Gradle setup

```kotlin
// build.gradle.kts of your web (or shared) module
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")            // only for outis-ui
    id("org.jetbrains.kotlin.plugin.compose") // only for outis-ui
}

kotlin {
    js(IR) {
        browser()
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation("io.github.nonbinarydev:outis-core:0.1.0-alpha01")
                implementation("io.github.nonbinarydev:outis-ui:0.1.0-alpha01") // optional
                implementation(compose.runtime)                                 // with outis-ui
                implementation(compose.foundation)
                implementation(compose.ui)
            }
        }
    }
}
```

### The Compose JS canvas property

If you use `outis-ui`, add this to your project's `gradle.properties`:

```properties
org.jetbrains.compose.experimental.jscanvas.enabled=true
```

Without it the Compose Multiplatform Gradle plugin does not configure Compose for the `js` target.
This repository sets the same flag for the same reason (`gradle.properties:10`). It is a
Compose-plugin flag, not an Outis one, and `:core`-only consumers do not need it.

### You do not add `shaka-player` yourself

`:core` declares `implementation(npm("shaka-player", "4.11.2"))` in its `jsMain` source set
(`core/build.gradle.kts:131`), and the Kotlin/JS plugin records that in the artifact's published
`package.json` — this repository's own build output shows `"shaka-player": "4.11.2"` under
`dependencies` for both `outis-core` and `outis-ui`. Your project's generated `package.json` picks it
up transitively. Adding it again yourself only risks a version conflict.

## 2. The host page

Kotlin/JS copies `src/jsMain/resources/` into the browser distribution, so put `index.html` there:

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Player</title>
  <style>
    html, body { margin: 0; padding: 0; height: 100%; overflow: hidden; background: #000; }
    #composeApp { width: 100%; height: 100%; }
  </style>
</head>
<body>
  <div id="composeApp"></div>
  <script src="your-module.js"></script>
</body>
</html>
```

Two notes on that file:

- **The script name is your Kotlin/JS module name**, which the plugin derives from the root project
  and module names — this repository's `:core` module, under root project `outis`, produces
  `outis-core.js`. Read the name off your first build's output rather than guessing it.
- **`overflow: hidden` on `body` is deliberate.** The engine's `<video>` is `position: fixed`, so it
  is positioned against the viewport and does not move when the *document* scrolls. Keep the Compose
  viewport the size of the window and scroll inside Compose. See
  [The `<video>` is `position: fixed`](#the-video-is-position-fixed).

If you use client-side ads, the page also needs the IMA loader — see
[Client-side ads need `ima3.js`](#7-client-side-ads-need-ima3js).

## 3. The Compose entry point

Compose Multiplatform 1.11.1 mounts a JS composition with `ComposeViewport`
(`androidx.compose.ui.window.ComposeViewport`, `@ExperimentalComposeUiApi`). It takes either a
container element id or an `Element`; passing `null` (the default) uses `<body>`.

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeViewport
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.PlayerView

@OptIn(ExperimentalComposeUiApi::class, ExperimentalPlayerUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "composeApp") {
        // AppContext() takes no arguments on web — only Android carries real data.
        val player = remember { VideoPlayer(AppContext()) }
        DisposableEffect(player) {
            player.setMediaItem(
                MediaItem(
                    MediaSource.Url("https://example.com/master.m3u8"),
                    mimeType = MimeType.HLS,
                    startMuted = true, // browsers block unmuted autoplay
                ),
                autoPlay = true,
            )
            onDispose { player.release() } // idempotent
        }
        PlayerView(player, modifier = Modifier.fillMaxSize())
    }
}
```

`ComposeViewport` **clears the container element** when it creates the composition, so do not put
your own markup inside `#composeApp`.

`@ExperimentalPlayerUiApi` is a warning-level opt-in
(`ui/src/commonMain/kotlin/dev/nonbinary/outis/ui/ExperimentalPlayerUiApi.kt:10`), so the `@OptIn` is
optional; it only silences the warning. `@ExperimentalComposeUiApi` on `ComposeViewport` is
Compose's own and is required.

`PlayerView` takes `surfaceType` and `showSubtitles` parameters that the web `PlayerSurface` accepts
and never reads (`ui/src/jsMain/kotlin/dev/nonbinary/outis/ui/PlayerSurface.js.kt:61-62`) — they are
Android and iOS concerns. Text-track *selection* still works: `selectTrack` / `clearTextTrack` drive
Shaka's text tracks on the adaptive path.

There is no `rememberPlayerWindow` on web — it is an Android actual
(`ui/src/androidMain/kotlin/dev/nonbinary/outis/ui/window/PlayerWindow.android.kt`). If you want the
fullscreen button, pass a `PlayerWindow` whose `isFullscreen` you keep in sync and whose
`onToggleFullscreen` calls the browser's Fullscreen API yourself; with `onToggleFullscreen == null`
the button hides itself.

Keyboard shortcuts are web-only and already wired: Space or K toggles play/pause, M mutes, F toggles
fullscreen — registered as a `document` keydown listener and gated on the pointer being over the
player, so Space still scrolls the page everywhere else
(`ui/src/jsMain/kotlin/dev/nonbinary/outis/ui/PlayerKeyboard.js.kt:20-57`).

## 4. Two side effects on your page

The Compose surface has to get a DOM `<video>` and a skiko `<canvas>` to composite correctly. It does
that by rewriting styles on elements it does not own. Both effects are page-wide.

### The overlay rewrites `z-index` on every direct child of `<body>`

When `PlayerSurface` mounts the video it calls `raiseComposeHostsAbove`
(`ui/src/jsMain/kotlin/dev/nonbinary/outis/ui/PlayerSurface.js.kt:138-146`), which walks
`document.body.children` and, for **every** direct child except the video:

- sets `style.zIndex = "1"`;
- sets `style.position = "relative"` if the computed position is `static`.

The intent is to lift the Compose canvas above the `<video>` (which is given `z-index: 0` at
`PlayerSurface.js.kt:86`) so the overlay composites on top while the video shows through the
`BlendMode.Clear` hole punched at `PlayerSurface.js.kt:113`. But it is indiscriminate: your own
body-level header, banner or modal root gets the same treatment. Consequences worth planning for:

- Anything of yours that must paint above the player needs an explicit `z-index` greater than 1.
- An element of yours that was `position: static` becomes positioned, which makes it a containing
  block for absolutely-positioned descendants. If your layout depends on `static`, do not put that
  element at the top level of `<body>` — nest it one level down.
- The engine's own ad container is a body child too (`ShakaEngine.kt:242`), so it is flattened to
  `z-index: 1` as well; the engine re-asserts `z-index: 2` on it during ad breaks from its reconcile
  poll (`ShakaEngine.kt:644-660`).

The simplest way to stay out of the way of this is to keep `<body>` down to one container element
and render your whole page inside the Compose viewport.

### The `<video>` is `position: fixed`

`PlayerSurface` appends the element to `document.body` and styles it `position: fixed`, `margin: 0`,
`background-color: black`, `z-index: 0`, with `object-fit` derived from `contentScale`
(`PlayerSurface.js.kt:83-90`). Its rect is then written directly from Compose layout in
`onGloballyPositioned`, in CSS pixels (`PlayerSurface.js.kt:114-122`).

Because it is fixed and a child of `<body>`, it is **not clipped by, and does not scroll with, any
DOM ancestor**. A player nested inside a scrollable DOM panel with `overflow: hidden` will still
paint over that panel's edges, and scrolling the document moves the Compose canvas without moving
the video, until the next Compose layout pass repositions it. Scroll inside Compose, not in the DOM,
and keep the viewport container fixed to the window.

Two smaller behaviours from the same file: the surface forces `controls = false` whenever it mounts
the element (`PlayerSurface.js.kt:81`), and it sets `display: none` on the video while
`PlayerState.error != null` so a frozen frame does not show through the hole
(`PlayerSurface.js.kt:104-107`). On dispose it removes the element from the DOM
(`PlayerSurface.js.kt:93-98`).

## 5. Using `:core` alone — you must mount the `<video>` yourself

`ShakaEngine` creates its `<video>` in a property initialiser
(`core/src/jsMain/kotlin/dev/nonbinary/outis/core/ShakaEngine.kt:179-183`) with `controls = false`
and `playsinline`, attaches Shaka to it, and **never adds it to the document**. The only element the
engine appends to `document.body` is the ad container (`ShakaEngine.kt:242`). Nothing renders until
you mount the element. This is the single most likely reason for "the player works but I see a black
page" on the `:core`-only route.

The element is `nativePlayerHandle` (`ShakaEngine.kt:190-191`):

```kotlin
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement

fun main() {
    val player = VideoPlayer(AppContext())

    // On web the handle is the HTMLVideoElement — never the shaka.Player, which is not exposed.
    val video = player.nativePlayerHandle as HTMLVideoElement
    video.style.width = "100%"
    video.style.height = "100%"
    video.controls = true // the engine leaves the native controls off
    document.getElementById("player")?.appendChild(video)

    player.setMediaItem(
        MediaItem(
            MediaSource.Url("https://example.com/master.m3u8"),
            mimeType = MimeType.HLS,
            startMuted = true,
        ),
        autoPlay = true,
    )
    // player.release() when you are done — then remove the element yourself (see below).
}
```

Four things to know about that handle on web:

- **Read it straight after construction.** `PlayerEvent.NativePlayerAttached` is emitted from the
  engine's `init` block (`ShakaEngine.kt:248`) into a `SharedFlow` with `replay = 0`
  (`ShakaEngine.kt:176`), so a collector you start afterwards will never see it. The event matters on
  Android, where the engine can be rebuilt; on web the element is created once and reused.
- **It is stable for the life of the player.** A failed load can wedge Shaka, and the engine then
  recreates the `shaka.Player` (`ShakaEngine.kt:360-370`) — but it re-attaches the *same* `<video>`.
- **It becomes `null` after `release()`** (`ShakaEngine.kt:671`). `release()` pauses the element,
  removes the ad container and destroys the Shaka player, but it does **not** remove the `<video>`
  from the DOM (`ShakaEngine.kt:660-672`). If you mounted it, unmount it.
- **Do not drive it directly.** The engine assigns `src`, calls `play()`/`pause()` and reconciles
  `PlayerState` from the element on a poll every `PlayerConfig.positionPollIntervalMs` (default
  250 ms, `PlayerFactory.kt:26`; interval registered at `ShakaEngine.kt:246`). Styling, mounting and
  `controls` are yours; playback is the player's.

## 6. Autoplay: use `startMuted`

Browsers block autoplay with sound. `MediaItem.startMuted` sets `video.muted = true` before the load
begins (`ShakaEngine.kt:402`) and reflects it in `PlayerState.isMuted` (`ShakaEngine.kt:409`). It only
ever forces mute *on*; it never unmutes (`MediaItem.kt:63-67`). Unmute after a user gesture with
`player.setMuted(false)`, which restores the volume set by `setVolume` rather than ramping from zero.

## 7. Client-side ads need `ima3.js`

Web CSAI runs through Shaka's ad manager, which wraps the IMA HTML5 SDK. The engine constructs the
request with `js("new google.ima.AdsRequest()")` (`ShakaEngine.kt:590`), so the global `google.ima`
must already exist. Add the loader to your `index.html`, before your module script:

```html
<script src="https://imasdk.googleapis.com/js/sdkloader/ima3.js"></script>
```

The ad UI renders into a `<div>` the engine creates and keeps aligned over the video, flipping it to
`pointer-events: auto` and re-asserting `z-index: 2` only during a break (`ShakaEngine.kt:235-242`
and `644-660`) — so IMA's skip and click-through are reachable above the Compose canvas, and the
overlay receives pointer events the rest of the time.

Note that the shipped Compose controls have no ad awareness of their own: nothing in `ui/src` reads
`PlayerState.adState`, and the scrubber is not locked during a break. Full detail, including which
`AdState` fields Web actually populates, is in [Client-side ads](ads-client-side.md).

## 8. Progressive sources take a different path

`isProgressive` (`ShakaEngine.kt:1001-1010`) routes `MimeType.MP4`, any `MediaSource.LocalFile`, and
extension-matched `.mp4` / `.webm` URLs to `video.src` directly, bypassing Shaka. On that path:

- **`MediaItem.headers` do not apply** — a browser `<video>` cannot carry custom request headers
  (`ShakaEngine.kt:481-484`). They are honoured on the Shaka path only.
- **`AdConfig.ClientSide` is silently ignored** — ads are requested inside the Shaka load handler
  (`ShakaEngine.kt:466`), which the progressive branch returns before reaching
  (`ShakaEngine.kt:448-452`).
- **Audio and text tracks stay empty** — `loadTracks()` runs on the Shaka path only.

`MediaSource.LocalFile.path` is assigned to `video.src` verbatim, and a browser cannot open a
filesystem path, so local-file playback on web needs a URL the page origin can actually serve. See
[Local files and chapters](local-files.md).

## 9. Other web-specific behaviour

| Behaviour | Detail |
|---|---|
| `PlayerState.videoSize` | Always `null` on web — the engine only ever writes `null` (`ShakaEngine.kt:408`, `:568`). Never gate layout on it. |
| `CaptionsDefaultMode.FOLLOW_SYSTEM` | Treated as `OFF` on web; there is no universal caption-accessibility signal (`MediaItem.kt:103-107`). |
| DRM | Widevine in Chrome, Edge and Firefox; PlayReady in Edge; FairPlay in Safari. A DRM failure at load time surfaces as `PlayerError.Category.SOURCE`, not `DRM` — see [DRM](drm.md). |
| Chapters | Not extracted on web. `PlayerState.chapters` stays empty. |

Everything else — sources, transport, state and events, tracks, live, quality caps — behaves as
described in [Playback](playback.md).

---

**See also:** [Playback](playback.md) · [The Compose UI](ui.md) ·
[Platform support](platform-support.md) · [Troubleshooting](troubleshooting.md)
