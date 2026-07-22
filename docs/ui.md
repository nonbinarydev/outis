[Outis](../README.md) › [Docs](README.md) › Compose UI

# The Compose UI (`outis-ui`)

`outis-ui` is one opinionated Compose Multiplatform chrome on top of `outis-core`. You do not need it:
`:core` is a complete player API, and plenty of apps will render their own controls against
`player.state`. Take `:ui` when you want a working player surface and overlay in one line, or when you
want the surface but your own controls.

The module publishes for **android, iosArm64, iosSimulatorArm64 and js** only — there is no `jvm` and
no `wasmJs` target, unlike `:core`. A shared module that also targets desktop or Wasm cannot depend on
`outis-ui` from `commonMain`. See [platform support](platform-support.md) for the full target sets.

```kotlin
dependencies {
    implementation("io.github.nonbinarydev:outis-ui:0.1.0-alpha01")
}
```

`outis-ui` declares `api(project(":core"))`, so `outis-core` comes with it
(`ui/build.gradle.kts:58`) — you do not need to add both.

## Contents

- [Opting in](#opting-in)
- [The four tiers](#the-four-tiers)
- [`PlayerView`](#playerview)
- [`PlayerSurface`](#playersurface)
- [Web surface](#web-surface)
- [Controls state](#controls-state)
- [Building blocks](#building-blocks)
- [Input model](#input-model)
- [TV and D-pad](#tv-and-d-pad)
- [Fullscreen and picture-in-picture](#fullscreen-and-picture-in-picture)
- [Background and lifecycle](#background-and-lifecycle)
- [What the shipped controls do not do](#what-the-shipped-controls-do-not-do)

## Opting in

Most of the module is annotated `@ExperimentalPlayerUiApi`. The marker is declared at
`RequiresOptIn.Level.WARNING` (`ExperimentalPlayerUiApi.kt:10`), so using it without opting in
compiles — you just get a warning. Add `@OptIn(ExperimentalPlayerUiApi::class)` to silence it.

Annotated: `PlayerView`, `PlayerSurface` (the `expect` and all three actuals), `DefaultControls`,
`ControlsScaffold`, and every building block in `BasicControls.kt`, `TrackControls.kt` and
`WindowControls.kt`.

Not annotated, despite being part of the same surface: `PlayerControlsState` and
`rememberPlayerControlsState` (`PlayerControlsState.kt:43, 241`), `PlayerControlsScope`
(`PlayerControlsScope.kt:23`), `PlayerWindow` (`PlayerWindow.kt:23`), `SurfaceType`
(`SurfaceType.kt:10`) and `rememberPlayerWindow` (`PlayerWindow.android.kt:47`). Treat them as equally
experimental; the missing markers are an oversight in the source, not a stability promise.

## The four tiers

### Tier 1 — batteries included

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.PlayerView

@OptIn(ExperimentalPlayerUiApi::class)
@Composable
fun Player(appContext: AppContext) {
    // VideoPlayer must be constructed on the main thread; composition already is.
    val player = remember(appContext) { VideoPlayer(appContext) }
    DisposableEffect(player) {
        player.setMediaItem(
            MediaItem(
                source = MediaSource.Url("https://example.com/master.m3u8"),
                mimeType = MimeType.HLS,
            ),
            autoPlay = true,
        )
        onDispose { player.release() }
    }
    PlayerView(player, Modifier.fillMaxSize())
}
```

`appContext` is `AppContext(context.applicationContext)` on Android and `AppContext()` everywhere
else. See the platform getting-started pages.

### Tier 2 — keep the layout, swap one region

`ControlsScaffold` is `DefaultControls` with its three regions exposed. **The scrubber is not one of
them.** `ControlsScaffold` draws `Scrubber(Modifier.fillMaxWidth())` itself, in a `Column` directly
above the `bottom` row (`DefaultControls.kt:90-93`), so `bottom` is the transport row only. Putting a
`Scrubber` in `bottom` renders a second one, stacked under the built-in one.

```kotlin
PlayerView(player) {
    ControlsScaffold(
        bottom = {
            PlayPauseButton()
            TimeLabel(Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            PlaybackSpeedButton()
            FullscreenButton()
        },
    )
}
```

The three overridable regions and their defaults (`DefaultControls.kt:49-70`):

| Region   | Receiver   | Default content |
| -------- | ---------- | --------------- |
| `top`    | `RowScope` | `SubtitleButton()`, `AudioButton()`, `PipButton()`, `FullscreenButton()` — top-end, 4.dp spacing |
| `center` | `BoxScope` | `BufferingIndicator` when `state.isWaitingToPlay`, else `BigPlayButton`, both centred |
| `bottom` | `RowScope` | `PlayPauseButton()`, `MuteButton()`, `TimeLabel()`, `Spacer(weight(1f))`, `PlaybackSpeedButton()` |

Inside a region lambda both receivers are in scope: the outer `PlayerControlsScope` (so the zero-arg
building blocks resolve) and the inner `RowScope`/`BoxScope` (so `Modifier.weight` and
`Modifier.align` resolve).

What the scaffold keeps doing for you regardless of overrides: the `0x66000000` scrim, the
`if (!state.controlsVisible) return` gate, `LocalContentColor = White`, the focus group, the D-pad
focus grab on appear, and re-arming auto-hide on focus movement.

### Tier 3 — your own overlay

The `controls` lambda's receiver is `PlayerControlsScope`, which carries `state`, `player`, `window`
and `focusRequester`.

```kotlin
PlayerView(player) {
    // Tier 3 replaces ControlsScaffold entirely, so YOU own the visibility gate.
    if (!state.controlsVisible) return@PlayerView
    Box(Modifier.fillMaxSize()) {
        BufferingIndicator(Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
            Scrubber(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayPauseButton()
                MuteButton()
                TimeLabel(Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                SubtitleButton()
                AudioButton()
                FullscreenButton()
            }
        }
    }
}
```

Three things you inherit from `ControlsScaffold` and lose here, in order of how often they are missed:

1. **The visibility gate.** Auto-hide flips `state.controlsVisible`, but nothing reads it for you. An
   overlay that never checks it is permanently on screen.
2. **The D-pad focus grab.** Apply `Modifier.focusRequester(focusRequester).focusGroup()` to your root
   and request focus in a `LaunchedEffect(state.controlsVisible)` if you care about TV.
3. **The focus ring.** `Modifier.controlFocusRing()` is `internal` (`FocusRing.kt:38`), so it is
   applied to the shipped `IconButton`s and cannot be applied to yours. Custom controls need their own
   focus indication.

`BufferingIndicator` gates itself on `isWaitingToPlay` (`BasicControls.kt:155`) and emits no layout
when hidden, so the surrounding `if` in the old README example was redundant.

`DefaultControls()` is written entirely against this public scope, so forking `DefaultControls.kt` is a
legitimate starting point.

### Tier 4 — controls beside the video

No `PlayerView`: hoist the state, place `PlayerSurface` yourself, and call the free-function form of
each building block.

```kotlin
@OptIn(ExperimentalPlayerUiApi::class)
@Composable
fun TvShell(player: VideoPlayer) {
    val state = rememberPlayerControlsState(player)
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(280.dp)) {
            PlayPauseButton(state)
            Scrubber(state)
            SubtitleButton(state)
            AudioButton(state)
        }
        PlayerSurface(player, Modifier.weight(1f))
    }
}
```

The `@Composable` wrapper is not optional — `rememberPlayerControlsState` and every building block are
composables.

Everything `PlayerView` wires is now yours to wire or to skip:

- Tap/click/hover gestures and the auto-hide interaction feedback.
- The web keyboard shortcuts. `PlatformPlayerKeyboard` is `internal` (`PlayerKeyboard.kt:23`), so this
  tier has no route to Space/K/M/F on web at all.
- `pauseWhenStopped`. Nothing else in the SDK touches the lifecycle.
- The PiP collapse (`if (!window.isInPip)`).

`FullscreenButton` and `PipButton` take `(state, window, modifier)` in free-function form, so this tier
needs a `PlayerWindow` to use them.

## `PlayerView`

`PlayerView.kt:64-73`.

```kotlin
@ExperimentalPlayerUiApi
@Composable
fun PlayerView(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    surfaceType: SurfaceType = SurfaceType.SurfaceView,
    window: PlayerWindow = PlayerWindow(),
    pauseWhenStopped: Boolean = true,
    state: PlayerControlsState = rememberPlayerControlsState(player),
    controls: @Composable PlayerControlsScope.() -> Unit = { DefaultControls() },
)
```

| Parameter | Notes |
| --------- | ----- |
| `player` | The surface re-binds when the engine re-emits `PlayerEvent.NativePlayerAttached`. |
| `contentScale` | Only three values are distinguished: `Crop`, `FillBounds`, and everything else, which maps to fit. Details under [`PlayerSurface`](#playersurface). |
| `surfaceType` | **Currently a no-op.** It is declared and threaded to `PlayerSurface`, but no actual reads it (`PlayerSurface.android.kt:44`, `.ios.kt:47`, `.js.kt:61` all ignore the parameter). Android therefore always gets Media3 `PlayerView`'s default `SurfaceView`. |
| `window` | Fullscreen/PiP delegation. Defaults to a no-op `PlayerWindow()`, which hides both buttons and disables mouse double-click-to-fullscreen. |
| `pauseWhenStopped` | See [background and lifecycle](#background-and-lifecycle). |
| `state` | Pass a hoisted state to share it with controls outside the overlay, or to change the auto-hide duration — the default calls `rememberPlayerControlsState(player)` with the 3-second default. |
| `controls` | Receiver is `PlayerControlsScope`. Not composed at all while `window.isInPip` is true (`PlayerView.kt:168`), so PiP shows bare video. |

There is no `showSubtitles` parameter on `PlayerView`. It calls
`PlayerSurface(player, Modifier.matchParentSize(), contentScale, surfaceType)`
(`PlayerView.kt:167`), leaving `showSubtitles` at its default of `true`. To turn native subtitle
rendering off you must drop to tier 4 and call `PlayerSurface` yourself.

On Android, note the name collision: `dev.nonbinary.outis.ui.PlayerView` and
`androidx.media3.ui.PlayerView` cannot both be imported unqualified in the same file.

## `PlayerSurface`

`PlayerSurface.kt:24-30`.

```kotlin
@ExperimentalPlayerUiApi
@Composable
expect fun PlayerSurface(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    surfaceType: SurfaceType = SurfaceType.SurfaceView,
    showSubtitles: Boolean = true,
)
```

It draws only the video, plus subtitle cues on platforms that render them natively. It is deliberately
public so video can go anywhere in your layout — an overlay, a rail, a mini-player.

| Platform | Implementation | `contentScale` | `showSubtitles` |
| -------- | -------------- | -------------- | --------------- |
| Android | Media3 `PlayerView` with `useController = false`, black background (`PlayerSurface.android.kt:57-60`) | `Crop` → `RESIZE_MODE_ZOOM`, `FillBounds` → `RESIZE_MODE_FILL`, else `RESIZE_MODE_FIT` | Honoured: toggles `subtitleView.visibility` (`:72`) |
| iOS | `AVPlayerViewController` with `showsPlaybackControls = false`, hosted by `UIKitViewController` (`PlayerSurface.ios.kt:57-78`) | `Crop` → `ResizeAspectFill`, `FillBounds` → `Resize`, else `ResizeAspect` | **Ignored.** AVKit renders the selected text track and there is no switch. |
| Web | The engine's `<video>` element, repositioned under the Compose canvas (`PlayerSurface.js.kt:75-123`) | `Crop` → `object-fit: cover`, `FillBounds` → `fill`, else `contain` | **Ignored.** |

`ContentScale.Inside`, `FillHeight`, `FillWidth` and any custom `ContentScale` all fall into the "else"
branch on all three platforms and behave as fit.

Android and iOS additionally register the surface with the engine for client-side ads: the Media3
`PlayerView` is handed over as an `AdViewProvider` (`PlayerSurface.android.kt:66`) and the
`AVPlayerViewController` as an ad container (`PlayerSurface.ios.kt:65`). Both are cleared in
`onRelease`. That is the extent of the UI module's ad involvement — see
[what the shipped controls do not do](#what-the-shipped-controls-do-not-do).

The iOS interop is created with `UIKitInteropProperties(interactionMode = null)`, i.e.
non-interactive, so every touch reaches the Compose overlay immediately with no cooperative-gesture
delay.

### Web surface

Compose Multiplatform draws the whole UI into one skiko `<canvas>`. Mounting the engine's `<video>`
above that canvas would occlude the overlay, so `PlayerSurface` does the opposite: it keeps the video
**underneath** and punches a transparent hole through the canvas over its own rect.

- The `<video>` (read from `player.nativePlayerHandle`, refreshed on
  `PlayerEvent.NativePlayerAttached`) is appended to `document.body` with `position: fixed`,
  `margin: 0`, black background and `z-index: 0`, and native `controls` off (`PlayerSurface.js.kt:81-90`).
- `raiseComposeHostsAbove` then walks every direct child of `document.body` except the video, sets
  `z-index: 1`, and adds `position: relative` to any host whose computed position is `static`
  (`PlayerSurface.js.kt:138-146`). **This rewrites page-level styles you did not author** — if your page
  has siblings alongside the Compose host, they are restyled too.
- The Compose `Box` carries
  `Modifier.drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }`
  (`PlayerSurface.js.kt:113`). Skiko presents the canvas with per-pixel alpha, so the video shows
  through, and the same shared Compose overlay composites on top — exactly as on Android and iOS, with
  no native controls.
- Layering, bottom to top: page background → `<video>` (`z-index: 0`) → Compose canvas (`z-index: 1`,
  carrying the hole) → your controls.
- The video's position is tracked in `onGloballyPositioned` and written straight to the DOM without
  touching Compose state, so scrolling does not churn recomposition. Compose pixels are divided by
  `LocalDensity.density` to get CSS pixels.
- Because the element is `position: fixed` on `document.body`, it does not clip to a scroll container
  or to any ancestor with `overflow: hidden`. It will happily paint over the rest of your page if your
  layout assumes otherwise.
- On a fatal error the surface sets `display: none` on the video (`PlayerSurface.js.kt:104-107`) so a
  frozen or garbage frame does not show through the hole, and restores it on the next good load.

Verified on Chrome and Safari.

## Controls state

```kotlin
@Composable
fun rememberPlayerControlsState(
    player: VideoPlayer,
    autoHide: Duration = 3.seconds,
): PlayerControlsState
```

`PlayerControlsState.kt:241-259`. It collects `player.state` **once** into a snapshot-backed field, so
every control in the overlay reads the same consistent frame, and it runs the auto-hide effect.

`autoHide` is captured on first composition: the state is created inside `remember(player)`
(`PlayerControlsState.kt:246`), so changing the argument later has no effect. Key it off the player, or
recreate the composable, if the duration must change.

### Derived state

| Member | Notes |
| ------ | ----- |
| `playbackState`, `isBuffering`, `isEnded`, `isPlaying` | Straight off the snapshot. `isPlaying` is "frames are advancing", so it drops during a rebuffer. |
| `showPlayIcon` | `!playWhenReady \|\| isEnded`. Bind the play/pause glyph to this, not to `isPlaying`. |
| `isWaitingToPlay` | `isBuffering && playWhenReady` — the spinner condition. Buffering while paused draws nothing. |
| `positionMs`, `durationMs`, `isLive`, `isSeekable` | `durationMs == null` is not a live signal; check `isLive`. |
| `bufferedPositionMs` | An absolute timeline position, not an amount ahead — subtract `positionMs`. On iOS the engine approximates it as `positionMs`, so it reads as zero buffer-ahead there. |
| `scrubPositionMs` | Local drag, else an in-flight seek target, else the sampled position. What the scrubber thumb should follow. |
| `isScrubbing` | True only between `onScrubMove` and `onScrubCommit`/`cancelScrub`. A programmatic seek does not count. |
| `isMuted`, `volume`, `playbackSpeed`, `error` | `volume` is unaffected by `isMuted`. |
| `audioTracks`, `textTracks`, `selectedAudioTrackId`, `selectedTextTrackId` | `selectedTextTrackId == null` means subtitles are **off**, not unknown. |
| `controlsVisible`, `keepVisible`, `interactionTick` | Overlay state; see below. |
| `player` | The player itself, for calls with no convenience here (`seekTo`, `setVolume`, track selection). Calls made straight on the player do **not** re-arm auto-hide — pair them with `notifyInteraction()`. |

Transport conveniences, each of which counts as an interaction: `playPause()` (keyed off intent, so
tapping during a rebuffer pauses), `toggleMute()`, `onScrubMove(ms)`, `onScrubCommit()`,
`cancelScrub()`.

### Visibility and the keep-visible protocol

`controlsVisible` starts `true`. Auto-hide is a single `LaunchedEffect`
(`PlayerControlsState.kt:252-257`) that fires only when **all three** hold:

```kotlin
state.controlsVisible && !state.keepVisible && !state.showPlayIcon
```

so it never runs while paused or ended, and it re-arms whenever `controlsVisible`, `keepVisible`,
`showPlayIcon` or `interactionTick` changes.

`keepVisible` is a ref-counted latch over distinct token identities:

- `keepVisible(token)` adds the token, forces `controlsVisible = true` and re-arms.
- `releaseVisible(token)` removes it. Auto-hide resumes only once **every** token is released.
- A leaked token pins the overlay open permanently. Release from the same disposal path that took it.

The shipped pattern, from `PlaybackSpeedButton` (`BasicControls.kt:199-203`) and both track buttons:

```kotlin
var open by remember { mutableStateOf(false) }
val token = remember { Any() }
LaunchedEffect(open) { if (open) state.keepVisible(token) else state.releaseVisible(token) }
DisposableEffect(Unit) { onDispose { state.releaseVisible(token) } }
```

`Scrubber` uses an internal token for the duration of a drag and self-heals on disposal
(`BasicControls.kt:107-109`), so a scrubber removed mid-drag cannot strand the latch.

Explicit `hideControls()` and `toggleControls()` always win — a held token does not veto them
(`PlayerControlsState.kt:167, 173`). That is deliberate: a tap on the video hides the overlay even with
a menu open.

## Building blocks

Each block exists twice: a **free function** taking `state` (or `state, window`), and a zero-argument
**scope member** for use inside a `controls` lambda. The one exception is `TrackList`, which exists only
as a free function and does not take a `PlayerControlsState` at all.

| Free function | Scope member | Behaviour |
| ------------- | ------------ | --------- |
| `PlayPauseButton(state, modifier)` | yes | Always enabled, even when idle, so it stays D-pad reachable; the click no-ops with no media. |
| `BigPlayButton(state, modifier)` | yes | Same behaviour, forced to a 72.dp target around a 48.dp icon — a size in your `modifier` is overridden. |
| `Scrubber(state, modifier)` | yes | **Emits nothing** when `isLive`, or when `durationMs` is null or non-positive. Drag moves the preview only; the seek is issued on release. Disabled but drawn when `!isSeekable`. |
| `TimeLabel(state, modifier, showDuration = true)` | yes (`modifier`, `showDuration`) | `mm:ss` or `h:mm:ss`; literal `LIVE` for live; `--:--` for unknown or negative. Follows the drag, not the playhead, while scrubbing. |
| `BufferingIndicator(state, modifier)` | yes | Self-gating on `isWaitingToPlay`; emits no layout when hidden. Polite live region. |
| `MuteButton(state, modifier)` | yes | Toggles the mute flag; the volume level is untouched. |
| `PlaybackSpeedButton(state, modifier, speeds = listOf(0.5f, 1f, 1.25f, 1.5f, 2f))` | yes, **without** `speeds` | Drop-down with a tick on the active rate. Use the free function to offer different rates. |
| `SubtitleButton(state, modifier)` | yes | **Self-hides** when `textTracks` is empty. Has an "Off" entry. Icon tinted with the primary colour while a track is selected. |
| `AudioButton(state, modifier)` | yes | **Self-hides** when there are fewer than two audio tracks. Deliberately no "Off" entry. |
| `FullscreenButton(state, window, modifier)` | yes | **Self-hides** when `window.onToggleFullscreen == null`. Reports the requested value; the icon flips only once the host feeds `isFullscreen` back. |
| `PipButton(state, window, modifier)` | yes | **Self-hides** unless `isPipSupported` **and** `onEnterPip != null`. Entry only — the system window owns exit. |
| `TrackList(tracks, selectedId, onSelect, modifier, onOff = null)` | **no** | Standalone selectable list for custom shells. Takes an `ImmutableList<MediaTrack>` directly; pass `onOff` to get an "Off" row. |
| — | `DefaultControls(modifier)` | Scope-only; delegates to `ControlsScaffold`. |
| — | `ControlsScaffold(modifier, top, center, bottom)` | Scope-only. See [tier 2](#tier-2--keep-the-layout-swap-one-region). |

Track labelling everywhere falls back `label` → `language` → `id`, so an unlabelled manifest still
produces a readable menu (`TrackControls.kt:40`).

All blocks take their colour from `LocalContentColor`, which `ControlsScaffold` sets to white over its
scrim. Wrap them in `CompositionLocalProvider(LocalContentColor provides …)` to retheme.

## Input model

All of this lives on `PlayerView` (`PlayerView.kt:100-165`). Tier 4 gets none of it.

**Touch.** A tap toggles the overlay (`state.toggleControls()`) and nothing else. Touch never
plays or pauses through the surface.

**Mouse.** A single click calls `playPause()` and `showControls()` **immediately** — no double-click
lag. Then, only if `window.onToggleFullscreen` is non-null, it waits
`viewConfiguration.doubleTapTimeoutMillis` for a second press; if one arrives it undoes the first
`playPause()` and calls `onToggleFullscreen(!window.isFullscreen)`, so a double-click leaves playback
state unchanged and toggles fullscreen. With no fullscreen handler wired there is no double-click
behaviour at all.

Both gestures use `awaitFirstDown()`, which ignores presses already consumed by a control, so clicking
a button does only that.

**Hover (mouse and stylus).** `Enter` and button-less `Move` mark the pointer as over the player and
either show the controls or re-arm auto-hide, throttled to one re-arm per 250 ms. `Exit` clears the
flag and hides the controls unless a keep-visible token is held. Observed without consuming, so
clicking and scrubbing still work; a no-op on touch, where these events do not fire.

**Any key.** `onPreviewKeyEvent` on the root: a key-down re-arms auto-hide if the controls are visible,
or shows them if they are not. It never consumes the event.

**Web keyboard shortcuts.** Web is the only platform with an implementation; Android and iOS have
deliberately empty actuals. A DOM `keydown` listener on `document` (`PlayerKeyboard.js.kt:28-56`)
handles:

| Key | Action |
| --- | ------ |
| Space or `K` | `playPause()` |
| `M` | `toggleMute()` |
| `F` | `onToggleFullscreen(!isFullscreen)`, only if the callback is non-null |

Gated on the pointer being over the player, so the page keeps its own Space-to-scroll behaviour
elsewhere. Combos with Alt, Ctrl or Meta are left to the browser. A handled key calls
`preventDefault()` and shows the controls. The listener sits on `document` rather than Compose's focus
system because key routing through the skiko canvas is unreliable, Safari especially.

## TV and D-pad

The shipped controls are focusable `IconButton`s carrying an internal white 2.dp circular focus ring,
drawn inside the bounds so it never affects layout (`FocusRing.kt:25-38`). Nothing else is required to
support TV — the same `PlayerView` works on phone, tablet and TV.

**Auto-hide is not suspended by focus.** This is the correction most worth reading: an earlier version
of this SDK vetoed hiding while a control held focus, and that veto was removed as "the old TV
deadlock" (`PlayerControlsState.kt:37-38`). Today:

- Focus movement bumps `interactionTick` (`DefaultControls.kt:82`), which **re-arms** the countdown; it
  never pins the overlay.
- While actively playing, the overlay hides after `autoHide` whether or not a control has focus.
- The next key press brings it straight back, via the `onPreviewKeyEvent` wake above.

Design against that, not against a permanently visible focused overlay.

`ControlsScaffold` grabs D-pad focus when the overlay appears: the `focusRequester` is on the root,
which is a `focusGroup()`, so focus forwards to the first focusable child and the grab survives you
overriding `bottom` (`DefaultControls.kt:79-80, 98-100`). It is wrapped in `runCatching`, so a
requester with nothing to focus fails quietly.

`PlayerControlsScope.focusRequester` is exposed so a custom overlay can do the same. Point it at an
**enabled** control — a disabled one swallows focus and strands the user.

## Fullscreen and picture-in-picture

The library never owns the Activity, so it delegates through an immutable `PlayerWindow`
(`PlayerWindow.kt:24-44`):

```kotlin
@Immutable
data class PlayerWindow(
    val isFullscreen: Boolean = false,
    val isInPip: Boolean = false,
    val isPipSupported: Boolean = false,
    val onToggleFullscreen: ((Boolean) -> Unit)? = null,
    val onEnterPip: (() -> Boolean)? = null,
    val onExitPip: (() -> Unit)? = null,
)
```

The host is always the source of truth: the SDK reports a requested value and picks an icon, and the OS
can change `isInPip` out of band (home gesture, the user dismissing the PiP window), so keep it in sync
from platform callbacks rather than only from your own `onEnterPip`.

```kotlin
var fullscreen by remember { mutableStateOf(false) }
PlayerView(
    player,
    window = PlayerWindow(
        isFullscreen = fullscreen,
        onToggleFullscreen = { fullscreen = it },
    ),
)
```

Note that `onToggleFullscreen` also arms mouse double-click-to-fullscreen, not just the button.

While `isInPip` is true, `PlayerView` skips the controls lambda entirely and shows bare video.

### Android: `rememberPlayerWindow`

Android is the only platform with a ready-made implementation (`PlayerWindow.android.kt:47-89`); it
lives in `androidMain`, so it is not callable from `commonMain`.

```kotlin
@Composable
fun rememberPlayerWindow(
    player: VideoPlayer,
    onToggleFullscreen: ((Boolean) -> Unit)? = null,
    onPipUnavailable: (() -> Unit)? = null,
): PlayerWindow
```

- `isPipSupported` requires API 26+, the `FEATURE_PICTURE_IN_PICTURE` system feature, **and** an AppOps
  check — `MODE_ALLOWED` or `MODE_DEFAULT`. A revoked permission hides the button rather than leaving a
  dead one.
- `onEnterPip` builds `PictureInPictureParams` with the video's aspect ratio, but only when
  `player.state.value.videoSize` is non-null and the ratio falls inside Android's legal
  1:2.39 … 2.39:1 band; outside it, PiP is entered without an explicit ratio rather than throwing.
- It honours the `Boolean` returned by `enterPictureInPictureMode` — the system silently declines in
  multi-window and under some OEM policies — and calls `onPipUnavailable` on a decline, on
  API < 26, and on `IllegalStateException`/`IllegalArgumentException`. Use it to show a toast or hide
  your own affordance; nothing else in the SDK surfaces the failure.
- `isInPip` is tracked from the Activity lifecycle via a `LifecycleEventObserver`.
- Fullscreen state is tracked locally and forwarded to your callback.

The host must declare this on its Activity — a library cannot inject it:

```xml
<activity
    android:name=".MainActivity"
    android:supportsPictureInPicture="true"
    android:configChanges="orientation|screenLayout|screenSize|smallestScreenSize|keyboardHidden" />
```

On iOS and web there is no `rememberPlayerWindow`: implement the same callbacks yourself against
`AVPictureInPictureController`, or `requestPictureInPicture()` / `requestFullscreen()`.

## Background and lifecycle

`PlayerView(pauseWhenStopped = true)` is the default and the **only** lifecycle handling anywhere in
the SDK — `:core` has none. When enabled (`PlayerView.kt:78-90`):

- On `Lifecycle.Event.ON_STOP` it records `player.state.value.playWhenReady` and calls `player.pause()`.
- On `Lifecycle.Event.ON_START` it calls `player.play()` if it had been playing.

These are event effects rather than start/stop-or-dispose effects deliberately, so moving the player to
another `PlayerView` — toggling fullscreen, for instance — does not pause it.

On Android, `ON_STOP` is the app being backgrounded or the screen locking, and it does **not** fire
while in PiP, so PiP keeps playing. On iOS and web the trigger is whatever the Compose Multiplatform
lifecycle owner reports; do not assume the Android semantics transfer exactly.

**Background playback requires `pauseWhenStopped = false`.** There is no other switch, and at tier 4
the behaviour does not exist at all. If you turn it off you own the whole problem: audio focus,
notifications, and (on Android) a foreground service.

The player's own lifetime is still yours. `release()` is idempotent; call it when the player leaves
your scope, not when the surface does.

## What the shipped controls do not do

Stated plainly so nobody designs around a feature that is not there.

**No ad awareness whatsoever.** A grep for `adState`, `AdState`, `isInAdBreak` or `cuePoint` across
`ui/src` returns nothing. The scrubber is **not** locked during an ad break, no ad countdown or skip
button is drawn, and cue-point markers are not rendered. `PlayerState.adState` is populated by the
Android and web engines, and `AdController` publishes its own state for server-side ads, but the
overlay never reads either. If you monetise, you gate seeking and draw ad UI yourself — tier 3 exists
for this. The module's entire ad involvement is handing the platform surface to the engine so IMA has
somewhere to render (see [`PlayerSurface`](#playersurface)).

**No chapter UI.** `PlayerState.chapters` and `MediaItem.chapterThumbnails` have no consumer in
`ui/src`. Chapter markers, a chapter list and thumbnail previews are all yours to build, including
decoding the thumbnail bytes. See [local files and chapters](local-files.md).

**No video-quality selector.** `SubtitleButton` and `AudioButton` cover text and audio tracks only.
`PlayerState.currentTrack` / `availableTracks` are reserved and never written by any engine, so there is
nothing for a quality menu to bind to. Constrain renditions through `MediaItem.videoConstraints`
instead.

**No volume slider.** `MuteButton` toggles the flag; `state.volume` is exposed but no shipped control
sets it. Call `state.player.setVolume(…)` and pair it with `state.notifyInteraction()`.

**No skip-forward/back buttons, no error UI, no thumbnail scrub preview.**

**`surfaceType` does nothing** on any platform, as noted under [`PlayerView`](#playerview).

**No UI test target.** `:ui` has no test source set. The overlay and controls have been exercised by
compilation, review and manual use; full rendering, D-pad navigation and PiP are best confirmed on a
real device or TV.

---

**See also:** [Playback guide](playback.md) · [Platform support](platform-support.md) ·
[Getting started on Android](getting-started-android.md) · [Troubleshooting](troubleshooting.md)
