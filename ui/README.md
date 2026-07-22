# `:ui`

Compose Multiplatform surface + a **fully customisable** controls overlay for the KMP video player.
Runs on **Android, iOS and Web** (JS). Works for touch **and** D-pad/TV.

> The whole API is `@ExperimentalPlayerUiApi` (opt in at the call site).

## Customisation tiers

```kotlin
// 1) Batteries included
PlayerView(player)

// 2) Keep the layout, swap a region
PlayerView(player, controls = {
    ControlsScaffold(bottom = { Scrubber(Modifier.weight(1f)); TimeLabel() })
})

// 3) Arbitrary overlay — compose any layout from building blocks (receiver = PlayerControlsScope)
PlayerView(player) {
    Box(Modifier.fillMaxSize()) {
        if (state.isWaitingToPlay) BufferingIndicator(Modifier.align(Alignment.Center))
        Column(Modifier.align(Alignment.BottomCenter)) {
            Row { TimeLabel(); Spacer(Modifier.weight(1f)); SubtitleButton(); AudioButton(); FullscreenButton() }
            Scrubber()
        }
    }
}

// 4) Controls BESIDE the video (e.g. a TV left rail) — surface + building blocks, no overlay
@Composable fun MyShell(player: VideoPlayer) {
    val state = rememberPlayerControlsState(player)
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(280.dp)) { PlayPauseButton(state); Scrubber(state); SubtitleButton(state); AudioButton(state) }
        PlayerSurface(player, Modifier.weight(1f))
    }
}
```

`DefaultControls()` is written entirely against the public scope — copy/fork it as a starting point.

## Building blocks

Each exists twice: a **scope member** (zero-arg, inside the `controls` lambda) and a **free function**
taking `state` (or `state, window`) for use anywhere. `PlayPauseButton`, `BigPlayButton`, `Scrubber`,
`TimeLabel`, `BufferingIndicator`, `MuteButton`, `PlaybackSpeedButton`, `SubtitleButton`, `AudioButton`,
`FullscreenButton`, `PipButton`, `TrackList`, plus `DefaultControls` / `ControlsScaffold`. Controls
self-hide when irrelevant (no text tracks → no subtitle button; <2 audio tracks → no audio button;
no fullscreen/PIP handler → those buttons vanish).

## State

`rememberPlayerControlsState(player)` collects `player.state` **once** and exposes UI-ready
derivations (`showPlayIcon` binds to *intent*, `scrubPositionMs` is anti-rubber-band, …) plus the
overlay's own visibility/scrub state. Auto-hide is suspended while paused, while a menu is open, while
scrubbing, or while a control holds focus (TV).

## TV / D-pad

Controls are focusable `IconButton`s; the overlay grabs focus on appear and stays visible while
focused. No extra setup — the same `PlayerView` works on phone, tablet and TV.

## Fullscreen / PIP

The library never owns the Activity, so it delegates via `PlayerWindow`
(`onToggleFullscreen` / `onEnterPip` / `onExitPip`); buttons self-hide when a handler/capability is
absent. On Android, `rememberPlayerWindow(player, onToggleFullscreen = …)` wires it to the Activity
(PIP availability incl. AppOps, aspect-ratio, live PIP-mode tracking).

**The host must declare on its Activity (a library can't inject these):**

```xml
<activity
    android:name=".MainActivity"
    android:supportsPictureInPicture="true"
    android:configChanges="orientation|screenLayout|screenSize|smallestScreenSize|keyboardHidden" />
```

## Surface

`PlayerSurface` is **public** (so it composes anywhere — overlay, rail, mini-player), with a
per-platform actual:

- **Android** — Media3's `PlayerView` (`useController = false`); renders the video **and selected
  subtitle cues** natively.
- **iOS** — `AVPlayerViewController` hosted via `UIKitViewController`.
- **Web** — Compose draws the UI into one `<canvas>` (skiko). The surface keeps the engine's `<video>`
  **underneath** the canvas and punches a transparent hole through the canvas over its own rect
  (`Modifier.drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }` — skiko presents
  the canvas with per-pixel alpha, so the video shows through). The **same shared Compose overlay
  composites on top of the video**, exactly as on Android/iOS — no native controls, no occlusion.
  Layering (bottom→top): page bg → `<video>` (`z-index: 0`) → Compose canvas (`z-index: 1`) → controls.
  Verified on Chrome + Safari. (Caveat: a *progressive* MP4 plays via the native `<video>`, which can't
  take request headers — see [`:core` known gaps](../core/README.md#known-platform-gaps).)

## Status

Runs on Android, iOS and Web. The overlay + controls are validated by compilation and review; full
rendering, D-pad navigation and PiP are best verified on a real device/TV (there is no runnable UI
test target in this module yet).
