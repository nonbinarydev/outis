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
| Kotlin | 2.4.10 | Multiplatform plugin, applied by both modules. |
| Android Gradle Plugin | 9.1.1 | Both modules use the `com.android.kotlin.multiplatform.library` plugin. |
| Gradle | 9.6.1 | `gradle/wrapper/gradle-wrapper.properties`; only relevant if you build Outis from source. |
| Android `minSdk` | 24 | `core/build.gradle.kts:38-41`, `ui/build.gradle.kts:33-36`. |
| Android `compileSdk` | 36 | `core/build.gradle.kts:34-37`, `ui/build.gradle.kts:29-32`. |
| Android JVM bytecode target | 11 | Set on the Android compilations of both modules (`core/build.gradle.kts:43-49`, `ui/build.gradle.kts:38-44`). No project-wide Java toolchain is declared, so the plain `jvm()` target of `:core` uses the Kotlin compiler default. |
| Compose Multiplatform | 1.11.1 | `:ui` only. `:core` has no Compose dependency of any kind (`core/build.gradle.kts:79-88`). |
| AndroidX Media3 | 1.10.1 | The Android engine (`exoplayer`, `-hls`, `-dash`, `-ima`), plus `media3-ui` in `:ui`. |
| Shaka Player | 4.11.2 | Declared as an npm dependency by `:core`'s JS target (`core/build.gradle.kts:131`). The Kotlin/JS plugin propagates it to your `package.json` — **you do not add it yourself**. |

Building the Apple targets requires macOS. Building the Android and JS targets does not.

### Coordinates

```kotlin
implementation("io.github.nonbinarydev:outis-core:0.1.0-alpha01")
implementation("io.github.nonbinarydev:outis-ui:0.1.0-alpha01")   // optional
```

Neither artifact has been published to Maven Central yet, so these coordinates do not resolve today.

### Published targets

| Target | `outis-core` | `outis-ui` |
|---|---|---|
| `android` | Yes | Yes |
| `iosArm64` | Yes | Yes |
| `iosSimulatorArm64` | Yes | Yes |
| `js(IR)` | Yes | Yes |
| `jvm` | Yes (no engine — see below) | No |
| `wasmJs` | Yes (no engine — see below) | No |

Verified against `core/build.gradle.kts:29-64` and `ui/build.gradle.kts:26-53`.

The two target sets are **not** the same, and that asymmetry is the most common first build failure. A
shared module that declares `jvm()` or `wasmJs()` and puts `outis-ui` in `commonMain` will fail to
resolve `outis-ui` for those source sets. Put `outis-ui` in the source sets that actually have it, or
drop the extra targets.

### Targets that are absent

- **No `iosX64`.** Intel Macs cannot build or run the iOS simulator target. Apple Silicon only.
- **No macOS, tvOS, watchOS, Linux or Windows targets.**
- **`jvm` and `wasmJs` have no playback engine.** They compile the engine-agnostic API — useful for
  fast unit tests on the JVM, and for a Wasm module that shares non-playback code — but constructing
  a player throws:
  - `PlayerFactory.jvm.kt:13-16` — `UnsupportedOperationException("JVM is an API/test-only target — there is no JVM playback engine. Use a Fake in tests.")`
  - `PlayerFactory.wasmJs.kt:13-14` — `UnsupportedOperationException("Web playback runs on the JS target in v1; wasmJs engine is a follow-up.")`

  The throw happens inside the `VideoPlayer(context, config)` factory function
  (`PlayerFactory.kt:164`), i.e. at construction, not at first playback.
- **The JS engine needs a DOM.** `:core`'s JS target declares `nodejs()` alongside `browser()`, but the
  engine creates its `<video>` element with `document.createElement` (`ShakaEngine.kt:179`), so it
  cannot run headless under Node.

### Transitive dependencies you must declare yourself

`:core` declares `kotlinx-coroutines-core` and `kotlinx-collections-immutable` with
`implementation(...)`, not `api(...)` (`core/build.gradle.kts:79-88`). Both appear in the **public**
API signature:

- `VideoPlayer.state: StateFlow<PlayerState>` and `VideoPlayer.events: SharedFlow<PlayerEvent>`
  (`VideoPlayer.kt:36,42`) — kotlinx-coroutines.
- `PlayerState.audioTracks` / `textTracks` / `chapters` / `availableTracks`, all
  `ImmutableList<…>` (`PlayerState.kt:99,112,117,132`); `PlayerEvent.TracksChanged`'s two lists
  (`PlayerEvent.kt:199,201`); `MediaItem.headers: ImmutableMap<String, String>`
  (`MediaItem.kt:36`); `DrmConfig.licenseRequestHeaders: ImmutableMap<String, String>`
  (`DrmConfig.kt:49`) — kotlinx-collections-immutable.

An implementation-scoped dependency is not on a consumer's compile classpath, so until this is
changed to `api(...)` you must add both to your own build. Without them you cannot collect `state`,
and you cannot construct the `persistentMapOf(...)` value that `MediaItem.headers` or
`DrmConfig.licenseRequestHeaders` require.

```kotlin
// build.gradle.kts — your shared module
commonMain.dependencies {
    implementation("io.github.nonbinarydev:outis-core:0.1.0-alpha01")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.1")
}
```

`:ui` has the same issue for `kotlinx-collections-immutable`: it is `implementation`-scoped there too
(`ui/build.gradle.kts:65`), and `TrackList` takes an `ImmutableList<MediaTrack>` as its first
parameter (`TrackControls.kt:125-131`). `:ui` does declare `api(project(":core"))`
(`ui/build.gradle.kts:58`), so taking `outis-ui` brings `outis-core` with it.

This is a packaging defect, not a design choice. Expect the scopes to change before 1.0; the
declarations above are harmless once they do.

---

## Per-platform behaviour and known gaps

### Engines

| | Android | iOS | Web |
|---|---|---|---|
| Engine | Media3 / ExoPlayer 1.10.1 | `AVPlayer` (AVFoundation) | Shaka Player 4.11.2, plus the native `<video>` for progressive files |
| `nativePlayerHandle` is | `ExoPlayer` (`ExoPlayerEngine.kt:505`) | `AVPlayer` (`AVPlayerEngine.kt:195`) | `HTMLVideoElement`, **never** `shaka.Player` (`ShakaEngine.kt:190`) |

The Shaka `Player` instance is not exposed at all. On web, a progressive source (an `MimeType.MP4`, a
`.mp4`/`.webm` URL, or any `MediaSource.LocalFile`) bypasses Shaka entirely and plays through the
`<video>` element — `isProgressive` at `ShakaEngine.kt:1001-1010`, taken at
`ShakaEngine.kt:448-451`. Several gaps below are consequences of that branch.

### Container and streaming support

| | Android | iOS | Web |
|---|---|---|---|
| Progressive MP4 | Yes | Yes | Yes (native `<video>`) |
| HLS | Yes | Yes, `avc1` only — AVPlayer rejects in-band-parameter-set `avc3` | Yes |
| DASH | Yes | **No** — AVPlayer cannot play DASH at all | Yes |

The iOS limits are AVFoundation's, not Outis's; there is no workaround short of a second engine. If
your Apple delivery is DASH, it will not play, and the failure surfaces as a source error rather than
anything that names the container.

### DRM

Covered in full by the [DRM guide](drm.md). The per-platform facts:

| | Android | iOS | Web |
|---|---|---|---|
| Widevine | Yes, probed via `MediaDrm.isCryptoSchemeSupported` (`ExoPlayerEngine.kt:768`) | No CDM exists on Apple platforms | Chrome, Edge, Firefox |
| PlayReady | Only where the device ships a PlayReady CDM (`ExoPlayerEngine.kt:769`, `DrmConfig.kt:129-133`) — most Android phones ship Widevine only | No | Edge |
| FairPlay | No — `unsupportedDrmLabel` returns `"FairPlay"` unconditionally (`ExoPlayerEngine.kt:767`) | Yes, `AVContentKeySession`; **requires** `DrmConfig.certificateUrl` | Safari |

Two behaviours that are easy to misread as bugs:

- **`DrmConfig.widevineLevel` is ignored on iOS**, deliberately: FairPlay exposes no client-settable
  security level (`AVPlayerEngine.kt:242-243`).
- **`DrmConfig` is ignored for progressive web sources.** `configureDrm` is only reached on the Shaka
  branch (`ShakaEngine.kt:454`), after the progressive early return at `ShakaEngine.kt:448-451`. A
  DRM-protected MP4 on web is handed to the bare `<video>` element with no key system configured.

### `MediaItem` fields

| Field | Android | iOS | Web |
|---|---|---|---|
| `headers` | Yes, per-item HTTP stack (`ExoPlayerEngine.kt:239`) | Yes, via the `AVURLAssetHTTPHeaderFieldsKey` asset option (`AVPlayerEngine.kt:227-236`) | Shaka-managed HLS/DASH only (`ShakaEngine.kt:330`) — **not** progressive playback |
| `captionsDefault = FOLLOW_SYSTEM` | Yes, `CaptioningManager` (`ExoPlayerEngine.kt:212`) | Yes, Media Accessibility (`AVPlayerEngine.kt:673`) | Degrades to `OFF` (`ShakaEngine.kt:795-797`, `MediaItem.kt:103-107`) |
| `videoConstraints` resolution cap | Needs both `maxWidth` and `maxHeight` | Needs both (`AVPlayerEngine.kt:255-256`) | Each dimension applies independently (`ShakaEngine.kt:802-804`) |
| `adConfig` (`ClientSide`) | Yes | **Ignored** — no iOS code path reads `MediaItem.adConfig` | Shaka (MSE) sources only (`ShakaEngine.kt:466`); ignored for progressive |
| `chapterThumbnails` | Yes, local files | Yes, local files | No — web reads no chapters |
| `preferredAudioLanguage`, `preferredTextLanguage`, `startPositionMs`, `startMuted`, `loop` | Yes | Yes | Yes |

The web `headers` gap is structural: the browser's `<video>` element cannot carry custom request
headers (`ShakaEngine.kt:481-483`). Nothing in the API signals which branch you took, so a
progressive MP4 behind a token-gated CDN silently 401s on web while working on the other two.

On iOS the `headers` map is applied to the manifest and segment requests, but **not** to the FairPlay
application-certificate GET (`FairPlayContentKeyManager.kt:180-181`), so a token-gated certificate
endpoint fails there.

### `PlayerState` fields that are not populated everywhere

| Field | Behaviour |
|---|---|
| `videoSize` | **Android only.** The iOS and web engines always write `null` (`PlayerState.kt:80-84`). Never gate layout on it. |
| `bufferedPositionMs` | On iOS it is set to `positionMs` — a v1 approximation, not a real buffer level (`AVPlayerEngine.kt:759`). A buffer-ahead indicator reads permanently zero there. It is also an **absolute timeline position** on every platform, not a duration ahead of `positionMs` (`PlayerState.kt:38-44`). |
| `chapters` | Android and iOS only, both parsing the local container. Always empty on web. Populated **asynchronously**, as a second state emission after load (`ExoPlayerEngine.kt:610`, `AVPlayerEngine.kt:605`). |
| `audioTracks`, `textTracks` | Populated on all three engines for adaptive streams. On web, a **progressive** source leaves both empty: `loadTracks()` runs only on the Shaka branch (`ShakaEngine.kt:462`) and from Shaka's own track events (`ShakaEngine.kt:349-351`), neither of which happens for native `<video>` playback. Any local file on web is progressive, so its audio and subtitle controls are inert. |
| `adState` | Written by the Android and web engines for client-side ads only (`ExoPlayerEngine.kt:645-649`, `ShakaEngine.kt:597-602`). Never written on iOS by the SDK; `AVPlayerEngine.kt:367` exists solely for a host-supplied IMA adapter to push into. |
| `currentTrack`, `availableTracks` | **Never written on any platform.** Reserved slots for video-rendition (quality) selection, which is not implemented. Audio and text track selection *is* implemented everywhere — these two fields are about video renditions only. |

### Events

`PlayerEvent.BitrateChanged`, `BandwidthSample` and `DroppedFrames` are **Android only** — the sole
emit sites are `ExoPlayerEngine.kt:485,488,494`, and neither `AVPlayerEngine.kt` nor `ShakaEngine.kt`
emits any of the three. An analytics adapter built on the QoS stream attaches to nothing on two of
the three platforms. See [Analytics, QoS and the plugin seam](analytics.md).

### Ads

- **Client-side (IMA):** Android and web. On iOS `AdConfig.ClientSide` is silently ignored; the
  engine exposes `setAdContainer` / `updateAdState` (`AVPlayerEngine.kt:371,883`) so a host-written
  IMA coordinator can drive ads, but no such coordinator ships.
- **Server-side (SSAI):** platform-agnostic and engine-independent, because no engine reads
  `AdConfig.ServerSide` at all. `AdController` is a class your app constructs and feeds. It works
  identically on every target that has an engine, because it does not touch one.

See [Client-side ads](ads-client-side.md) and [Server-side ads](ads-server-side.md).

### The Compose UI (`outis-ui`)

`PlayerSurface` has an actual for Android, iOS and JS. Within that, three parameters are not
universal:

| Parameter | Behaviour |
|---|---|
| `surfaceType` | Android only. `SurfaceView` and `TextureView` are Android concepts; the iOS and JS actuals accept the parameter and ignore it (`PlayerSurface.ios.kt:47`, `PlayerSurface.js.kt:61`). |
| `showSubtitles` | Android only — it toggles the Media3 `SubtitleView` (`PlayerSurface.android.kt:72`). On iOS `AVPlayerViewController` renders cues itself; on web the `<video>` and Shaka do. |
| Keyboard shortcuts | Web only. `PlatformPlayerKeyboard` attaches a DOM `keydown` listener on JS; the Android and iOS actuals are no-ops (`PlayerKeyboard.kt:13-27`). |

`rememberPlayerWindow` — the helper that wires fullscreen and Picture-in-Picture to the host
Activity — exists **only on Android** (`PlayerWindow.android.kt:47`). It is not an `expect`/`actual`
pair, so there is nothing to call on iOS or web: construct `PlayerWindow` yourself and supply the
callbacks (`AVPictureInPictureController` on iOS, `requestPictureInPicture()` /
`requestFullscreen()` on web). Buttons whose capability is absent hide themselves
(`PlayerWindow.kt:16-17`).

The shipped controls have **no ad awareness** on any platform: nothing under `ui/src` reads
`adState`, and the scrubber is not locked during an ad break. That is the host app's job today.

### iOS specifics worth knowing

- The SDK configures the shared `AVAudioSession` to the playback category itself
  (`AVPlayerEngine.kt:850-859`), and deactivates it when the last engine is released
  (`AVPlayerEngine.kt:422`). You do not need to do it, and if you do it differently, yours may be
  overwritten.
- `isLive` is inferred as "item ready, indefinite duration, and a non-empty seekable range"
  (`AVPlayerEngine.kt:761-766`). The seekable-range term exists so an HLS VOD whose duration has not
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
