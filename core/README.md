# `:core`

Engine-agnostic core of the KMP video player. **No Compose, no UI, no platform player in any public
signature.** The Compose surface + overlay live in [`:ui`](../ui); analytics/ads adapters
attach through the plugin seam.

Targets: `jvm` (API/test-only — no engine), `android`, `iosArm64`, `iosSimulatorArm64`,
`js(IR)`, `wasmJs`. Source-set layout: `iosMain` over the two iOS targets, `webMain` over
`js`+`wasmJs`.

> New here? Start with the [SDK overview](../README.md) and the [playback](../docs/playback.md) /
> [DRM](../docs/drm.md) guides — this README is the `:core` module reference.

## Engines

| Platform | Engine | Status |
|---|---|---|
| Android | Media3 / ExoPlayer (`1.10.1`) — main-thread-pinned, one `Player.Listener` + one `AnalyticsListener`, 250 ms ticker | ✅ |
| iOS (arm64 + simulator) | AVFoundation `AVPlayer` — polled state, `AVContentKeySession` for FairPlay | ✅ |
| Web — `js(IR)` | Shaka Player over a DOM `<video>` | ✅ |
| Web — `wasmJs` | — | stub (ships headless; `createPlatformPlayer` throws — Kotlin/Wasm↔Shaka interop is a separate effort) |

The commonMain contract is frozen and compiles green on all targets; `FakeVideoPlayer` +
`VideoPlayerContractTest` pin the semantics.

> **Construct on the main thread.** The engines are main-thread affine (ExoPlayer is pinned to the
> main `Looper`; AVPlayer/Shaka likewise); call `VideoPlayer(context)` from the UI thread. Interface
> methods may then be called from any thread (each engine marshals back internally).

## The contract

```kotlin
val player = VideoPlayer(context)                 // AppContext; Android needs the app Context
player.setMediaItem(MediaItem(MediaSource.Url("https://…/master.m3u8")), autoPlay = true)
player.state    // StateFlow<PlayerState>  — conflated snapshot for the UI
player.events   // SharedFlow<PlayerEvent> — timed, one-shot; analytics/QoS consume this
player.release()                                  // idempotent
```

### Invariants (frozen here; everything else is additive)

1. `PlayerEvent` / `PlayerError` are **sealed** — ads/QoS/downloads add subtypes without touching core.
2. Every `PlayerEvent` carries `positionMs` (content time) **and** a monotonic `elapsedRealtimeMs`
   — QoS derives startup/rebuffer/seek latency from deltas. Adding this later would break every event.
3. The `PlayerComponent` / `PlayerHost` seam exists, with an **observable** `nativePlayerHandle`
   (`StateFlow`) so analytics SDKs re-bind when the native player is recreated.
4. `MediaSource` is sealed and source-agnostic, so offline downloads slot in later as just another
   `MediaSource` — not a player-state change.

## DRM

`MediaItem.drmConfig` (a [`DrmConfig`](src/commonMain/kotlin/dev/nonbinary/outis/core/source/DrmConfig.kt))
carries the key system, license server, optional FairPlay certificate and license-request headers:

- **Widevine / PlayReady** → Android (Media3 `DrmConfiguration`) + Web (Shaka `drm.servers`).
- **FairPlay** → iOS (`AVContentKeySession`) + Web/Safari (Shaka `drm.servers['com.apple.fps']`).

A scheme the platform can't satisfy surfaces a `PlayerError` (category `DRM`) — it never silently
plays. Full walkthrough in the [DRM guide](../docs/drm.md).

## Track selection (audio + text)

`PlayerState.{audioTracks, textTracks, selectedAudioTrackId, selectedTextTrackId}` (a
`selectedTextTrackId` of `null` means subtitles **off**) + `VideoPlayer.selectTrack(track)` /
`clearTextTrack()` + `PlayerEvent.TracksChanged`. Selection is fire-and-forget — the engine applies it
and re-emits the lists; don't assume it optimistically. `MediaTrack.id` is an opaque, engine-stable
token (only valid for the loaded item). *Cue rendering* is a surface concern (`:ui`), not core.

> **Compat note:** the track fields were added **append-only** to `PlayerState` and the two
> `VideoPlayer` methods have **default bodies** — so this is *source*-additive but *binary*-incompatible
> (data-class `copy`/`componentN` arity shifts; recompile consumers). Acceptable at `0.1.0-alpha01`.
> New `VideoPlayer` methods must always keep default bodies (guarded by `VideoPlayerAdditiveContractTest`).

## Adaptive quality caps & buffering

`MediaItem.videoConstraints` (a `VideoConstraints(maxWidth, maxHeight, maxBitrateBps)`) caps ABR per
item → `TrackSelectionParameters` (Android), `AVPlayerItem.preferredMaximumResolution` /
`preferredPeakBitRate` (iOS), Shaka `restrictions` (Web). Reset per item, so it never leaks forward.

`PlayerConfig.bufferConfig` (`BufferConfig`, construction-time) tunes the load control —
buffering/rebuffering goals + back-buffer → Media3 `DefaultLoadControl` (all fields), Shaka
`streaming.{bufferingGoal,rebufferingGoal,bufferBehind}` (web), `AVPlayerItem.preferredForwardBufferDuration`
(iOS, forward only). `PlayerConfig.initialBitrateBps` seeds the cold-start ABR estimate → Media3
`DefaultBandwidthMeter` + Shaka `abr.defaultBandwidthEstimate` (no-op on iOS). `null` keeps each engine's
stock behaviour.

`PlayerConfig.retryConfig` (`RetryConfig`) sets network connect/read timeouts + retry count / back-off /
cross-protocol redirects → Media3 (HTTP timeouts + `DefaultLoadErrorHandlingPolicy`) and Shaka
`retryParameters`; no-op on iOS. `PlayerConfig.audioConfig` (`AudioConfig`) covers audio-focus /
becoming-noisy (Android) and `AVAudioSession` mix-with-others (iOS); no-op on Web. `MediaItem.loop`
restarts an item when it ends (Media3 repeat-one / AVPlayer seek-on-end / Web `<video>.loop`).
`PlayerConfig.liveConfig` (`LiveConfig`) tunes live: target latency + catch-up speed (Media3
`LiveConfiguration`), target offset (iOS `configuredTimeOffsetFromLive`), low-latency mode (Shaka
`streaming.lowLatencyMode`).

The engines **auto-recover** from transient faults instead of surfacing them: Android re-snaps to the
live edge on `ERROR_CODE_BEHIND_LIVE_WINDOW`, and iOS nudges a wedged player after a long stall
(Android/Web self-recover natively) — both reported via `PlayerEvent.PlaybackRecovered(reason)`, not
`FatalError`.

## Ads

Both insertion models ship, and they work differently:

- **Server-side (SSAI)** — `AdController` is an engine-agnostic cue-point tracker in `commonMain`,
  driven by *your* app: no engine reads `AdConfig.ServerSide`, the stitched stream plays unchanged, and
  you feed positions in. Includes parsers for MediaTailor avails JSON and SCTE-35 cues in HLS
  playlists. See [../docs/ads-server-side.md](../docs/ads-server-side.md).
- **Client-side (CSAI)** — Google IMA, wired inside the engines on **Android and Web only**. Surfaced
  through `PlayerState.adState`, whose populated fields differ between those two engines. iOS has the
  bridge (`updateAdState`, `setAdContainer`, `adContainer`) but no adapter ships. See
  [../docs/ads-client-side.md](../docs/ads-client-side.md).

## Reserved for the roadmap (present but unused in v1)

- **Video-quality selection** — `PlayerState.{currentTrack, availableTracks}`, `VideoFormat`
  (audio/text selection *is* shipped; video-rendition selection is not).
- **Offline** — `MediaSource.LocalFile` plays today, but there is no download manager to produce those
  files.
- **QoS events on non-Android engines** — `BitrateChanged` / `BandwidthSample` / `DroppedFrames` are
  emitted by the Media3 engine only; the iOS and Web engines expose no comparable callback that this
  SDK currently maps. See [../docs/analytics.md](../docs/analytics.md).
- **`wasmJs` and `jvm` engines** — both targets compile the full API and throw
  `UnsupportedOperationException` on construction. `jvm` exists so `commonTest` runs fast.

## Known platform gaps

- **iOS:** DASH and in-band-parameter-set `avc3` HLS are unsupported by AVPlayer. `MediaItem.headers`
  use the private-but-stable `AVURLAssetHTTPHeaderFieldsKey` option (swap in an
  `AVAssetResourceLoaderDelegate` if you need a fully public path). `isLive` is inferred from
  "ready + indefinite duration + a seekable window" — a VOD exposes no seekable range until its finite
  duration loads, so there's no live-flicker at startup.
- **Web:** `MediaItem.headers` apply to Shaka-managed HLS/DASH only — a *progressive* MP4/WebM plays
  through the native `<video>`, which can't carry custom request headers. `captionsDefault =
  FOLLOW_SYSTEM` falls back to `OFF` (no universal browser caption-accessibility signal).

## Testing

Use `FakeVideoPlayer` (in `commonTest`) — it reproduces the engine semantics and exposes `simulate*`
hooks (`simulateReady`, `simulateFirstFrame`, `completeSeek`, `simulateNativeAttached`, …) so UI and
adapter tests run with no device.
