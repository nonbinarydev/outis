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
- SMIL sources: a `MediaSource.Smil`, or a `.smil` URL, is fetched and resolved to one of the HLS or
  DASH manifests it lists before any engine sees it. `PlayerConfig.smilSelector` chooses, defaulting to
  the engine's own preference (ADR-0007).
- `PlayerError` with a coarse cross-engine `Category` (`SOURCE`, `NETWORK`, `DRM`, `DECODER`,
  `RENDERER`, `UNKNOWN`), the engine's own `code`, and the untouched `nativeCause`.
- Audio, subtitle and alternate-rendition track selection through `selectTrack` and `clearTextTrack`,
  with selection reported back on `state` rather than applied optimistically.
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
- `drmSupport()`, which asks the platform which schemes this device can decrypt and at what Widevine
  security level. Every engine checks the same answer before loading a protected item, so a scheme the
  device lacks fails at once as `Category.DRM`, naming the scheme, on all three platforms.

**Local files and chapters**

- Chapters from three sources, first match wins: `MediaItem.chapters` declared by the app, a
  `chaptersUrl` WebVTT sidecar, then the container. The first two work on every platform and for streams.
- Embedded chapter extraction for MP4/M4V (QuickTime `chap` text tracks with a Nero `chpl` fallback)
  and Matroska, parsed in pure Kotlin over small ranged reads. Wired on Android and iOS.
- Optional per-chapter thumbnails from an MP4 chapter image track, with size and count budgets.
- Parsing is hardened against malformed input and fails to an empty list rather than disturbing
  playback.

**Subtitles**

- Sidecar subtitle files through `MediaItem.subtitles`: WebVTT and SRT beside a progressive file, a
  local one or an HLS/DASH stream, on all three platforms. They join `textTracks` after the media's own,
  and are selected, switched off and pre-selected the same way. A file that cannot be fetched or read
  shows no cues and never stops playback.
- `PlayerState.sidecarCues` (experimental): the cues to draw now, on iOS, whose AVPlayer takes no subtitle
  file. `PlayerSurface` draws them over the video.

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
- Keyboard shortcuts on web, and D-pad support for TV: a focus ring, and focus kept on the player while
  the controls are hidden, so the next key brings them back.
- Server-side ad UI: given an `AdController`, `rememberPlayerControlsState` locks the scrubber during a
  break, routes scrubs through `resolveSeek` and marks the cue-points, and `DefaultControls` draws a
  countdown and a skip button (`AdCountdown`, `SkipAdButton`, `AdOverlay`).
- On web, the shared Compose overlay composites over the engine's `<video>` by punching a transparent
  hole through the Compose canvas, so the same chrome runs on all three platforms.

**Analytics (`outis-analytics-mux`)**

- `MuxAnalytics`, a `PlayerComponent` that binds Mux Data's own SDK to the engine's native player on
  Android, iOS and Web, with per-video metadata from `MediaItem.analytics`.

**Analytics (`outis-analytics-firebase`)**

- `FirebaseUsageAnalytics`, a `PlayerComponent` that reports usage to Google Analytics 4: `video_start`,
  `video_complete` and `video_error` with the item's `PlaybackMetadata`, through Firebase on Android and
  iOS and gtag.js on the web. `logEvent` reports the app's own events, and `setCollectionEnabled` applies
  the viewer's consent to Firebase as a whole.

**Distribution**

- `outis-bom`, which aligns `outis-core`, `outis-ui` and the two analytics adapters to one version.

### Known limitations

- **Video selection is between alternate renditions, not bitrate rungs.** `videoTracks` lists versions
  of a programme (a remaster beside an original) on Android and web, and is never populated on iOS.
  Bitrate stays the engine's to adapt; cap the ladder with `MediaItem.videoConstraints`.
- **`BitrateChanged`, `BandwidthSample` and `DroppedFrames` are emitted on Android only.**
- **No client-side ad adapter for iOS.** The bridge exists (`setAdState`, `setAdContainer`,
  `adContainer`) but no IMA iOS adapter ships, and nothing in this repository has been built against
  the IMA iOS pod.
- **The `jvm` and `wasmJs` targets carry no engine.** They compile the API and throw
  `UnsupportedOperationException` on construction. They are published so shared code compiles for them
  and tests can run against a fake player ([ADR-0010](docs/decisions/0010-engine-less-targets-publish-the-api.md)).
- **No `iosX64` target**, so Intel Macs cannot run the simulator build.
- iOS cannot play DASH, or HLS carrying in-band parameter sets (`avc3`); progressive MP4 needs
  `faststart`.
- Embedded chapters are unavailable on web, which has no local-file API. Declared chapters and a
  `chaptersUrl` WebVTT sidecar work on all three platforms.
- **On iOS, sidecar subtitles are drawn by `:ui`, not AVKit.** AVFoundation has no API that adds a
  subtitle file to an item, so they don't reach picture-in-picture or AirPlay, a surface of your own must
  draw `PlayerState.sidecarCues`, and cue positioning is ignored.

[Unreleased]: https://github.com/nonbinarydev/outis/compare/v0.1.0-alpha01...HEAD
[0.1.0-alpha01]: https://github.com/nonbinarydev/outis/releases/tag/v0.1.0-alpha01
