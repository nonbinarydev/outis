# Playback implementation guide

How to drive standard playback with Outis — loading media, transport, observing state,
wiring the UI, tracks, live, quality caps and errors. For protected content, see [drm.md](drm.md).

All core types live in `dev.nonbinary.outis.core` (`…core.source`, `…core.track`).

---

## 1. Create and release a player

```kotlin
val player: VideoPlayer = VideoPlayer(appContext)                 // main thread
// …or with construction-time config:
val player = VideoPlayer(appContext, PlayerConfig(initialVolume = 0.8f))

player.release()                                                  // idempotent — always call it
```

`PlayerConfig` is small and additive:

| Field | Default | Meaning |
|---|---|---|
| `initialVolume: Float` | `1f` | Starting volume, `0f`–`1f`. |
| `positionPollIntervalMs: Long` | `250` | How often position is sampled (no engine streams it continuously). |
| `bufferConfig: BufferConfig?` | `null` | Buffering / load-control tuning (see below). `null` keeps each engine's stock behaviour. |
| `initialBitrateBps: Int?` | `null` | Seed the ABR bandwidth estimate (bits/sec) so the first variant on a cold start isn't a blind guess. Android + Web; no-op on iOS. |
| `retryConfig: RetryConfig?` | `null` | Network connect/read timeouts + retry count, back-off, cross-protocol redirects. Android + Web; no-op on iOS. |
| `audioConfig: AudioConfig?` | `null` | Audio focus / become-noisy (Android) + mix-with-others (iOS `AVAudioSession`). No-op on Web. |
| `liveConfig: LiveConfig?` | `null` | Live tuning — target latency + catch-up speed (Android), target offset (iOS), low-latency mode (Web). |
| `components: List<PlayerComponent>` | `[]` | Analytics/QoS extensions registered at construction ([§9](#9-analytics--qos-seam)). |

**`BufferConfig`** (field names + defaults mirror Media3's `DefaultLoadControl`):

```kotlin
VideoPlayer(appContext, PlayerConfig(
    // Deeper buffers for a flaky network / low-end TV:
    bufferConfig = BufferConfig(minBufferMs = 30_000, maxBufferMs = 120_000, backBufferMs = 30_000),
    initialBitrateBps = 4_000_000, // assume ~4 Mbps until measured
))
```

- **Android (Media3)** honours every field. **Web (Shaka)** maps `maxBufferMs`→`bufferingGoal`,
  `bufferForPlaybackMs`→`rebufferingGoal`, `backBufferMs`→`bufferBehind` — note the Media3-shaped
  *defaults* aren't Shaka's own (Shaka's stock `bufferBehind` is ~30s), so set fields you care about
  deliberately. **iOS (AVPlayer)** maps only `maxBufferMs`→`preferredForwardBufferDuration`; the rest
  are no-ops.

With Compose, tie creation/release to composition:

```kotlin
val player = remember { VideoPlayer(appContext) }
DisposableEffect(player) { onDispose { player.release() } }
```

---

## 2. Load media

```kotlin
player.setMediaItem(item, autoPlay = true)   // prepare is folded in; starts when ready if autoPlay
```

A `MediaItem` describes **what** to play:

```kotlin
data class MediaItem(
    val source: MediaSource,                 // required
    val mimeType: MimeType? = null,          // null => infer from the URL extension
    val headers: ImmutableMap<String, String> = persistentMapOf(),
    val drmConfig: DrmConfig? = null,        // see drm.md
    val videoConstraints: VideoConstraints? = null,  // see §7
    // ----- load-time defaults (applied before first frame) -----
    val preferredAudioLanguage: String? = null,      // BCP-47, e.g. "es"
    val preferredTextLanguage: String? = null,       // BCP-47, e.g. "en"
    val captionsDefault: CaptionsDefaultMode = CaptionsDefaultMode.OFF,  // OFF / ON / FOLLOW_SYSTEM
    val startPositionMs: Long? = null,       // resume / deep-link; null or 0 => start at the beginning
    val startMuted: Boolean = false,         // start muted (autoplay-muted feeds; required for Web autoplay)
    val loop: Boolean = false,               // restart from the beginning at end (reels/feeds); ignored for live
    val metadata: MediaMetadata? = null,     // title/subtitle/artwork for overlays & OS media sessions
)
```

- **`MediaSource`** is sealed: `MediaSource.Url("https://…")` (progressive MP4 / HLS / DASH) or
  `MediaSource.LocalFile("/path")` (reserved for offline).
- **`MimeType`** is `MP4`, `HLS` or `DASH`. Leave it `null` to detect from the extension (`.m3u8` →
  HLS, `.mpd` → DASH, `.mp4`/`.webm` → progressive); set it explicitly for extension-less or signed
  URLs.
- **`headers`** are applied to manifest/segment requests (auth tokens, etc.) on **Android, iOS and
  Web**. (Web caveat: a *progressive* MP4/WebM plays through the browser's native `<video>`, which
  can't take custom request headers; headers apply to Shaka-managed HLS/DASH there.)
- **Load-time defaults** are applied as the item prepares, so the right track / position / mute state
  is in place before the first frame (no reactive flip after playback starts):
  - **`preferredAudioLanguage` / `preferredTextLanguage`** — BCP-47 tags that pre-select the audio /
    subtitle track, instead of waiting for the track lists then calling `selectTrack`.
  - **`captionsDefault`** — `OFF` (default), `ON`, or `FOLLOW_SYSTEM` (the OS caption setting; treated
    as `OFF` on Web, which has no universal caption-accessibility signal).
  - **`startPositionMs`** — begin at a content-time offset (continue-watching / deep-link).
  - **`startMuted`** — begin muted; only ever forces mute *on*, never unmutes. Browsers block unmuted
    autoplay, so this is effectively required for autoplaying feeds on Web.
  - **`loop`** — restart the item from the beginning when it ends (reels/feeds). Media3 repeat-one,
    AVPlayer seek-on-end, Web `<video>.loop`; ignored for live.

  > **Web caveat:** the language/caption preferences apply to Shaka-managed **HLS/DASH** only — a
  > *progressive* MP4/WebM is a single native `<video>` stream with no selectable audio/text tracks
  > (`startPositionMs` and `startMuted` still work). On iOS, `captionsDefault = FOLLOW_SYSTEM` leaves
  > AVPlayer's automatic selection (which already honours the OS caption setting).

```kotlin
// HLS with an auth header:
player.setMediaItem(
    MediaItem(
        source = MediaSource.Url("https://cdn.example.com/live/master.m3u8"),
        mimeType = MimeType.HLS,
        headers = persistentMapOf("Authorization" to "Bearer $token"),
        metadata = MediaMetadata(title = "Channel 1", subtitle = "Live"),
    ),
    autoPlay = true,
)
```

> **Platform routing.** On Web, HLS/DASH go through Shaka (MSE); a progressive MP4/WebM plays via the
> native `<video>` element. On iOS, AVPlayer plays HLS + progressive only (no DASH; and HLS must be
> `avc1`, not in-band-parameter-set `avc3`). See the [capability matrix](../README.md#capability-matrix).

---

## 3. Transport

All transport is **fire-and-forget** — it requests a change and returns immediately; the result lands
on `state`/`events`.

```kotlin
player.play()
player.pause()
player.seekTo(positionMs = 45_000)     // content time, ms
player.setPlaybackSpeed(1.5f)
player.setVolume(0.5f)                  // 0f–1f
player.setMuted(true)
player.stop()                           // clears the item, keeps the player reusable
```

---

## 4. Observe state and events

Two flows, two jobs:

### `state: StateFlow<PlayerState>` — render the UI

A conflated, always-readable snapshot. Collect it and render:

```kotlin
scope.launch {
    player.state.collect { s ->
        progressBar.max = (s.durationMs ?: 0).toInt()
        progressBar.progress = (s.pendingSeekTargetMs ?: s.positionMs).toInt()
        playPauseIcon = if (s.playWhenReady) Icon.Pause else Icon.Play
        spinner.isVisible = s.playbackState == PlaybackState.BUFFERING && s.playWhenReady
        errorBanner.text = s.error?.message
    }
}
```

Key `PlayerState` fields:

| Field | Notes |
|---|---|
| `playbackState` | `IDLE` / `BUFFERING` / `READY` / `ENDED`. |
| `isPlaying` | Frames are actually advancing **now**. |
| `playWhenReady` | Play **intent** — bind the play/pause icon to this, not `isPlaying`. |
| `positionMs`, `bufferedPositionMs` | Content time, ms. |
| `durationMs: Long?` | `null` = unknown/unresolved. **Never** used to signal live. |
| `isLive` | Use this for live, not a null duration. See [§6](#6-live-streams). |
| `isSeekable` | |
| `pendingSeekTargetMs: Long?` | The target of an in-flight seek — show it on the scrubber to avoid rubber-banding. |
| `playbackSpeed`, `volume`, `isMuted` | |
| `videoSize: VideoSize?` | `width` × `height` once known. |
| `mediaItem: MediaItem?` | What's loaded (so late subscribers read "what's playing" without replaying events). |
| `error: PlayerError?` | Non-null after a fatal error; cleared on the next `setMediaItem`. See [§8](#8-error-handling--recovery). |
| `audioTracks`, `textTracks`, `selectedAudioTrackId`, `selectedTextTrackId` | See [§5](#5-track-selection-audio--subtitles). |

### `events: SharedFlow<PlayerEvent>` — analytics / one-shot reactions

Timed, one-shot, no replay. **Every** event carries `positionMs` (content time) and a monotonic
`elapsedRealtimeMs` — use *deltas* for startup time, rebuffer duration, seek latency.

```kotlin
scope.launch {
    player.events.collect { e ->
        when (e) {
            is PlayerEvent.FirstFrameRendered -> trackStartup(e.elapsedRealtimeMs - playClickedAt)
            is PlayerEvent.BufferingStarted   -> rebufferStartedAt = e.elapsedRealtimeMs
            is PlayerEvent.BufferingEnded     -> trackRebuffer(e.elapsedRealtimeMs - rebufferStartedAt)
            is PlayerEvent.FatalError         -> report(e.error)
            is PlayerEvent.Ended              -> onComplete()
            else -> {}
        }
    }
}
```

The event types: `BufferingStarted/Ended`, `SeekStarted(targetMs)/SeekCompleted`, `FirstFrameRendered`,
`MediaItemTransition(item)`, `PlaybackStateChanged(state)`, `IsPlayingChanged(isPlaying)`, `Ended`,
`FatalError(error)`, `PlaybackRecovered(reason)`, `TracksChanged(audioTracks, textTracks)`,
`NativePlayerAttached(handle)`, plus the QoS signals `BitrateChanged(format)` /
`BandwidthSample(bitsPerSecond)` / `DroppedFrames(count)`.

> `PlaybackRecovered(reason)` (`BEHIND_LIVE_WINDOW` | `STALL`) fires when the engine **auto-recovered**
> from a transient fault instead of surfacing a `FatalError` — playback continues. Android re-snaps to
> the live edge on `BEHIND_LIVE_WINDOW`; iOS nudges a wedged player after a long stall (Android/Web
> engines self-recover). Surface a brief "reconnecting…" hint, or feed it to QoS.

> **Rule of thumb:** drive **UI** off `state` (idempotent, conflated); drive **analytics and one-shot
> side-effects** off `events` (each fires exactly once, timestamped).

---

## 5. Track selection (audio & subtitles)

When a manifest exposes alternative audio or subtitle tracks, they appear on
`state.audioTracks` / `state.textTracks` once the item is ready (and on `PlayerEvent.TracksChanged`):

```kotlin
data class MediaTrack(
    val id: String,              // opaque, engine-stable; valid only for the loaded item
    val type: TrackType,         // AUDIO / TEXT / VIDEO
    val label: String? = null,   // human-readable, if the manifest has one
    val language: String? = null,// BCP-47 ("en", "pt-BR")
    val isSelected: Boolean = false,
    val isDefault: Boolean = false,
)
```

Select fire-and-forget; the engine applies it and re-emits the lists — **don't** assume the change
optimistically, read it back from `state`:

```kotlin
val french = player.state.value.audioTracks.firstOrNull { it.language == "fr" }
french?.let { player.selectTrack(it) }

val englishSubs = player.state.value.textTracks.firstOrNull { it.language == "en" }
englishSubs?.let { player.selectTrack(it) }

player.clearTextTrack()           // subtitles OFF (selectedTextTrackId becomes null)
```

`selectedTextTrackId == null` means subtitles **off** (not "unknown"). Subtitle *cue rendering* is a
surface concern — the `:ui` `PlayerSurface` renders selected cues natively (and the overlay's
`SubtitleButton` / `AudioButton` drive the above for you).

---

## 6. Live streams

Live is detected automatically (Android `isCurrentMediaItemLive`, Web `duration == ∞`, iOS = an
indefinite duration once the item is *ready*) and surfaced as `state.isLive == true` with a `null`
`durationMs`. Drive your UI off `isLive`, **not** a null duration (a VOD whose duration hasn't resolved
also has `null`). The `:ui` overlay already does this — it shows
a **"LIVE"** label and hides the scrubber.

> iOS leaves `isLive == false` (reliable live detection from AVPlayer is deferred) — fine for HLS live,
> which still plays; just don't depend on `isLive` on iOS yet.

---

## 7. Capping quality (`VideoConstraints`)

Cap adaptive (HLS/DASH) selection per item — for data saving, or to steer ABR away from a rung a
device rejects:

```kotlin
MediaItem(
    source = MediaSource.Url("https://…/master.m3u8"),
    mimeType = MimeType.HLS,
    videoConstraints = VideoConstraints(maxWidth = 1280, maxHeight = 720, maxBitrateBps = 4_000_000),
)
```

`null` fields are unbounded. Resolution caps need **both** `maxWidth` and `maxHeight` (Media3 and
AVPlayer take a size, not a single dimension). Mapped to `TrackSelectionParameters` (Android),
`AVPlayerItem.preferredMaximumResolution`/`preferredPeakBitRate` (iOS) and Shaka `restrictions` (Web);
each engine resets the cap per item, so it never leaks to the next source.

---

## 8. Error handling & recovery

A fatal error sets `state.error` (a `PlayerError`) and emits `PlayerEvent.FatalError`. The player stays
usable: the **next `setMediaItem` clears the error** and starts fresh — so "switch to another source"
is the recovery path.

```kotlin
data class PlayerError(
    val category: Category,        // SOURCE / NETWORK / DRM / DECODER / RENDERER / UNKNOWN
    val code: String? = null,      // engine/OS code (e.g. a CoreMedia code on iOS)
    val message: String? = null,
    val nativeCause: Any? = null,  // the original ExoPlaybackException / NSError / Shaka error
)
```

```kotlin
player.state.collect { s ->
    val err = s.error
    errorOverlay.isVisible = err != null
    errorOverlay.text = err?.let { "${it.category}: ${it.message ?: "Playback failed"}" }
}
// recover by loading something else:
retryButton.onClick { player.setMediaItem(currentItem, autoPlay = true) }
```

`category == DRM` fires on key-system/license failures (Android + Web; iOS surfaces decode/source
errors). The iOS engine includes the underlying `CoreMediaErrorDomain` code in `message`, which is what
actually pinpoints AVPlayer failures.

---

## 9. Analytics / QoS seam

Attach an extension via `PlayerComponent` — it gets a read-only `PlayerHost` (state, events, a
lifecycle `scope`, and an **observable** `nativePlayerHandle` flow so SDKs that bind to the concrete
native player re-bind when it's recreated):

```kotlin
class MyAnalytics : PlayerComponent {
    override fun attach(host: PlayerHost) {
        host.scope.launch { host.events.collect { /* … */ } }
        host.scope.launch { host.nativePlayerHandle.collect { native -> rebind(native) } }
    }
    override fun detach() { /* tear down */ }
}

val player = VideoPlayer(appContext, PlayerConfig(components = listOf(MyAnalytics())))
// or later: player.addComponent(MyAnalytics())
```

For SDKs that must touch the concrete engine directly, `player.nativePlayerHandle` is the escape hatch
(`ExoPlayer` / `AVPlayer` / `shaka.Player`, or `null` before it exists / after `release()`).

---

## 10. The Compose UI (`:ui`)

The whole UI API is `@ExperimentalPlayerUiApi` (opt in at the call site). Four tiers, from batteries-
included to fully custom:

```kotlin
// 1) Batteries included
PlayerView(player)

// 2) Keep the layout, swap one region
PlayerView(player, controls = {
    ControlsScaffold(bottom = { Scrubber(Modifier.weight(1f)); TimeLabel() })
})

// 3) Arbitrary overlay — compose from building blocks (receiver = PlayerControlsScope)
PlayerView(player) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.BottomCenter)) {
            Row { TimeLabel(); Spacer(Modifier.weight(1f)); SubtitleButton(); AudioButton(); FullscreenButton() }
            Scrubber()
        }
    }
}

// 4) Controls BESIDE the video (e.g. a TV left rail) — surface + building blocks, no overlay
val state = rememberPlayerControlsState(player)
Row {
    Column(Modifier.width(280.dp)) { PlayPauseButton(state); Scrubber(state); SubtitleButton(state) }
    PlayerSurface(player, Modifier.weight(1f))
}
```

Building blocks: `PlayPauseButton`, `BigPlayButton`, `Scrubber`, `TimeLabel`, `BufferingIndicator`,
`MuteButton`, `PlaybackSpeedButton`, `SubtitleButton`, `AudioButton`, `FullscreenButton`, `PipButton`,
`TrackList`, plus `DefaultControls` / `ControlsScaffold`. They **self-hide** when irrelevant (no text
tracks → no subtitle button; <2 audio tracks → no audio button; no fullscreen/PiP handler → those
buttons vanish). Works on touch **and** D-pad/TV. Full detail in the [`:ui` README](../ui/README.md).

### Fullscreen / PiP

The library never owns the Activity, so it delegates through `PlayerWindow`:

```kotlin
data class PlayerWindow(
    val isFullscreen: Boolean = false,
    val isInPip: Boolean = false,
    val isPipSupported: Boolean = false,
    val onToggleFullscreen: ((Boolean) -> Unit)? = null,
    val onEnterPip: (() -> Boolean)? = null,
    val onExitPip: (() -> Unit)? = null,
)

PlayerView(player, window = PlayerWindow(isFullscreen = fullscreen, onToggleFullscreen = { fullscreen = it }))
```

Buttons self-hide when a handler/capability is absent. On Android, `rememberPlayerWindow(player,
onToggleFullscreen = …)` wires PiP to the Activity. The host **must** declare on its Activity:

```xml
<activity android:name=".MainActivity"
    android:supportsPictureInPicture="true"
    android:configChanges="orientation|screenLayout|screenSize|smallestScreenSize|keyboardHidden" />
```

### <a id="web-surface"></a>Web surface note

Compose Multiplatform draws the UI into one `<canvas>` (skiko). On Web the `PlayerSurface` keeps the
engine's `<video>` **underneath** the canvas (`z-index: 0`, tracking the surface's bounds) and punches a
transparent hole through the canvas over its own rect
(`Modifier.drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }` — skiko presents the
canvas with per-pixel alpha, so the video shows through). The Compose canvas is raised above the video
(`z-index: 1`), so the **same shared Compose overlay you pass composites on top of the video** — exactly
as on Android/iOS, with **no native controls**. Verified on Chrome + Safari. The surface hides the
`<video>` on a fatal error so your Compose error UI paints over a clean background.
