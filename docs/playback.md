[Outis](../README.md) › [Docs](README.md) › Playback

# Playback guide

How to drive standard playback with Outis: creating a player, loading media, transport, observing
state, selecting tracks, live streams, quality caps and errors — in that order, because that is the
order you will write them in. For protected content see [drm.md](drm.md); for the Compose chrome see
[ui.md](ui.md); for analytics adapters see [analytics.md](analytics.md).

All core types live in `dev.nonbinary.outis.core` (plus `…core.source`, `…core.track`,
`…core.chapters`, `…core.ads`).

---

## 1. Create and release a player

`VideoPlayer(...)` is a function that reads like a constructor. **Call it on the main thread** — every
engine is main-thread affine at construction. Once it exists, its methods are safe to call from any
thread; each engine marshals internally.

```kotlin
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.PlayerConfig
import dev.nonbinary.outis.core.VideoPlayer

val player = VideoPlayer(appContext)                            // main thread
// …or with construction-time configuration:
val configured = VideoPlayer(appContext, PlayerConfig(initialVolume = 0.8f))

player.release()                                                // idempotent — always call it
```

With Compose, tie creation and release to composition instead:

```kotlin
val player = remember { VideoPlayer(appContext) }
DisposableEffect(player) { onDispose { player.release() } }
```

### What `appContext` is

`AppContext` is the one genuinely platform-specific thing you must build. On Android it is
`AppContext(context.applicationContext)` — the process-wide context, never an Activity, which the
player would outlive and leak. On iOS and Web it is the empty `AppContext()`. Build it in your
platform source set and pass it into shared code. On the `jvm` and `wasmJs` targets `:core` compiles
but has no engine, so construction throws `UnsupportedOperationException` —
see [platform-support.md](platform-support.md).

### `PlayerConfig`

Small and additive; every field has a default.

| Field | Default | Meaning |
|---|---|---|
| `initialVolume: Float` | `1f` | Starting volume, `0f`–`1f`. The player's own gain, not the system volume. |
| `positionPollIntervalMs: Long` | `250` | How often position is sampled — no engine streams progress continuously. |
| `bufferConfig: BufferConfig?` | `null` | Buffering / load-control tuning (below). `null` keeps each engine's stock behaviour. |
| `initialBitrateBps: Int?` | `null` | Seed the ABR bandwidth estimate (bits/sec) so the first variant on a cold start isn't a blind guess. Android + Web; **no-op on iOS**. |
| `retryConfig: RetryConfig?` | `null` | Connect/read timeouts, retry count, back-off, cross-protocol redirects. Android + Web; **no-op on iOS**. |
| `audioConfig: AudioConfig?` | `null` | Audio focus + become-noisy (Android), mix-with-others (iOS `AVAudioSession`). **No-op on Web**. |
| `liveConfig: LiveConfig?` | `null` | Live tuning — target offset + catch-up speed (Android), target offset (iOS), low-latency mode (Web). |
| `components: List<PlayerComponent>` | `[]` | Extensions registered at construction — see [analytics.md](analytics.md). |

### `BufferConfig`

Field names and defaults mirror Media3's `DefaultLoadControl`: `minBufferMs = 50_000`,
`maxBufferMs = 50_000`, `bufferForPlaybackMs = 2_500`,
`bufferForPlaybackAfterRebufferMs = 5_000`, `backBufferMs = 0`.

```kotlin
import dev.nonbinary.outis.core.BufferConfig

// Deeper buffers for a flaky network or a low-end TV:
val player = VideoPlayer(
    appContext,
    PlayerConfig(
        bufferConfig = BufferConfig(minBufferMs = 30_000, maxBufferMs = 120_000, backBufferMs = 30_000),
        initialBitrateBps = 4_000_000, // assume ~4 Mbps until measured
    ),
)
```

**Android (Media3)** honours every field. **Web (Shaka)** maps `maxBufferMs`→`bufferingGoal`,
`bufferForPlaybackMs`→`rebufferingGoal`, `backBufferMs`→`bufferBehind` — but the Media3-shaped
*defaults* are not Shaka's own (its stock `bufferBehind` is around 30s, here `0`), so set the fields
you care about deliberately. **iOS (AVPlayer)** maps only
`maxBufferMs`→`preferredForwardBufferDuration`; the rest are no-ops.

> **`BufferConfig` validates in its `init` block and throws `IllegalArgumentException`.** The rule is
> `0 ≤ bufferForPlayback*Ms ≤ minBufferMs ≤ maxBufferMs`, with `backBufferMs ≥ 0`. This bites on
> *partial* overrides that look harmless: `BufferConfig(minBufferMs = 2_000)` throws, because the
> default `bufferForPlaybackMs` of `2_500` is now above `minBufferMs`. The check is deliberate — it
> fails at your call site rather than deep inside ExoPlayer construction.

---

## 2. Load media

```kotlin
player.setMediaItem(item, autoPlay = true)   // prepare is folded in; starts when ready if autoPlay
```

A `MediaItem` describes **what** to play. All fourteen parameters, in declaration order:

```kotlin
data class MediaItem(
    val source: MediaSource,                                            // required
    val mimeType: MimeType? = null,                                     // null => infer from the URL extension
    val headers: ImmutableMap<String, String> = persistentMapOf(),
    val drmConfig: DrmConfig? = null,                                   // see drm.md
    val videoConstraints: VideoConstraints? = null,                     // see §7
    // ----- load-time defaults (applied before first frame) -----
    val preferredAudioLanguage: String? = null,                         // BCP-47, e.g. "es"
    val preferredTextLanguage: String? = null,                          // BCP-47, e.g. "en"
    val captionsDefault: CaptionsDefaultMode = CaptionsDefaultMode.OFF, // OFF / ON / FOLLOW_SYSTEM
    val startPositionMs: Long? = null,                                  // resume / deep-link
    val startMuted: Boolean = false,                                    // required for Web autoplay
    val loop: Boolean = false,                                          // restart at end; ignored for live
    // -----
    val adConfig: AdConfig? = null,                                     // see ads-client-side.md / ads-server-side.md
    val metadata: MediaMetadata? = null,                                // title/subtitle/artwork for overlays
    val chapterThumbnails: Boolean = false,                             // see local-files.md
)
```

`headers` is an `ImmutableMap` from `kotlinx.collections.immutable`. `:core` declares that dependency
as `implementation`, not `api`, so you must add `kotlinx-collections-immutable` to your own build
before you can call `persistentMapOf` — see [troubleshooting.md](troubleshooting.md).

### Source and container

- **`MediaSource`** is sealed: `MediaSource.Url("https://…")` for progressive MP4, HLS or DASH, or
  `MediaSource.LocalFile("/absolute/path")` for an on-device file. `LocalFile` is **played by all
  three engines today** (Media3 `setUri`, `NSURL.fileURLWithPath`, and the native `<video>` on web) —
  a `LocalFile` is always treated as progressive, never adaptive. A `file://` URL is accepted by
  `MediaSource.Url` and recognised as local. See [local-files.md](local-files.md) for what each
  platform can actually read.
- **`MimeType`** is `MP4`, `HLS` or `DASH`. Leave it `null` to infer from the URL extension (`.m3u8` →
  HLS, `.mpd` → DASH, `.mp4`/`.webm` → progressive); set it explicitly for extension-less or signed
  URLs. There is no `MimeType.MKV` — Matroska is a progressive local-file case.
- **`headers`** apply to manifest and segment requests on Android, iOS and Web. Web caveat: a
  *progressive* MP4/WebM plays through the browser's native `<video>`, which cannot take custom
  request headers, so headers apply only to Shaka-managed HLS/DASH there.

### Load-time defaults

These are applied as the item prepares, so the right track, position and mute state are in place
before the first frame — no reactive flip after playback starts.

- **`preferredAudioLanguage` / `preferredTextLanguage`** — BCP-47 tags that pre-select the audio and
  subtitle tracks instead of waiting for the track lists and then calling `selectTrack`.
- **`captionsDefault`** — `OFF` (the default), `ON`, or `FOLLOW_SYSTEM` (Android `CaptioningManager`,
  iOS Media Accessibility). `FOLLOW_SYSTEM` degrades to `OFF` on Web, which has no universal
  caption-accessibility signal.
- **`startPositionMs`** — begin at a content-time offset (continue-watching, deep-link). `null` or `0`
  starts at the beginning, or at the live edge for live.
- **`startMuted`** — begin muted. Only ever forces mute *on*; it never unmutes. Browsers block
  unmuted autoplay, so this is effectively mandatory for autoplaying feeds on Web.
- **`loop`** — restart the item when it ends (Media3 repeat-one, AVPlayer seek-on-end, Web
  `<video>.loop`). Ignored for live.

> **Web caveat:** the language and caption preferences apply to Shaka-managed HLS/DASH only — a
> progressive MP4/WebM is a single native `<video>` stream with no selectable audio or text tracks.
> `startPositionMs`, `startMuted` and `loop` still work there.

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

> **Platform routing.** On Web, HLS/DASH go through Shaka (MSE) while a progressive MP4/WebM plays via
> the native `<video>` element. On iOS, AVPlayer plays HLS and progressive only — no DASH, and HLS
> must be `avc1`, not in-band-parameter-set `avc3`. See the
> [capability matrix](../README.md#capability-matrix) and
> [platform-support.md](platform-support.md).

---

## 3. Transport

All transport is **fire-and-forget** — it requests a change and returns immediately; the result lands
on `state` and `events`.

```kotlin
player.play()
player.pause()
player.seekTo(positionMs = 45_000)      // content time, ms
player.setPlaybackSpeed(1.5f)           // 1f is normal speed; never 0f — pause instead
player.setVolume(0.5f)                  // 0f–1f
player.setMuted(true)                   // independent of volume; unmuting restores the level
player.stop()                           // clears the item, keeps the player reusable
```

Two behaviours worth knowing before you build a UI around them:

- **Playback speed is not reset by `setMediaItem`.** It persists across loads on all three engines, so
  a user who set 1.5x on one item gets 1.5x on the next. Reset it yourself if that is not what you
  want.
- **Volume and mute are independent.** `setVolume` never clears the mute flag, and muting retains the
  volume value so unmuting restores it rather than jumping to full scale.

---

## 4. Observe state and events

Two flows, two jobs.

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

`PlayerState` fields:

| Field | Notes |
|---|---|
| `playbackState` | `IDLE` / `BUFFERING` / `READY` / `ENDED`. `READY` means the engine *could* render, not that it is. |
| `isPlaying` | Frames are actually advancing **now**. |
| `playWhenReady` | Play **intent** — bind the play/pause icon to this, not `isPlaying`. |
| `positionMs` | Content time, ms. Never ad time. |
| `bufferedPositionMs` | An **absolute timeline position**, not a duration ahead of `positionMs` — subtract `positionMs` for the buffered-ahead amount. On iOS it is currently approximated as `positionMs`, so a buffer-ahead indicator reads permanently empty there. |
| `durationMs: Long?` | `null` = unknown or unresolved. **Never** used to signal live. |
| `isLive` | Use this for live, not a null duration. See [§6](#6-live-streams). |
| `isSeekable` | Bind the scrubber's enabled state to this, not to `durationMs` being non-null. |
| `pendingSeekTargetMs: Long?` | The target of an in-flight seek — show it on the scrubber to avoid rubber-banding. |
| `playbackSpeed`, `volume`, `isMuted` | |
| `videoSize: VideoSize?` | Decoded frame dimensions. **Android only** — the iOS and Web engines always leave this `null`, so never gate layout on it. |
| `mediaItem: MediaItem?` | What's loaded, so late subscribers read "what's playing" without replaying events. |
| `error: PlayerError?` | Non-null after a fatal error, and **sticky** until the next load or release. See [§8](#8-error-handling-and-recovery). |
| `audioTracks`, `textTracks`, `selectedAudioTrackId`, `selectedTextTrackId` | See [§5](#5-track-selection-audio-and-subtitles). |
| `chapters: ImmutableList<Chapter>` | Embedded chapter markers from a **local** container, sorted by start. Populated on Android and iOS; always empty on Web. They arrive **asynchronously**, as a second state emission after load — see [local-files.md](local-files.md). |
| `adState: AdState?` | Client-side ad playback, written by the engine on Android and Web only. `null` outside an ad. Server-side ads do **not** populate this — see [ads-server-side.md](ads-server-side.md). |
| `currentTrack`, `availableTracks` | **Reserved.** Video rendition/quality selection is not implemented; nothing ever writes these. |

### `events: SharedFlow<PlayerEvent>` — analytics and one-shot reactions

Timed and replay-free. Every event carries `positionMs` (content time) and a monotonic
`elapsedRealtimeMs` — use *deltas* for startup time, rebuffer duration and seek latency.

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

All three engines emit `BufferingStarted` / `BufferingEnded`, `SeekStarted(targetMs)` /
`SeekCompleted`, `FirstFrameRendered`, `MediaItemTransition(item)`, `PlaybackStateChanged(state)`,
`IsPlayingChanged(isPlaying)`, `TracksChanged(audioTracks, textTracks)`,
`NativePlayerAttached(handle)`, `Ended` and `FatalError(error)`. `BufferingStarted` also fires for the
*initial* buffering of a new item, so don't count every one as a rebuffer — use `FirstFrameRendered`
to tell startup from rebuffering.

Two groups are **not** emitted everywhere:

- **`PlaybackRecovered(reason)`** — Android only for `BEHIND_LIVE_WINDOW`, iOS only for `STALL`. It
  fires when the engine **auto-recovered** from a transient fault instead of surfacing a
  `FatalError`, so playback continues: Android re-snaps to the live edge, iOS nudges a wedged player
  after a long stall. Surface a brief "reconnecting…" hint, or feed it to QoS.
- **`BitrateChanged(format)`, `BandwidthSample(bitsPerSecond)`, `DroppedFrames(count)`** — **Android
  only.** The iOS and Web engines emit none of the three, so a QoS adapter built on them collects
  nothing on two of the three platforms. See [analytics.md](analytics.md).

**Delivery is best-effort, not guaranteed.** `events` is a `MutableSharedFlow(replay = 0,
extraBufferCapacity = 64)` written with `tryEmit`. So there is **no replay** — a collector only sees
events emitted after it subscribes, and launching it after `setMediaItem` can miss
`MediaItemTransition`, the first `BufferingStarted` and even `FirstFrameRendered` (subscribe first,
load second; in a `PlayerComponent`, subscribe inside `attach`) — and **overflow drops silently**: a
collector more than 64 events behind loses the excess with no signal.

> **Rule of thumb:** drive **UI** off `state` (idempotent, conflated); drive **analytics and one-shot
> side-effects** off `events` (timestamped, ordered, but neither replayed nor guaranteed).

---

## 5. Track selection (audio and subtitles)

When a manifest exposes alternative audio or subtitle tracks they appear on `state.audioTracks` and
`state.textTracks` once the item is ready, and on `PlayerEvent.TracksChanged`:

```kotlin
data class MediaTrack(
    val id: String,               // opaque, engine-stable; unique only within one type, for the loaded item
    val type: TrackType,          // AUDIO / TEXT / VIDEO
    val label: String? = null,    // human-readable, if the manifest has one
    val language: String? = null, // BCP-47 ("en", "pt-BR")
    val isSelected: Boolean = false,
    val isDefault: Boolean = false,
)
```

Selection is fire-and-forget: the engine applies it and re-emits the lists. **Don't** assume the
change optimistically — read it back from `state`:

```kotlin
val french = player.state.value.audioTracks.firstOrNull { it.language == "fr" }
french?.let { player.selectTrack(it) }

val englishSubs = player.state.value.textTracks.firstOrNull { it.language == "en" }
englishSubs?.let { player.selectTrack(it) }

player.clearTextTrack()           // subtitles OFF (selectedTextTrackId becomes null)
```

`selectedTextTrackId == null` means subtitles **off**, not "unknown". `selectedAudioTrackId == null`
means the opposite — the engine has not reported a selection yet. `TrackType.VIDEO` exists in the
enum but engines ignore selection of a video track. Cue *rendering* is a surface concern: the `:ui`
`PlayerSurface` renders the selected text track natively, and the overlay's subtitle and audio
buttons drive the calls above for you — see [ui.md](ui.md).

---

## 6. Live streams

Live is detected automatically on all three platforms and surfaced as `state.isLive == true`:

- **Android** — `ExoPlayer.isCurrentMediaItemLive`.
- **Web** — the `<video>` element's `duration` is infinite.
- **iOS** — the item is *ready*, its duration is indefinite, **and** it exposes a seekable range. The
  seekable-range term is what stops an HLS VOD flickering `isLive = true` for a tick during startup,
  before its duration resolves.

Drive your UI off `isLive`, **not** a null duration: a VOD whose duration hasn't resolved yet also
reports `null`. The converse holds too — a live asset may report a finite `durationMs`, which is the
DVR window length, not the programme length. For whether the user can scrub at all, use `isSeekable`.
The `:ui` overlay already follows this: it shows a "LIVE" label and hides the scrubber.

Live tuning goes in `PlayerConfig.liveConfig`: `targetOffsetMs` on Android and iOS,
`minPlaybackSpeed`/`maxPlaybackSpeed` catch-up on Android only, `lowLatencyMode` on Web only
(LL-HLS/LL-DASH is manifest-driven on Android and iOS).

---

## 7. Capping quality (`VideoConstraints`)

Cap adaptive (HLS/DASH) rendition selection per item — for data saving, or to steer ABR away from a
rung a device rejects:

```kotlin
MediaItem(
    source = MediaSource.Url("https://…/master.m3u8"),
    mimeType = MimeType.HLS,
    videoConstraints = VideoConstraints(maxWidth = 1280, maxHeight = 720, maxBitrateBps = 4_000_000),
)
```

`null` fields are unbounded. Mapped to `TrackSelectionParameters` (Android),
`AVPlayerItem.preferredMaximumResolution` / `preferredPeakBitRate` (iOS) and Shaka `restrictions`
(Web). The cap is per item and each engine resets it, so it never leaks to the next source.

**Set `maxWidth` and `maxHeight` together.** Media3 and AVPlayer take a size, not a single dimension,
so a width-only cap is ignored on Android and iOS. (Shaka does apply them independently, but relying
on that makes your cap platform-dependent.)

---

## 8. Error handling and recovery

A fatal error sets `state.error` to a `PlayerError` and emits `PlayerEvent.FatalError`. The player
stays usable: the **next `setMediaItem` clears the error** and starts fresh, so "load something" is
the recovery path. `error` is otherwise sticky until the next load or release — clear your own error
UI on load rather than waiting for it to return to `null` on its own.

```kotlin
data class PlayerError(
    val category: Category,        // SOURCE / NETWORK / DRM / DECODER / RENDERER / UNKNOWN
    val code: String? = null,      // the engine's own code as a string — diagnostic only, never branch on it
    val message: String? = null,   // engine text, unlocalised — do not show it verbatim to end users
    val nativeCause: Any? = null,  // the original ExoPlaybackException / NSError / Shaka error object
)
```

```kotlin
player.state.collect { s ->
    val err = s.error
    errorOverlay.isVisible = err != null
    errorOverlay.text = err?.let { "${it.category}: ${it.message ?: "Playback failed"}" }
}
// recover by reloading, or by switching source:
retryButton.onClick { player.setMediaItem(currentItem, autoPlay = true) }
```

Branch on `category` and nothing else — `code` is a different code space on every platform. Two
things about `category` that will otherwise cost you an afternoon:

- **`DRM` is not reliable as a "this was a DRM problem" signal.** Android emits it for an unsupported
  scheme and for key-system/licence failures. iOS emits it only for FairPlay key-delivery failures,
  once a FairPlay key manager exists; an unsupported scheme, or a FairPlay config with no
  `certificateUrl`, surfaces as `SOURCE` instead. On Web, a load-time DRM failure also arrives as
  `SOURCE`. See [drm.md](drm.md).
- **iOS puts the underlying `CoreMediaErrorDomain` code in `message`**, unwrapping
  `NSUnderlyingErrorKey`. That underlying code is usually what actually pinpoints an AVPlayer
  failure; the top-level `NSError` rarely does.

Not every fault is fatal — see `PlaybackRecovered` in [§4](#4-observe-state-and-events) for the ones
the engine survives, and [troubleshooting.md](troubleshooting.md) for symptom-first diagnosis.

---

## See also

- [ui.md](ui.md) — the Compose chrome, `PlayerView` and `PlayerSurface`.
- [drm.md](drm.md) — protected content.
- [analytics.md](analytics.md) — QoS events, `PlayerComponent` and `nativePlayerHandle`.
- [local-files.md](local-files.md) — on-device playback and `PlayerState.chapters`.
- [troubleshooting.md](troubleshooting.md) — symptom → cause → fix.
