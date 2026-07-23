<p align="start"><picture>
<source media="(prefers-color-scheme: dark)" srcset="docs/img/lockup-dark-1273w.png">
<img src="docs/img/lockup-light-1273w.png" width="480" alt="Outis — a Kotlin Multiplatform video player">
</picture></p>

[![Maven Central](https://img.shields.io/maven-central/v/io.github.nonbinarydev/outis-core?label=maven%20central)](https://central.sonatype.com/artifact/io.github.nonbinarydev/outis-core) [![Licence](https://img.shields.io/badge/licence-Apache--2.0-blue)](LICENSE) [![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-blue)](https://kotlinlang.org) ![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS%20%7C%20Web-blue)

***No one player.*** A Kotlin Multiplatform video player for **Android, iOS and Web**: one
engine-agnostic API, plus an optional Compose Multiplatform UI. Write your playback logic once; each
platform runs its native engine underneath — so there is no single player here, which is rather the point.

| Platform | Engine |
|---|---|
| Android | Media3 / ExoPlayer 1.10.1 |
| iOS | AVFoundation `AVPlayer` |
| Web (JS) | Shaka Player 4.11.2, over an HTML `<video>` |

**Status: `0.1.0-alpha01`.** Not yet published to Maven Central; the coordinates below will not resolve until the first release.

## Capability matrix

| | Android | iOS | Web |
|---|---|---|---|
| Progressive **MP4** | ✅ | ✅ (needs *faststart*) | ✅ |
| **HLS** | ✅ | ✅ (`avc1` only — not in-band-param `avc3`) | ✅ |
| **DASH** | ✅ | ❌ (AVPlayer is HLS-only) | ✅ |
| **Live** (HLS/DASH) | ✅ | ✅ (HLS) | ✅ |
| **Widevine** DRM | ✅ | ❌ | ✅ (Chrome/Edge/Firefox) |
| **PlayReady** DRM | ✅ (device CDM only) | ❌ | ✅ (Edge) |
| **FairPlay** DRM | ❌ | ✅ | ✅ (Safari) |
| Audio / subtitle track selection | ✅ | ✅ | ✅ |
| Client-side ads (IMA) | ✅ | ❌ | ✅ (adaptive sources only) |
| Server-side ads (SSAI) | app-driven | app-driven | app-driven |
| Embedded chapters (MP4 / Matroska) | ✅ | ✅ | ❌ |
| Picture-in-Picture | ✅ (`rememberPlayerWindow`) | host-supplied | host-supplied |

The DRM split is the unavoidable one: Widevine has no CDM on Apple platforms, FairPlay is Apple-only. SSAI is
*app-driven*: no engine reads `AdConfig.ServerSide` — the stitched stream plays unchanged and you drive the shared
`AdController`. On web the shared Compose overlay composites over the engine's `<video>` rather than falling back to
native controls ([how](docs/ui.md#web-surface)). **Not yet:** video-rendition (quality) selection — `currentTrack` and
`availableTracks` are reserved and never populated, so the track-selection row means audio and subtitles only; cap the
ladder with `MediaItem.videoConstraints`. Full per-platform behaviour, gaps and target sets → **[docs/platform-support.md](docs/platform-support.md)**.

## Requirements

Android `minSdk` 24 and `compileSdk` 36; JVM target 11; Kotlin 2.4.10; Compose Multiplatform 1.11.1 for `:ui`. Apple
targets build on macOS only, and `iosArm64` / `iosSimulatorArm64` are the only ones published — there is no `iosX64`, so
Intel Macs cannot run the simulator build.

## Install

```kotlin
// outis-core publishes: jvm, android, iosArm64, iosSimulatorArm64, js, wasmJs
implementation("io.github.nonbinarydev:outis-core:0.1.0-alpha01")
// outis-ui publishes: android, iosArm64, iosSimulatorArm64, js — no jvm, no wasmJs
implementation("io.github.nonbinarydev:outis-ui:0.1.0-alpha01")   // optional Compose UI
```

Declare these in a shared `commonMain` only if that module's targets are a subset of the artifact's — a `commonMain`
dependency on `outis-ui` from a module that also targets jvm or wasmJs will not resolve. Setup: [Android](docs/getting-started-android.md) · [iOS](docs/getting-started-ios.md) · [Web](docs/getting-started-web.md).

## Quick start

```kotlin
import dev.nonbinary.outis.core.*
import dev.nonbinary.outis.core.source.*
// Each platform entry point builds the AppContext — AppContext(context.applicationContext) on Android,
// AppContext() on iOS and web — and constructs the player on the main/UI thread.
fun start(appContext: AppContext): VideoPlayer {
    val player = VideoPlayer(appContext)
    player.setMediaItem(
        MediaItem(MediaSource.Url("https://example.com/master.m3u8"), mimeType = MimeType.HLS),
        autoPlay = true,
    )
    // Fire-and-forget: results land on `state` (conflated — drive UI off it) and `events` (drive analytics off it).
    player.play()
    player.seekTo(30_000)
    player.setVolume(0.5f)   // 0f..1f, independent of setMuted
    player.pause()
    return player               // ...and player.release() when done — idempotent
}
```

## Documentation

| Guide | What it covers |
|---|---|
| Getting started: [Android](docs/getting-started-android.md) · [iOS](docs/getting-started-ios.md) · [Web](docs/getting-started-web.md) | From an empty project to a playing video. |
| [Playback](docs/playback.md) | Sources, transport, state and events, tracks, live, quality caps, errors. |
| [Compose UI](docs/ui.md) | `PlayerView`, the controls overlay, four tiers of customisation. |
| [DRM](docs/drm.md) | Widevine, PlayReady, FairPlay, and the license request/response interceptors. |
| [Ads — client-side](docs/ads-client-side.md) | IMA on Android and Web, and what `AdState` actually carries. |
| [Ads — server-side](docs/ads-server-side.md) | `AdController`, MediaTailor avails, SCTE-35 cues, beaconing. |
| [Local files and chapters](docs/local-files.md) | On-device playback, embedded chapters and thumbnails. |
| [Analytics and QoS](docs/analytics.md) | `PlayerComponent`, the native handle, which events fire where. |
| [Platform support](docs/platform-support.md) | Requirements, published targets, per-platform gaps. |
| [Troubleshooting](docs/troubleshooting.md) | Symptom, cause, fix. |
| Module reference | [`outis-core`](core/README.md) · [`outis-ui`](ui/README.md) — the per-module API notes that ship to Maven Central. |
| Full index | [docs/README.md](docs/README.md) |

## Threading and lifecycle

Construct `VideoPlayer(...)` on the main/UI thread; call its methods from anywhere (each engine marshals
internally); always `release()` when done — it is idempotent, and under Compose belongs in a `DisposableEffect`.

## Project

Alpha, solo-maintained, and the published contract is deliberately additive — [CHANGELOG.md](CHANGELOG.md) records what
changed and which changes need a consumer recompile. Building and contributing: [CONTRIBUTING.md](CONTRIBUTING.md). Bugs and
questions: [GitHub issues](https://github.com/nonbinarydev/outis/issues). Vulnerabilities: [SECURITY.md](SECURITY.md).
Apache-2.0 ([LICENSE](LICENSE)). Repository: <https://github.com/nonbinarydev/outis>.
