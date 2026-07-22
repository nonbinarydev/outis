[Outis](../README.md) › Docs

# Outis documentation

Outis is a Kotlin Multiplatform video player: `outis-core` is the engine-agnostic player (Media3 on
Android, `AVPlayer` on iOS, Shaka Player on Web) and `outis-ui` is an optional Compose Multiplatform
chrome on top of it. Both are at `0.1.0-alpha01` and neither has been published to Maven Central yet.

Start with the getting-started page for your platform, then move to the reference guide for whatever
you are building. Every page states platform differences explicitly, because that is this SDK's whole
shape — and every per-platform capability claim lives in one file,
[Platform support](platform-support.md), so the others link to it rather than restating it.

## Start

Get to a playing video on one platform. These pages assume nothing but an existing project.

| Guide | What it covers |
|---|---|
| [Getting started on Android](getting-started-android.md) | The dependency line, the `INTERNET` permission, the Activity attributes fullscreen and PiP need, and a complete Activity that plays a stream and releases the player. |
| [Getting started on iOS](getting-started-ios.md) | The two routes that work: a KMP shared module that declares its own framework binary, or the prebuilt XCFramework for Swift-only apps. Framework search paths, hosting the Compose surface, and the ATS keys for HTTP test streams. |
| [Getting started on Web (JS)](getting-started-web.md) | The three prerequisites that each independently block a first run — the Compose JS canvas Gradle property, the host page, and mounting the engine's `<video>` element yourself if you use `:core` alone. |

## Build

Per-subsystem references. Read them in any order; each is self-contained.

| Guide | What it covers |
|---|---|
| [Playback](playback.md) | The main integration narrative, as one chain: construct a `VideoPlayer`, load a `MediaItem`, drive transport, observe `state` and `events`, select audio and text tracks, handle live streams, cap quality, and handle errors. |
| [The Compose UI (`outis-ui`)](ui.md) | `PlayerView`, `PlayerSurface` and the controls overlay, from a single line to a fully custom chrome, plus the input model and how the web surface composites Compose over the `<video>`. |
| [DRM](drm.md) | Widevine, PlayReady and FairPlay: what each platform can actually do, the full `DrmConfig` surface, and the license request and response interceptors for providers whose proxy does not take a raw challenge. |
| [Client-side ads (IMA)](ads-client-side.md) | `AdConfig.ClientSide`, which is implemented on Android and Web only, and what each of those two engines actually writes into `PlayerState.adState` — the field sets differ. |
| [Server-side ads (SSAI)](ads-server-side.md) | `AdController`: the cue-point tracker your app constructs and feeds for stitched streams, with the MediaTailor and SCTE-35 parsers and the beaconing contract. No engine reads `AdConfig.ServerSide` for you. |
| [Local files and chapters](local-files.md) | `MediaSource.LocalFile`, embedded chapter parsing for mp4 and Matroska, `PlayerState.chapters` (populated asynchronously, after load, on Android and iOS), and chapter thumbnails with their size budget. |
| [Analytics, QoS and the plugin seam](analytics.md) | `PlayerComponent` and `PlayerHost`, the `nativePlayerHandle` escape hatch and what it actually is per platform, and which QoS events each engine emits — three of them are Android-only. |

## Reference

| Guide | What it covers |
|---|---|
| [Platform support, requirements and known gaps](platform-support.md) | Toolchain and dependency versions, the exact target set each artifact publishes, and every known per-platform gap in one place. The single source of truth for capability claims; the README's matrix is a summary of this page. |
| [Troubleshooting](troubleshooting.md) | Symptom, cause and fix for the failure modes that are easy to hit and hard to diagnose: construction throwing on unsupported targets, a black video on web, blocked autoplay, iOS refusing a stream that is not actually a DRM problem, and unresolved references from the quick start. |

## Maintainer docs

[Releasing](maintainers/releasing.md) is the publishing runbook, executable only on the machine that
holds the signing key; contributor-facing build notes are in [CONTRIBUTING.md](../CONTRIBUTING.md).

---

[Outis on GitHub](https://github.com/nonbinarydev/outis) ·
[Security policy](../SECURITY.md) ·
[Changelog](../CHANGELOG.md) ·
Licensed under [Apache-2.0](../LICENSE)
