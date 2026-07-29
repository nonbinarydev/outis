[Outis](../README.md) › [Docs](README.md) › [Analytics](analytics.md) › Mux

# Mux Data adapter

`outis-analytics-mux` reports [Mux Data](https://www.mux.com/data) QoS for an Outis player. You add the
module and register it; it binds Mux's **own** SDK to the native player and stays out of the way.

It does not translate `PlayerEvent`s (ADR-[0003](decisions/0003-analytics-adapters-bind-natively.md)).
Mux's SDK hooks the concrete engine — Media3's `AnalyticsListener`, the `<video>` element — so it
collects rendition changes, bandwidth and dropped frames **natively**. That is the whole reason to use
the adapter rather than wiring Mux to the event stream by hand: on the event stream those three signals
are Android-only, but Mux's native monitors are not.

## What you actually get, per platform

| Platform | Mux QoS | How |
|---|:---:|---|
| **Android** | full | `data-media3` hooks the ExoPlayer instance |
| **Web** | full | `mux-embed` hooks the `<video>` element |
| **iOS** | **none yet** | the actual is a stub — see [iOS](#ios) |

On iOS, attaching the adapter is a safe no-op today: it reports nothing rather than crashing. Do not
promise iOS Mux data until the CocoaPods SDK is wired.

## Add the dependency

The Mux SDKs are **not on Maven Central** — they publish to Mux's own repository. Add it in
`settings.gradle.kts`, scoped to the Mux groups so it is never consulted for anything else:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://muxinc.jfrog.io/artifactory/default-maven-release-local") {
            content { includeGroupByRegex("com\\.mux(\\..*)?") }
        }
    }
}
```

Then depend on the adapter:

```kotlin
implementation("io.github.nonbinarydev:outis-analytics-mux:0.1.0-alpha01")
```

You do **not** add the Mux SDK yourself — the adapter pulls the right artifact (`data-media3` on
Android, `mux-embed` on web) transitively. The repository above is still required, because that is where
those transitive artifacts live.

## Register it

`MuxAnalytics` is a `PlayerComponent`. Construct it with the same `AppContext` you built the player with
and register it:

```kotlin
val player = VideoPlayer(appContext)

player.addComponent(
    MuxAnalytics(
        appContext,
        MuxConfig(
            envKey = BuildConfig.MUX_ENV_KEY,   // see "The env key" below
            playerName = "outis-web-demo",       // shown in Mux to tell your surfaces apart
            viewerId = null,                     // a stable per-viewer id if you have one — never PII
        ),
    ),
)
```

That is all. The adapter re-binds itself whenever an engine rebuilds its native player (Shaka does this
after a failed load), because it follows `PlayerHost.nativePlayerHandle` — you do not manage that.
`removeComponent`, or releasing the player, tears the Mux monitor down.

## Per-item metadata

What Mux slices sessions by comes from [`MediaItem.analytics`](analytics.md). The adapter reads it when
it binds and again on every media-item change, so each item is its own Mux view:

```kotlin
MediaItem(
    MediaSource.Url(url),
    mimeType = MimeType.HLS,
    analytics = PlaybackMetadata(
        videoId = "s3e04",                 // the key Mux groups views by — make it stable and unique
        title = "The One With The Thing",  // falls back to the display MediaMetadata.title when null
        series = "Example Show",
        streamType = StreamType.VOD,        // null lets the adapter infer VOD/LIVE from the player
        durationMs = null,                  // Mux reads duration from the player once loaded
        cdn = "fastly",
    ),
)
```

Only `envKey` is required; everything on `PlaybackMetadata` is optional and omitted when null. Viewer
identity lives on `MuxConfig`, not the item — it is session-scoped, not per-video.

**Distinct content needs distinct `videoId`.** If several items share one id, Mux collapses them into a
single asset. This matters for a "paste your own URL" surface: derive the id from the URL (or a hash of
it), not a constant, or every custom stream reports as the same video.

**DRM is picked up automatically.** The adapter reads `MediaItem.drmConfig.scheme` and reports it to Mux
as the view-level DRM type — `widevine`, `playready` or `fairplay`; clear content reports nothing. It is
not part of `PlaybackMetadata`, because it already rides on the item.

## The env key

A Mux **env key** is a *client-side* key. It ships in your APK and is **plainly visible in the web JS
bundle** — it is an environment identifier, not a credential. So keep it out of source control for
**rotation and so forks don't report to your dashboard**, not because you can hide it on web (you can't).

How the sample plumbs it (see [`sample/README.md`](../sample/README.md)):

- **Locally:** a `mux.key=…` line in the repo-root, gitignored `local.properties`.
- **CI:** the `MUX_ENVIRONMENT` repository *variable* — a variable, not a secret, since the key is public
  in the bundle — passed to the build as `-Pmux.key=…`. (A dotted property name can't go through the
  `ORG_GRADLE_PROJECT_*` env-var form, whose names must be valid env vars, so CI passes it on the command
  line.)
- A generated constant bakes the value in: a KMP library has no `BuildConfig`, so the sample's build
  writes `SampleConfig.MUX_ENV_KEY` from the Gradle property, defaulting to empty.
- **Skip the adapter when it is blank**, so a clone with no key still plays video — the adapter is
  opt-in, not load-bearing.

```kotlin
val muxKey = SampleConfig.MUX_ENV_KEY   // BuildConfig.MUX_ENV_KEY in a plain Android app
if (muxKey.isNotBlank()) {
    player.addComponent(MuxAnalytics(appContext, MuxConfig(envKey = muxKey)))
}
```

## Consent

A public page reporting to Mux sets identifiers, so EU visitors bring GDPR duties. At minimum a privacy
note; possibly a consent gate before the adapter initialises. Decide this before a demo goes live, not
after.

## iOS

The iOS actual returns `null` — attaching the adapter does nothing there yet. The real Mux AVPlayer SDK
is CocoaPods/SPM and cannot be built or verified on a Linux CI runner, and this repository has no iOS
host to test against. It is a known limitation, tracked in [#42](https://github.com/nonbinarydev/outis/issues/42).

## See also

- [Analytics, QoS and the plugin seam](analytics.md) — the seam this is built on, and the roll-your-own path
- [ADR-0003](decisions/0003-analytics-adapters-bind-natively.md) — why adapters bind natively
