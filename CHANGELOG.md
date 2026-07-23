# Changelog

All notable changes to Outis are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[semantic versioning](https://semver.org/spec/v2.0.0.html).

**A note on compatibility at alpha.** The published contract is deliberately additive — new
`VideoPlayer` methods arrive with default bodies, new `PlayerState` and configuration fields arrive
with defaults. Source-additive is not the same as binary-compatible, though: adding a field to a data
class changes its `copy` and `componentN` arity, so consumers must recompile rather than swapping the
jar. Until `1.0.0`, releases that require a recompile are marked **recompile required** rather than
being treated as breaking changes.

## [Unreleased]

Nothing yet.

## [0.1.0-alpha01] — unreleased

First release. Not yet published to Maven Central.

### Added

**Core player (`outis-core`)**

- `VideoPlayer`, an engine-agnostic playback contract carrying no platform or Compose types, backed by
  Media3/ExoPlayer on Android, `AVPlayer` on iOS and Shaka Player on Web.
- `PlayerState` as a conflated snapshot and `PlayerEvent` as a timed, one-shot stream; every event
  carries `positionMs` and a monotonic `elapsedRealtimeMs`.
- `MediaItem` and `MediaSource` covering remote URLs and local files, with `MimeType` routing between
  adaptive and progressive playback.
- `PlayerError` with a coarse cross-engine `Category` (`SOURCE`, `NETWORK`, `DRM`, `DECODER`,
  `RENDERER`, `UNKNOWN`), the engine's own `code`, and the untouched `nativeCause`.
- Audio and subtitle track selection through `selectTrack` and `clearTextTrack`, with selection
  reported back on `state` rather than applied optimistically.
- `PlayerComponent` / `PlayerHost` plugin seam for analytics and QoS integrations, with a
  player-lifetime `CoroutineScope` and a `nativePlayerHandle` that re-emits on engine re-creation.
- Playback configuration: `VideoConstraints` for capping the ABR ladder, plus `BufferConfig`,
  `RetryConfig` and `LiveConfig`.

**DRM**

- Widevine and PlayReady on Android and Web; FairPlay on iOS and Safari, via `DrmConfig`.
- `licenseRequestInterceptor` and `licenseResponseInterceptor` for providers whose proxy does not
  accept a raw challenge — engine-neutral, taking and returning only `ByteArray`, `String` and `Map`.
- `WidevineLevel` for forcing the software CDM (the expired-L1-certificate workaround) on Android and
  Web. No-op on iOS, which has no client-settable security level.

**Local files and chapters**

- Embedded chapter extraction for MP4/M4V (QuickTime `chap` text tracks with a Nero `chpl` fallback)
  and Matroska, parsed in pure Kotlin over small ranged reads. Wired on Android and iOS.
- Optional per-chapter thumbnails from an MP4 chapter image track, with size and count budgets.
- Parsing is hardened against malformed input and fails to an empty list rather than disturbing
  playback.

**Ads**

- Server-side ad insertion through `AdController`: an engine-agnostic cue-point tracker the application
  drives, with `AdState`, `AdEvent` and quartile tracking. Parsers for MediaTailor avails JSON and
  SCTE-35 cues in HLS playlists.
- Client-side ad insertion via Google IMA on Android and Web, surfaced through `PlayerState.adState`.

**Compose UI (`outis-ui`)**

- `PlayerSurface`, the platform video surface, and `PlayerView`, a batteries-included player.
- A controls overlay built from composable building blocks, usable wholesale or piece by piece through
  `PlayerControlsScope`, with `PlayerControlsState` driving auto-hide and scrub state.
- Fullscreen and picture-in-picture seams via `PlayerWindow`, with `rememberPlayerWindow` on Android.
- Keyboard shortcuts on web, and a D-pad focus ring for TV.
- On web, the shared Compose overlay composites over the engine's `<video>` by punching a transparent
  hole through the Compose canvas, so the same chrome runs on all three platforms.

### Known limitations

- **Video rendition selection is not implemented.** `PlayerState.currentTrack` and `availableTracks`
  are reserved and never populated; track selection covers audio and subtitles only. Use
  `MediaItem.videoConstraints` to cap the ladder.
- **`BitrateChanged`, `BandwidthSample` and `DroppedFrames` are emitted on Android only.**
- **No client-side ad adapter for iOS.** The bridge exists (`updateAdState`, `setAdContainer`,
  `adContainer`) but no IMA iOS adapter ships, and nothing in this repository has been built against
  the IMA iOS pod.
- **The `jvm` and `wasmJs` targets carry no engine.** They compile the API and throw
  `UnsupportedOperationException` on construction. The JVM target exists to run the shared test suite.
- **No `iosX64` target**, so Intel Macs cannot run the simulator build.
- iOS cannot play DASH, or HLS carrying in-band parameter sets (`avc3`); progressive MP4 needs
  `faststart`.
- Chapters are unavailable on web, which has no local-file API.

[Unreleased]: https://github.com/nonbinarydev/outis/compare/v0.1.0-alpha01...HEAD
[0.1.0-alpha01]: https://github.com/nonbinarydev/outis/releases/tag/v0.1.0-alpha01
