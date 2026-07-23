[Outis](../README.md) › [Docs](README.md) › Troubleshooting

# Troubleshooting

Symptom, cause, fix. Ordered roughly by how early you are likely to hit each one.

## Build and construction

### `Unresolved reference: MediaItem` (or `VideoPlayer`, `AppContext`, `MediaSource`)

The types live in two packages and most snippets need both:

```kotlin
import dev.nonbinary.outis.core.*          // VideoPlayer, AppContext, PlayerState, PlayerEvent
import dev.nonbinary.outis.core.source.*   // MediaItem, MediaSource, MimeType, DrmConfig
```

If you are using `outis-ui` as well, add `dev.nonbinary.outis.ui.*` — but note that importing the UI
package alone does not bring the core types with it.

### `UnsupportedOperationException` the moment you construct a player

`outis-core` publishes six targets but only three carry an engine. `jvm` and `wasmJs` compile against
the full API and throw from `createPlatformPlayer` as soon as you call `VideoPlayer(...)`.

They exist so shared code can *compile* for those targets — the JVM target is what runs the unit test
suite — not so it can play video. If you need playback on desktop or Wasm, this SDK does not provide
it today.

### `Could not find :core` when wiring iOS

You have followed an instruction meant for someone building Outis from source. `:core` and `:ui` are
Gradle projects inside the Outis repository; a consumer who took the dependency from Maven Central has
no such projects.

Run `embedAndSignAppleFrameworkForXcode` on **your own** shared KMP module, which depends on
`outis-core`. See [Getting started on iOS](getting-started-ios.md).

### Dependency resolution fails from `commonMain`

`outis-core` publishes `jvm, android, iosArm64, iosSimulatorArm64, js, wasmJs`; `outis-ui` publishes
`android, iosArm64, iosSimulatorArm64, js` — **no `jvm`, no `wasmJs`**.

A `commonMain` dependency only resolves if that source set's targets are a subset of the artifact's.
A module that targets `jvm` and declares `outis-ui` in `commonMain` will not resolve. Move the
dependency into the source sets that can use it.

### Nothing builds for iOS on a non-Mac, or on an Intel Mac

Apple targets build on macOS only. The published set is `iosArm64` and `iosSimulatorArm64` — there is
no `iosX64`, so an Intel Mac cannot run the simulator build.

## Playback

### `PlayerError(category = SOURCE)` on iOS for a stream that works elsewhere

Two AVPlayer constraints account for most of these, and neither is a DRM problem:

- **HLS with `avc3`.** AVPlayer cannot play HLS carrying in-band parameter sets. The stream must be
  packaged `avc1`. There is no client-side workaround; the stream has to be re-packaged.
- **DASH at all.** AVPlayer is HLS-only. A DASH manifest fails as a source error even though the same
  content plays on Android and web.

Check the URL before assuming the licence exchange broke — a DRM failure surfaces as
`category = DRM`, not `SOURCE`.

### A progressive MP4 stalls or will not start on iOS

The file needs its `moov` atom at the front (`faststart`). A file written with the index at the end
requires the whole thing to be downloaded before playback can begin, and over a slow connection this
looks like an indefinite stall rather than an error.

Re-mux with `ffmpeg -i in.mp4 -c copy -movflags +faststart out.mp4`.

### Playback never starts and no error arrives

Check `PlayerState.playWhenReady` against `isPlaying`. They differ on purpose: `playWhenReady` is your
*intent* and flips immediately; `isPlaying` only becomes `true` once frames actually advance. If
`playWhenReady` is `true` and `isPlaying` stays `false`, the player is buffering or blocked, not
ignoring you.

On web, see [blocked autoplay](#video-stays-black-on-web-and-nothing-errors) below.

### `PlayerError(category = DRM)`

In rough order of likelihood:

- **The scheme is not supported on that platform.** Widevine has no CDM on Apple platforms; FairPlay
  is Apple and Safari only; PlayReady needs a device that actually ships a PlayReady CDM, which most
  Android phones do not. See the matrix in [Platform support](platform-support.md).
- **FairPlay without a certificate.** `DrmConfig.certificateUrl` is required for FairPlay and ignored
  by the others. Without it the key session cannot start.
- **The licence server returned an HTTP error.** The iOS FairPlay path treats any status ≥ 400 as a
  failure rather than passing an error page to AVFoundation as if it were a certificate or CKC. If
  your provider returns 200 with an error body, that check will not catch it — use
  `licenseResponseInterceptor` to inspect and reject.
- **The provider does not accept a raw challenge.** Many proxies want the challenge in a query
  parameter, or wrap the key in JSON. That is what `licenseRequestInterceptor` and
  `licenseResponseInterceptor` are for; see [DRM](drm.md).

### Chapters never appear

`PlayerState.chapters` is populated asynchronously *after* load, and only on Android and iOS — the web
engine has no local-file API and leaves it empty. It only works for `MediaSource.LocalFile`; a remote
URL is never parsed. Any parse failure yields an empty list by design, because chapters must never
break playback. See [Local files and chapters](local-files.md).

### Track selection appears to do nothing

Selection is not optimistic. The engine applies the change and re-emits the track lists with updated
`isSelected` flags — read the result from `state`, do not assume it from the call.

If you are looking for video quality or rendition selection, it is not implemented.
`PlayerState.currentTrack` and `availableTracks` are reserved and never populated. To constrain the
ABR ladder use `MediaItem.videoConstraints`.

## Web

### Video stays black on web and nothing errors

Most often blocked autoplay. Browsers refuse to start playback with sound from a non-interactive
context. Either start muted and let the user unmute, or begin playback from a real user gesture.

If it is not autoplay, check in this order:

1. **The `<video>` is not mounted.** Using `:core` alone on web, you must mount the element yourself —
   `nativePlayerHandle` returns the `HTMLVideoElement`. `:ui`'s `PlayerSurface` does it for you.
2. **The Compose canvas is covering it.** The web surface keeps the `<video>` *underneath* the Compose
   canvas and punches a transparent hole through it. If your own Compose content paints over that
   region, the video is behind it. See the web surface section in [The Compose UI](ui.md).
3. **CORS.** A cross-origin manifest or segment without permissive headers fails to load. Note the
   asymmetry: progressive playback through `<video>.src` does *not* need CORS, but MSE (which Shaka
   uses for HLS and DASH) does, as does any canvas readback.
4. **Mixed content.** An `http://` stream on an `https://` page is blocked by the browser.

### Client-side ads do not play on a progressive source on web

Web CSAI runs through Shaka's ad manager, which is only in play for adaptive sources loaded through
Shaka. A progressive file plays via `video.src` and never reaches it. Use an HLS or DASH source.

### `shaka-player` is missing from the bundle

You do not add it. `outis-core`'s JS target declares the npm dependency and the Kotlin/JS plugin
propagates it. If it is genuinely absent, the Kotlin/JS webpack build has not run — check the Gradle
build rather than adding the package by hand.

## Lifecycle

### Playback continues after the screen is gone, or the app leaks

`release()` was not called. It is idempotent, so call it freely. Under Compose it belongs in a
`DisposableEffect` keyed on the player.

### Crash or wrong-thread exception on Android

Construct the player on the main thread. Media3 pins ExoPlayer to the main `Looper` and throws on
access from elsewhere. Transport calls afterwards are safe from any thread — each engine marshals
internally — but construction is not.

### Ads state is stale after the break ends

`PlayerState.adState` is set to `null` when a break completes. If you are holding a snapshot rather
than collecting `state`, you will keep showing the last ad. For SSAI, remember that no engine drives
`AdConfig.ServerSide` for you — your app feeds the `AdController`. See
[Server-side ads](ads-server-side.md).

## Still stuck

Errors carry more than the category: `PlayerError.code` is the engine's own code and
`PlayerError.nativeCause` is the untouched platform exception. Log both before raising an issue —
`category` alone is rarely enough to diagnose anything.

[Open an issue](https://github.com/nonbinarydev/outis/issues) with the platform, the engine's code,
and whether the same stream works elsewhere.
