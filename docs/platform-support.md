[Outis](../README.md) › [Docs](README.md) › Platform support

# Platform support, requirements and known gaps

This is the single source of truth for every per-platform claim about Outis. The capability matrix in
the [root README](../README.md#capability-matrix) is a deliberate summary of this page; nothing else
in the documentation set states a per-platform capability, it links here instead.

Two sections: what you need in order to build against Outis, and what actually differs once you do.

---

## Requirements

### Toolchain and dependency versions

Every version below is pinned in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml) unless
stated otherwise.

| | Version | Notes |
|---|---|---|
| Kotlin | 2.4.10 | Multiplatform plugin, applied by every library module. |
| Android Gradle Plugin | 9.1.1 | Both modules use the `com.android.kotlin.multiplatform.library` plugin. |
| Gradle | 9.6.1 | `gradle/wrapper/gradle-wrapper.properties`; only relevant if you build Outis from source. |
| Android `minSdk` | 24 | `core/build.gradle.kts`, `ui/build.gradle.kts`. |
| Android `compileSdk` | 37 | `core/build.gradle.kts`, `ui/build.gradle.kts`. |
| Android JVM bytecode target | 11 | Set on the Android compilations of every library module (`core/build.gradle.kts`, `ui/build.gradle.kts`, `analytics/mux/build.gradle.kts`). No project-wide Java toolchain is declared, so the plain `jvm()` target of `:core` uses the Kotlin compiler default. |
| Compose Multiplatform | 1.11.1 | `:ui` only. `:core` has no Compose dependency of any kind (`core/build.gradle.kts`). |
| AndroidX Media3 | 1.11.1 | The Android engine (`exoplayer`, `-hls`, `-dash`, `-ima`), plus `media3-ui` in `:ui`. |
| Shaka Player | 5.3.1 | Declared as an npm dependency by `:core`'s JS target, pinned as `shakaPlayer` in `gradle/libs.versions.toml`. The Kotlin/JS plugin propagates it to your `package.json` — **you do not add it yourself**. |

Building the Apple targets requires macOS. Building the Android and JS targets does not.

### Coordinates

```kotlin
implementation("io.github.nonbinarydev:outis-core:0.1.0-alpha01")
implementation("io.github.nonbinarydev:outis-ui:0.1.0-alpha01")              // optional
implementation("io.github.nonbinarydev:outis-analytics-mux:0.1.0-alpha01")   // optional
```

`io.github.nonbinarydev:outis-bom` aligns all three: import it with `project.dependencies.platform(...)` and
drop the versions from the lines above.

Nothing has been published to Maven Central yet, so these coordinates do not resolve today.

### Published targets

| Target | `outis-core` | `outis-ui` | `outis-analytics-mux` |
|---|---|---|---|
| `android` | Yes | Yes | Yes |
| `iosArm64` | Yes | Yes | Yes |
| `iosSimulatorArm64` | Yes | Yes | Yes |
| `js(IR)` | Yes | Yes | Yes |
| `jvm` | Yes (no engine — see below) | No | No |
| `wasmJs` | Yes (no engine — see below) | No | No |

Verified against `core/build.gradle.kts`, `ui/build.gradle.kts` and `analytics/mux/build.gradle.kts`.
`outis-bom` is a platform with no code; it aligns the three versions.

The target sets are **not** the same, and that asymmetry is the most common first build failure. A
shared module that declares `jvm()` or `wasmJs()` and puts `outis-ui` or `outis-analytics-mux` in
`commonMain` will fail to resolve them for those source sets. Put them in the source sets that actually
have them, or drop the extra targets.

### Targets that are absent

- **No `iosX64`.** Intel Macs cannot build or run the iOS simulator target. Apple Silicon only.
- **No macOS, tvOS, watchOS, Linux or Windows targets.**
- **`jvm` and `wasmJs` have no playback engine.** They compile the engine-agnostic API — useful for
  fast unit tests on the JVM, and for a Wasm module that shares non-playback code — but constructing
  a player throws:
  - `PlayerFactory.jvm.kt` — `UnsupportedOperationException("JVM is an API/test-only target — there is no JVM playback engine. Use a Fake in tests.")`
  - `PlayerFactory.wasmJs.kt` — `UnsupportedOperationException("Web playback runs on the JS target in v1; wasmJs engine is a follow-up.")`

  The throw happens inside the `VideoPlayer(context, config)` factory function
  (`PlayerFactory.kt`), i.e. at construction, not at first playback. Why they are published anyway:
  [ADR-0010](decisions/0010-engine-less-targets-publish-the-api.md).
- **The JS engine needs a DOM.** `:core`'s JS target declares `nodejs()` alongside `browser()`, but the
  engine creates its `<video>` element with `document.createElement` (`ShakaEngine.kt`), so it
  cannot run headless under Node.

### Transitive dependencies

`:core` declares `kotlinx-coroutines-core` as `api`, because `VideoPlayer.state` and `.events` are a
`StateFlow` and a `SharedFlow`, so it reaches your compile classpath with `outis-core`. Nothing else
needs declaring: the model's collections are plain `List` and `Map`
([ADR-0008](decisions/0008-public-collections-are-plain-list-and-map.md)). `:ui` declares
`api(project(":core"))`, so taking `outis-ui` brings `outis-core` with it.

---

## Per-platform behaviour and known gaps

### Engines

| | Android | iOS | Web |
|---|---|---|---|
| Engine | Media3 / ExoPlayer 1.11.1 | `AVPlayer` (AVFoundation) | Shaka Player 5.3.1, plus the native `<video>` for progressive files |
| `nativePlayerHandle` is | `ExoPlayer` (`ExoPlayerEngine.kt`) | `AVPlayer` (`AVPlayerEngine.kt`) | `HTMLVideoElement`, **never** `shaka.Player` (`ShakaEngine.kt`) |

The Shaka `Player` instance is not exposed at all. On web, a progressive source (an `MimeType.MP4`, a
`.mp4`/`.webm` URL, or any `MediaSource.LocalFile`) bypasses Shaka entirely and plays through the
`<video>` element — `isProgressive` at `ShakaEngine.kt`, taken at
`ShakaEngine.kt`. Several gaps below are consequences of that branch.

### Container and streaming support

| | Android | iOS | Web |
|---|---|---|---|
| Progressive MP4 | Yes | Yes | Yes (native `<video>`) |
| HLS | Yes | Yes, `avc1` only — AVPlayer rejects in-band-parameter-set `avc3` | Yes |
| DASH | Yes | **No** — AVPlayer cannot play DASH at all | Yes |
| SMIL listing HLS/DASH manifests | Yes — DASH preferred | Yes — HLS only | Yes — DASH preferred; the document host must allow CORS |

The iOS limits are AVFoundation's, not Outis's; there is no workaround short of a second engine. If
your Apple delivery is DASH, it will not play, and the failure surfaces as a source error rather than
anything that names the container. A SMIL document is resolved to one of its manifests before any
engine sees it, so on iOS a SMIL listing only DASH fails as a source error too — see
[SMIL](playback.md#smil).

### DRM

Covered in full by the [DRM guide](drm.md). The per-platform facts:

| | Android | iOS | Web |
|---|---|---|---|
| Widevine | Where the device has the CDM — effectively all of them | No CDM exists on Apple platforms | Chrome, Edge, Firefox |
| PlayReady | Only where the device ships a PlayReady CDM — most Android phones ship Widevine only | No | Edge |
| FairPlay | No | Yes, `AVContentKeySession`, on devices — not the simulator; **requires** `DrmConfig.certificateUrl` | Safari |
| Clear Key | Where the device has the CDM | Yes, over HLS | Chrome, Edge, Firefox |

`drmSupport()` reports the device's own answer, and every engine checks it before loading a protected
item: a scheme the device lacks fails at once as `Category.DRM`, naming the scheme. See
[Checking what the device supports](drm.md#checking-what-the-device-supports).

Two behaviours that are easy to misread as bugs:

- **`DrmConfig.widevineLevel` is ignored on iOS**, deliberately: FairPlay exposes no client-settable
  security level (`AVPlayerEngine.kt`).
- **`DrmConfig` is ignored for progressive web sources.** `configureDrm` is only reached on the Shaka
  branch, after the progressive early return (`ShakaEngine.onReset`). A
  DRM-protected MP4 on web is handed to the bare `<video>` element with no key system configured.

### `MediaItem` fields

| Field | Android | iOS | Web |
|---|---|---|---|
| `headers` | Yes, per-item HTTP stack (`ExoPlayerEngine.kt`) | Yes, via the `AVURLAssetHTTPHeaderFieldsKey` asset option (`AVPlayerEngine.kt`) | Shaka-managed HLS/DASH only (`ShakaRequests.kt`) — **not** progressive playback |
| `captionsDefault = FOLLOW_SYSTEM` | Yes, `CaptioningManager` (`ExoPlayerEngine.kt`) | Yes, Media Accessibility (`AVPlayerEngine.kt`) | Degrades to `OFF` (`ShakaEngine.kt`, `MediaItem.kt`) |
| `videoConstraints` resolution cap | Needs both `maxWidth` and `maxHeight` | Needs both (`AVPlayerEngine.kt`) | Each dimension applies independently (`ShakaEngine.kt`) |
| `adConfig` (`ClientSide`) | Yes | **Ignored** — no iOS code path reads `MediaItem.adConfig` | Shaka (MSE) sources only (`ShakaAds.kt`); ignored for progressive |
| `chapterThumbnails` | Yes, local files | Yes, local files | No — web parses no container chapters |
| `preferredAudioLanguage`, `preferredTextLanguage`, `startPositionMs`, `startMuted`, `loop` | Yes | Yes | Yes |

The web `headers` gap is structural: the browser's `<video>` element cannot carry custom request
headers (`ShakaEngine.startNativePlayback`). Nothing in the API signals which branch you took, so a
progressive MP4 behind a token-gated CDN silently 401s on web while working on the other two.

On iOS the `headers` map is applied to the manifest and segment requests, but **not** to the FairPlay
application-certificate GET (`FairPlayContentKeyManager.kt`), so a token-gated certificate
endpoint fails there.

### `PlayerState` fields that are not populated everywhere

| Field | Behaviour |
|---|---|
| `videoSize` | **Android only.** The iOS and web engines always write `null` (`PlayerState.kt`). Never gate layout on it. |
| `bufferedPositionMs` | On iOS it is set to `positionMs` — a v1 approximation, not a real buffer level (`AVPlayerEngine.kt`). A buffer-ahead indicator reads permanently zero there. It is also an **absolute timeline position** on every platform, not a duration ahead of `positionMs` (`PlayerState.kt`). |
| `chapters` | From the `chaptersUrl` WebVTT sidecar on all three engines, streams included; otherwise parsed from a local MP4/Matroska container on Android and iOS. Populated **asynchronously**, as a second state emission after load. |
| `audioTracks`, `textTracks` | Populated on all three engines for adaptive streams. On web, a **progressive** source leaves both empty: `loadTracks()` runs only on the Shaka branch (`ShakaEngine.kt`) and from Shaka's own track events (`ShakaEngine.kt`), neither of which happens for native `<video>` playback. Any local file on web is progressive, so its audio and subtitle controls are inert. |
| `adState` | Written by the Android and web engines for client-side ads only (`ExoPlayerEngine.kt`, `ShakaAds.kt`). Never written on iOS by the SDK; `setAdState` (`AdContainer.ios.kt`) exists solely for a host-supplied IMA adapter to push into. |
| `videoTracks` | Alternate renditions of the programme (a remaster beside an original), not the bitrate ladder. Reported on Android (one per DASH adaptation set) and web (labelled video streams); **never written on iOS**. Empty unless the manifest offers two or more labelled renditions. |

### Events

`PlayerEvent.BitrateChanged`, `BandwidthSample` and `DroppedFrames` are **Android only** — the sole
emit sites are `ExoPlayerEngine.kt`, and neither `AVPlayerEngine.kt` nor `ShakaEngine.kt`
emits any of the three. An analytics adapter built on the QoS stream attaches to nothing on two of
the three platforms. See [Analytics, QoS and the plugin seam](analytics.md).

### Ads

- **Client-side (IMA):** Android and web. On iOS `AdConfig.ClientSide` is silently ignored; the
  engine exposes `setAdContainer` / `setAdState` (`AdContainer.ios.kt`) so a host-written IMA
  coordinator can drive ads, but no such coordinator ships.
- **Server-side (SSAI):** platform-agnostic and engine-independent, because no engine reads
  `AdConfig.ServerSide` at all. `AdController` is a class your app constructs and feeds. It works
  identically on every target that has an engine, because it does not touch one.

See [Client-side ads](ads-client-side.md) and [Server-side ads](ads-server-side.md).

### The Compose UI (`outis-ui`)

`PlayerSurface` has an actual for Android, iOS and JS. Within that, three parameters are not
universal:

| Parameter | Behaviour |
|---|---|
| `surfaceType` | Android only. `SurfaceView` and `TextureView` are Android concepts; the iOS and JS actuals accept the parameter and ignore it (`PlayerSurface.ios.kt`, `PlayerSurface.js.kt`). |
| `showSubtitles` | Android only — it toggles the Media3 `SubtitleView` (`PlayerSurface.android.kt`). On iOS `AVPlayerViewController` renders cues itself; on web the `<video>` and Shaka do. |
| Keyboard shortcuts | Web only. `PlatformPlayerKeyboard` attaches a DOM `keydown` listener on JS; the Android and iOS actuals are no-ops (`PlayerKeyboard.kt`). |

`rememberPlayerWindow` — the helper that wires fullscreen and Picture-in-Picture to the host
Activity — exists **only on Android** (`PlayerWindow.android.kt`). It is not an `expect`/`actual`
pair, so there is nothing to call on iOS or web: construct `PlayerWindow` yourself and supply the
callbacks (`AVPictureInPictureController` on iOS, `requestPictureInPicture()` /
`requestFullscreen()` on web). Buttons whose capability is absent hide themselves
(`PlayerWindow.kt`).

The shipped controls have **no ad awareness** on any platform: nothing under `ui/src` reads
`adState`, and the scrubber is not locked during an ad break. That is the host app's job today.

### iOS specifics worth knowing

- The SDK configures the shared `AVAudioSession` to the playback category itself
  (`AVPlayerEngine.kt`), and deactivates it when the last engine is released
  (`AVPlayerEngine.kt`). You do not need to do it, and if you do it differently, yours may be
  overwritten.
- `isLive` is inferred as "item ready, indefinite duration, and a non-empty seekable range"
  (`AVPlayerEngine.kt`). The seekable-range term exists so an HLS VOD whose duration has not
  yet resolved does not flicker `isLive = true`.
- `MediaItem.headers` use the private-but-long-stable `AVURLAssetHTTPHeaderFieldsKey` option. Swap in
  an `AVAssetResourceLoaderDelegate` if you need a fully public path.

---

## See also

- [Troubleshooting](troubleshooting.md) — the same gaps, indexed by the symptom you actually see.
- [Playback](playback.md) — what the state and event contracts mean once you are inside them.
- [DRM](drm.md) — the full `DrmConfig` surface, including the license interceptors.
- [Getting started on Android](getting-started-android.md) ·
  [iOS](getting-started-ios.md) ·
  [Web](getting-started-web.md)
