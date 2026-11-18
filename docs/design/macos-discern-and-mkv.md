[Outis](../../README.md) › [Docs](../README.md) › Design › macOS, Discern and MKV

# macOS, embedding in Discern, and files AVPlayer cannot open

**Status:** proposal, 18 November 2026. Sections 2 and 3 are built and tested on the branch
`feat/64-macos-swift-package`. Section 5 recommends an engine route but builds nothing; that waits for a
decision. Sections 4 and 7 propose API changes and bug fixes that are not made here.

## 1. Goal and constraints

Outis's macOS build becomes the video previewer in Discern's planned re-sync/Timing feature, which comes
after Discern's December release. Discern is a sandboxed Mac App Store app written in Swift/SwiftUI and
built only in Xcode, so Outis must:

- be consumable through Swift Package Manager, with no Gradle step at the app's build time;
- work inside the App Sandbox, needing no entitlement beyond what the app already has, and play a file
  the app already holds access to;
- add only dynamic frameworks that are acceptable on the Mac App Store, using no private API;
- give Discern what a timing tool needs (section 4), and draw no subtitles of its own.

## 2. What is built

| Piece | Where | What it is |
|---|---|---|
| `macosArm64` target | `core/build.gradle.kts` | The iOS AVFoundation engine, unchanged in behaviour, compiled for macOS. |
| `appleMain` | `core/src/appleMain` | The engine and everything else that was in `iosMain` and is not UIKit. `iosMain` keeps the UIKit ad bridge and the `AVAudioSession` setup; `macosArm64Main` has a no-op audio session (macOS has none). |
| Swift entry points | `appleMain/.../SwiftEntryPoints.kt` | `makeVideoPlayer()`, `mediaItemOf(url:)`, `observeState`/`observeEvents` returning a cancellable `PlayerObservation`. Built through Kotlin's defaults, so adding a model field breaks no Swift caller. `@ExperimentalPlayerApi`. |
| `OutisCore.xcframework` | `:core:assembleOutisCoreReleaseXCFramework` | Dynamic; `ios-arm64`, `ios-arm64-simulator`, `macos-arm64`. Renamed from `Outis` so the Swift module can take that name. The macOS binary is 4.5 MB. Minimums: iOS 15, macOS 12. |
| Swift package | `Package.swift`, `swift/Outis` | A binary target (`OutisCore`) and a Swift target (`Outis`) that re-exports it and adds `MediaItem.of(_:)`, `stateStream`/`eventStream` (`AsyncStream`), `avPlayer`, and a SwiftUI `PlayerSurface` (an `AVPlayerLayer`) that reports the picture's rect for overlays. |
| macOS sample | `sample/macosApp` | Xcode-only SwiftUI app, sandboxed, adding the package by local path. Opens a file from an open panel, reopens it after a relaunch through a security-scoped bookmark, plays the catalogue's MP4 and HLS, with rate, audio track, frame stepping, a display-frame clock and an overlay pinned to the picture. |
| Docs, CI | `docs/getting-started-swift.md`, `docs/platform-support.md`, workflows | The Swift route; macOS in the capability page; macOS tests and detekt in CI. |

Hand-written entry points were chosen over SKIE for now. SKIE would be a new build dependency that pins
the Kotlin version (Outis is on 2.4.21). A thin Swift module gives the same `for await` shape, and SKIE
or Kotlin's Swift export can replace it later without changing what Swift callers write.

### Verified, and how

All on an M-series Mac, macOS 27.2.1, Xcode 27.2, against the release XCFramework.

| Check | Result |
|---|---|
| `core` compiles for `macosArm64` and `iosSimulatorArm64`; `:ui` still compiles for iOS | Yes |
| `commonTest` on macOS (`:core:macosArm64Test`) | 130 tests, 0 failures |
| detekt on `appleMain`, `iosMain`, `macosArm64Main`; ABI dump updated | Clean; the dump gains `macosArm64` and the six Swift entry points |
| Swift package builds for macOS and the iOS simulator (`xcodebuild`, no Gradle) | Yes |
| Sample app builds with `xcodebuild`, sandboxed, framework embedded and signed | Yes |
| Remote MP4 (BBB 1080p) and HLS (BBB adaptive) play, from a harness linking the framework | Yes, both outside and **inside the App Sandbox** (network client only) |
| Local MP4: H.264 + 2×AAC, and MPEG-2 + AC-3, both remuxed from MKV with `-c copy` | Both play; switching to the second audio track works |
| Seek, rate 2×, frame step | Work; precision findings in section 4 |
| A file outside the sandbox container without a grant | Refused by the sandbox, as it should be |
| The sample's open panel and bookmark reopen, clicked by hand | **Not verified**: needs someone at the Mac |
| iOS on a device | **Not verified**: the iOS engine code is unchanged apart from the move, but run the iOS sample once |

## 3. App Sandbox and the Mac App Store

- **Entitlements.** Outis needs none. Discern's existing `files.user-selected.read-write` and
  `files.bookmarks.app-scope` are enough for local files. Remote streams would need `network.client`,
  which Discern does not need for this.
- **Already-opened URLs.** `MediaItem.of(url)` plays a file URL from its path. The sandbox grants access
  per path to the process, so this works for an open panel's choice and for a resolved bookmark between
  `startAccessingSecurityScopedResource()` and its stop. Discern should stop access only after `stop()` or
  after the next item replaces this one.
- **Kotlin/Native in the sandbox.** The runtime and coroutines run normally there (tested above).
- **Dynamic framework.** The XCFramework is dynamic, and Xcode embeds and signs it through SwiftPM.
- **Private API.** One finding: `MediaItem.headers` passes the undocumented
  `AVURLAssetHTTPHeaderFieldsKey` option string to `AVURLAsset`. It is only used when headers are set,
  which a local-file previewer never does, but before calling Outis "App Store-clean" it should move to
  an `AVAssetResourceLoaderDelegate`. Nothing else in `appleMain` uses undocumented API.

## 4. What a subtitle timing tool needs from the API

| Need | Today | Gap and proposal |
|---|---|---|
| Open a local file | `MediaItem.of(url)`; works sandboxed | None |
| Know when a file cannot be opened | **Broken**: Outis stays in `BUFFERING` forever (bug B1, section 7) | Fix B1 ([#68](https://github.com/nonbinarydev/outis/issues/68)). Discern needs this to fall back to its remux. |
| Seek to a frame | `seekTo(positionMs)` with zero tolerance | **Bug B2** ([#69](https://github.com/nonbinarydev/outis/issues/69)): the engine converts at timescale 600, so a frame's exact millisecond time (12,364 ms → 12.3633 s) lands *before* the frame, and the previous one shows. Use timescale 1000. Measured on a 25 fps file whose frames sit at x.004 + n × 0.04 s. |
| Which frame is showing | Not exposed | [#70](https://github.com/nonbinarydev/outis/issues/70). AVPlayer shows the frame with the greatest presentation time ≤ the playhead (measured: playhead 12.345 → frame 12.324). Propose `PlayerState.videoFrameRate` (from the track's nominal rate) so apps can find frame boundaries, and document the rule. The frame's exact time is available today through an `AVPlayerItemVideoOutput` on `player.avPlayer`. |
| Step frames | Not wrapped; `avPlayer.currentItem.step(byCount:)` works | [#70](https://github.com/nonbinarydev/outis/issues/70): `VideoPlayer.stepFrames(count: Int)`, with a default no-op body per the additive rule. AVFoundation steps exactly once the playhead is on a frame, but from between frames +1 skips one (measured: 12.345 → 12.404, not 12.364). So the Apple engine should snap to the shown frame first. Android can seek by one frame duration with exact seek parameters; web stays a no-op. |
| Time fine enough to place cues | `positionPollIntervalMs` (the sample uses 50) plus `avPlayer.currentTime()` read per display frame (`TimelineView(.animation)`) | Enough for drawing cues. A Swift `PlayerClock` could wrap the display-frame read later. |
| Play, pause, rate | Exist; rate 2× verified | None |
| Choose the audio track | `selectTrack`; verified on a two-track MP4 | None |
| No built-in subtitle drawing | `PlayerSurface` draws none unless a text track is selected, and `captionsDefault` is `OFF` | None. A future extra engine must default to no subtitles too. |
| Discern's SwiftUI on top | `.overlay` on `PlayerSurface`, plus `onVideoRectChange` for the picture's rect | None; prototyped in the sample |
| Waveform | Not in Outis | **Discern extracts audio itself with its bundled ffmpeg.** That is the only route that also covers MKV, and Discern's build already has the PCM path (`aresample`, `pcm_f32le`). Outis could only offer AVFoundation's routes: `MTAudioProcessingTap` sees audio only as it plays, in real time, and `AVAssetReader` only reads files AVFoundation opens, so neither reads a whole MKV. Exposing samples is out of scope for a player SDK. |

## 5. Files AVPlayer cannot open: MKV, DTS, TrueHD

AVFoundation has no Matroska demuxer: it refuses an MKV with `AVFoundationErrorDomain -11828` ("Cannot
Open"), underlying `-12847`. It has no DTS or TrueHD decoder either. There are three routes.

### A. Remux to MP4 with an LGPL ffmpeg, then play in AVPlayer

Discern's `build-ffmpeg.sh` already plans this as its "timing preview". It copies video and audio
without re-encoding into an MP4/MOV, and makes a PCM or Apple-AAC preview track where Apple can't play
the original audio.

- **What it covers:** MPEG-2, H.264, HEVC (`-tag:v hvc1`), AC-3, E-AC-3, AAC and FLAC. Measured here:
  H.264 + AAC and MPEG-2 + AC-3 copied out of MKV both play in AVPlayer on macOS. Opus, Vorbis and PCM can
  be decoded by ffmpeg and given to AVPlayer as a PCM or AAC preview track.
- **What it leaves out:** DTS and TrueHD have no Apple decoder. Discern's shipped ffmpeg leaves the `dca`
  and `truehd` decoders out (your decision, 17 November), so those files preview silently unless another
  audio track exists. PGS and VobSub are never drawn, which suits Discern, since it draws its own cues.
  VC-1 has no picture.
- **Cost:** time and temporary disk, roughly the size of the streams copied. Copying only the video and
  one audio track keeps it down; a later version could write fragmented MP4 in pieces, on demand.
- **Licence and store risk:** none new. Outis stays Apache-2.0 with no third-party binary, and Discern
  already ships this ffmpeg.

### B. libmpv as an optional second engine

The facts below are from the research pass; sources are in its notes.

- **Licence:** mpv is LGPLv2.1+ when built with `-Dgpl=false`. MPVKit's `MPVKit` product is the LGPL
  build, with GPL in a separate `MPVKit-GPL` product. Its FFmpeg is built `--enable-version3`, which makes
  the bundle effectively LGPLv3.
- **Distribution:** MPVKit ships **static** xcframeworks only, about 350 MB of SwiftPM downloads across
  all platforms. Its README calls it suitable "only for learning", and it has one main maintainer.
  Upstream mpv is healthy: 0.42.0 shipped on 5 November 2026.
- **Mac App Store:** one sandboxed store app, Reel, ships MPVKit's LGPL build. It had to rebuild libmpv
  with `-Dlua=disabled`, because LuaJIT crashed in `mpv_initialize` under the store's entitlements. It
  meets the LGPL with a written offer plus object files.
- **Rendering:** the official routes are the OpenGL render API (deprecated on macOS) and software
  rendering. Metal goes through MoltenVK via an MPVKit patch the README calls unofficial.
- **Fit for a timing tool:** exact seeks (`seek <t> absolute+exact`), `frame-step` and `frame-back-step`,
  an observable `time-pos`, `aid` for the audio track, and `sid=no` to draw no subtitles. MKV, DTS,
  TrueHD, PGS, VobSub and ASS (via libass) are all decoded and rendered.
- **Patents:** DTS and TrueHD decoding comes from the same FFmpeg decoders Discern chose not to ship, so
  adopting libmpv would reopen that decision, not avoid it.

### C. VLCKit

- **Licence and distribution:** LGPL-2.1. Its dependencies are built `--disable-gpl --disable-gnuv3`,
  and it ships as a **dynamic** framework, which makes LGPL compliance simpler.
- **3.x:** distributed through CocoaPods only, and CocoaPods trunk goes read-only on 2 December 2026. It
  renders with OpenGL, updates time at most every 250 ms, and steps frames forward only.
- **4.x:** has SwiftPM, an `AVSampleBufferDisplayLayer` output and previous-frame stepping, but is still
  alpha (a26), with a 910 MB package download.
- **Mac App Store:** no confirmed sandboxed store app on macOS. A 2017 rejection for a private API
  (`CGSCreateRegisteredCursorImage`) has no confirmed resolution.
- **JVM:** the mature binding (vlcj) is GPL.

### Recommendation

1. **Discern's re-sync uses route A**, with the remux living in Discern, which already has the ffmpeg
   build and the plan. Outis's part is what this branch builds, plus the bug fixes B1 and B2 and the
   `stepFrames` and `videoFrameRate` additions from section 4. Nothing new is needed in licences,
   dependencies or App Store risk.
2. **Outis's own second engine, when there is a consumer for it, is libmpv.** That means MKV for other
   Mac apps, and the JVM desktop (section 6). Build it with Outis's own pipeline rather than MPVKit:
   - `-Dgpl=false -Dlua=disabled`;
   - FFmpeg without `--enable-version3`, if its components allow, so the bundle stays LGPLv2.1 and avoids
     LGPLv3's installation-information duty;
   - the DTS and TrueHD decoders as a build option, so an app decides on the patent question itself;
   - shipped as a **dynamic** framework in a separate, optional product (say `OutisMPV`), never inside
     `outis-core`.

   This needs your yes first: it adds a binary dependency and a new licence.
3. **Not VLCKit**, for now: 3.x's distribution and timing limits, and 4.x is still alpha.

When route B happens, choose the engine per item, not per player: a wrapper like `SmilResolvingPlayer`
that sniffs a local file's first bytes (Matroska starts `1A 45 DF A3`) and hands MKV to libmpv, keeping
AVPlayer for MP4, HLS, DRM and AirPlay. Sniffing is deterministic; waiting for AVFoundation's
"Cannot Open" works only once B1 is fixed.

## 6. JVM desktop (issue #62)

What carries over from this work:

- **`commonMain`**: the model, state, sidecar captions, chapters and SMIL. It already compiles for
  `jvm`; only an engine is missing (ADR-0010).
- **The section 4 API additions**: these go into `commonMain` with default bodies, so a desktop engine
  implements them too.
- **The libmpv engine**: route B's Kotlin engine logic is written once, against a small binding
  interface:
  - the mapping from mpv properties to `PlayerState`, `track-list` parsing, and the seek, rate and
    audio-track commands, all shared;
  - Kotlin/Native cinterop on macOS, and JNA on the JVM (vlcj is GPL);
  - rendering into Compose Desktop through mpv's software render API into a Skia bitmap first, OpenGL
    later.

  The existing `mpv_kmp` binding (Apache-2.0) shows this works, but is too young to depend on.

Desktop would be non-DRM: mpv has no Widevine, PlayReady or FairPlay, and HLS through FFmpeg has no
ABR switching like AVPlayer's.

What does not carry over: the AVPlayer engine, `appleMain`, the XCFramework and the Swift package.

Proposed answer for #62: desktop is out of scope for 1.0 and stays a known limitation. When it comes in,
the engine is libmpv, shared with macOS as above.

## 7. Bugs found while testing (not fixed on this branch)

**B1. A load failure never surfaces on Apple platforms.** [#68](https://github.com/nonbinarydev/outis/issues/68)
- **Symptom:** an item that fails before playing stays in `PlaybackState.BUFFERING` with `error == null`
  indefinitely. This includes an MKV, a 404 URL, and a file the sandbox refuses. `isPlaying` can also
  keep the previous item's value.
- **Cause:** the engine reconciles state only from `addPeriodicTimeObserver`, which does not fire while
  the playhead never moves. `AVPlayerItem.status` goes to `.failed` within a second, but nothing reads it.
- **Reach:** affects iOS too, since it is the same code. It also makes the claim in `platform-support.md`
  that DASH on Apple "surfaces as a source error" untrue.
- **Fix:** observe `status` (KVO), or reconcile once when the asset's loading completes.

**B2. `seekTo` can land one frame early.** [#69](https://github.com/nonbinarydev/outis/issues/69)
- **Cause:** `CMTimeMakeWithSeconds(positionMs / 1000.0, 600)` rounds to 1/600 s.
- **Fix:** `CMTimeMake(positionMs, 1000)`.

Both belong in one small `fix/` pull request, verified on an iOS device as well as the Mac.

## 8. Open issues: proposed order

Grouped by what each unblocks. **Touched by this work:** #64 (largely done here) and #62 (decided
here, built later).

| Order | Issue | Why here |
|---|---|---|
| 1 | #3 Central Portal token, and `publish-env.sh` failing silently | Small, and the only thing between the project and its first release; the silent success is a trap. |
| 2 | #68 and #69 (B1 and B2 above) | Correctness bugs in a shipped engine. Discern's fallback and frame accuracy depend on them. |
| 3 | **#64** Swift package and Swift API (**this branch**) | Left to do: a release workflow that zips the XCFramework, computes its checksum and points `Package.swift` at the release asset; then `for await` in a Swift-only sample by package URL. |
| 4 | #70 Frame stepping and `videoFrameRate` (section 4) | Small, additive, and what Discern's Timing feature needs; due before Discern's re-sync work starts. |
| 5 | #18 Verify the focus ring on devices | A short verification pass, already overdue; it gives `:ui` a test source set. |
| 6 | #56 Background playback and media session | Expected of any player SDK before 1.0. macOS gets Now Playing from the same `MPNowPlayingInfoCenter` code. |
| 7 | #61 Quality menu | Small; builds on `VideoConstraints`, which already exists on all three engines. |
| 8 | #57 Playlists and preloading | Common need; touches all three engines and `:ui`. |
| 9 | #58 Casting (AirPlay button first, then Cast) | The AirPlay route button is cheap on Apple; Cast is the larger half. |
| 10 | #60 Timed metadata | Valuable for live; needs the demo media pipeline's fixtures. |
| 11 | **#62** Desktop engine (libmpv) | Decided here (section 6); built after 1.0 or when an app asks. |
| 12 | #63 IMA iOS adapter | Needs the IMA iOS SDK in the integration layer; ads on iOS have a working bridge already. |
| 13 | #59 Offline downloads with persistent DRM | Large, and needs persistent-licence work on every platform. |
| 14 | #66 JavaScript/TypeScript API and npm package | Large, and serves a different audience; it may deserve its own milestone. |
| 15 | #65 CTV web platforms (webOS, Tizen) | Large, and needs devices or emulators; after #66, which it would build on. |

## 9. Decisions needed

1. **Route A for Discern's re-sync**, with route B (libmpv, own build, dynamic, optional) only when a
   second consumer appears. Or prototype B now?
2. **The Swift API shape**: `makeVideoPlayer`, `MediaItem.of`, `stateStream`/`eventStream`,
   `PlayerSurface`, `avPlayer`, and renaming the XCFramework to `OutisCore`.
3. **The section 4 additions** (`stepFrames`, `videoFrameRate`, #70), and fixing B1 and B2 (#68, #69) in a
   separate PR.
4. **How the Swift package is released**: GitHub release assets with `Package.swift` rewritten per tag
   (the KMMBridge pattern), and whether `swift/` and `Package.swift` should live at the root (needed for
   resolving by URL) or in a separate repository.
