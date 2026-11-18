[Outis](../README.md) › Sample

# Sample

A Compose Multiplatform application that plays the published catalogue through `PlayerView`, or any
stream you paste in. It runs on Android, iOS and the web from one shared `App()`.

The **Videos** dialog holds two ways of choosing what to play: the catalogue, fetched at runtime from
`catalogue.json` so the stream list changes without a rebuild, and a custom-stream form that builds a
`MediaItem` by hand — URL, container, DRM scheme, licence server and FairPlay certificate. **Player
settings** holds the privacy & consent controls (the player's `PlayerConfig` is otherwise fixed at
construction). **Diagnostics** shows the live player event log — startup, buffering, track changes and
errors — read straight off the SDK's event stream.

## The application

`:sample` is a Kotlin Multiplatform module with a single screen shared across all three platforms: a
`VideoPlayer` rendered through the SDK's own `PlayerView` and `DefaultControls`, the Outis lockup above
it, a status line below, and the three overlay dialogs described above. Fullscreen and Picture-in-Picture
are wired through `rememberSampleWindow`. On first launch it plays the first catalogue item.

When the catalogue cannot be fetched the sample falls back to Big Buck Bunny over HLS — a master playlist
that is all `avc1`, with no in-band parameter sets and no DRM, so Media3, AVPlayer and Shaka can all
decode it. That makes the fallback a smoke test: if it does not play, the problem is the integration
rather than the content.

A status line under the video reports the `PlaybackState`, because "nothing rendered" and "rendered but
never became ready" look identical on a black surface.

Server-side ad entries get an `AdController` per item (`ads/ServerSideAds.kt`): the catalogue's own
breaks, or, for `scte35MasterUrl`, avails polled from the live playlist and placed at the playhead where
they first appear. The sample drives it with the player's position and hands it to the controls state,
so `DefaultControls` shows the countdown, the skip button and the cue-points.

### Running it

**Web** — the shared UI in a browser:

```bash
./gradlew :sample:jsBrowserDevelopmentRun   # local dev server (hot reload)
./gradlew :sample:jsBrowserDistribution     # the production bundle published to /demo/
```

The production bundle takes several minutes — Compose for web ships skiko, which is large.

**Android** — `:sample:android` is a thin `com.android.application` host whose `MainActivity` presents
the shared `App()`; install it on a device or emulator:

```bash
./gradlew :sample:android:installDebug
```

**Android TV** — the same APK. Its `TvActivity` is listed only by the TV launcher (`LEANBACK_LAUNCHER`, with
a banner) and presents `TvApp()`: catalogue rails to move through with the D-pad, the player full screen,
and Back to return. It is catalogue-only, since typing a URL with a D-pad is no way to try a player, and
it runs no analytics, so it asks for no consent. One APK rather than a second application module, so the
Compose-resources staging in `sample/android/build.gradle.kts` is not duplicated. To try the TV screen
without a television, open the web build with `?tv` and use the arrow keys, Enter and Escape.

**iOS** — `sample/iosApp` is an Xcode host that presents the shared `App()` through
`mainViewController()`. It links Mux's iOS SDK via CocoaPods, so run `pod install` in `sample/iosApp`
once, then open **`iosApp.xcworkspace`** (not the `.xcodeproj`) and Run (simulator or device). The Kotlin
framework is built and embedded by a run-script phase (`:sample:embedAndSignAppleFrameworkForXcode`), so
there is no manual Gradle step. For a **real device**,
copy `sample/iosApp/Configuration/Local.xcconfig.example` to `Local.xcconfig` and set `DEVELOPMENT_TEAM`
to your Apple Team ID, then sign into Xcode — signing is Automatic. `Local.xcconfig` is git-ignored, so
your team ID is never committed.

## Analytics and consent

The sample doubles as the reference wiring for the optional [Mux Data](../docs/analytics-mux.md) QoS
adapter (`:analytics:mux`), the [Firebase / GA4](../docs/analytics-firebase.md) usage adapter
(`:analytics:firebase`), Crashlytics on the app hosts, and the consent gate all of them need. None of it
is part of the SDK — `:core` and `:ui` ship no analytics dependency; this is the app's own integration.

### Enabling Mux

The adapter is off unless a build supplies a Mux env key, so a plain checkout runs with analytics
disabled. Supply the key — the client-side *environment* key, not a Mux API token — by precedence:

- **Local:** a `mux.key=…` line in the repo-root `local.properties` (git-ignored).
- **Command line / CI:** `-Pmux.key=…`. [`pages.yml`](../.github/workflows/pages.yml) reads it from the
  `MUX_ENVIRONMENT` repository **variable** — a variable, not a secret, because the key ships in the web
  bundle and is public by design.

An empty key bakes an empty constant and the adapter is simply never attached.

### Firebase

Usage analytics (GA4) and, on Android and iOS, crash reports (Crashlytics). A plain checkout is
Firebase-free: each platform turns it on only when given its own configuration, none of which is
committed, because each identifies a real Firebase project.

- **Android:** download the app's `google-services.json` from the Firebase console (package
  `dev.nonbinary.outis.sample`) into `sample/android/`; `google-services.json.example` shows its shape.
  Only then does the build apply the Google services and Crashlytics plugins.
- **iOS:** put the app's `GoogleService-Info.plist` in `sample/iosApp/iosApp/` (see the `.example`) and
  run `pod install`. A build phase bundles the file when it is there, and the app configures Firebase only
  when it finds it. Crash reports arrive unsymbolicated until the Crashlytics dSYM upload is set up, which
  this host does not do yet.
- **Web:** a GA4 measurement id, by the same precedence as the Mux key: a `ga4.id=G-…` line in
  `local.properties`, or `-Pga4.id=G-…`. [`pages.yml`](../.github/workflows/pages.yml) reads it from the
  `GA4_MEASUREMENT_ID` repository variable. The build rejects anything that is not shaped like `G-…`,
  since it becomes part of a script URL.

Then, with **Marketing** consent, the adapter reports `video_start`, `video_complete` and `video_error`,
and the sample adds `select_content` when the viewer picks a video: the catalogue id only, never a
custom stream's URL. On the web, gtag.js is not even loaded before that consent; a revoke silences it at
once. Crash reports follow **Performance** consent from the next launch, because Crashlytics applies a
change then; reports from a session without consent are deleted, not sent.

The public demo's [privacy notice](../site/privacy.html) describes all of this for visitors, and the
consent dialog links to it. It is a draft for the operator to complete, not legal advice.

### Consent

The demo reports to third-party analytics, so it carries the consent gate a public deployment needs —
and, being a reference, it demonstrates the *mechanism* rather than hardcoding a legal judgement. Consent
is modelled as **purpose categories**, not per-SDK:

- **Essential** — always on: playback, and the on-device diagnostics log (which never leaves the device).
- **Performance** — Mux QoS, and Crashlytics in the apps. Gated by default.
- **Marketing** — usage analytics (GA4). Always gated.

An adapter attaches only while its category is granted (`addComponent` on grant, `removeComponent` on
revoke), and nothing collects before a first-run choice: Firebase's own collection is off by default in
the Android manifest and the iOS `Info.plist`, and consent turns it on. The choice persists per platform (localStorage /
SharedPreferences / `NSUserDefaults`) so it survives a reload, and is revisitable from **Player settings →
Privacy & data**.

Whether Performance *requires* consent is a single, documented, flippable line
(`ConsentCategory.PERFORMANCE.requiresConsent`). It ships `true` — the safe default, since Mux Data stores
a viewer id, which ePrivacy governs — and an operator whose legal basis treats QoS as legitimate interest
can flip it. **This is the sample's posture, not legal guidance:** the classification is the operator's
and their counsel's call.

## The catalogue
`catalogue.json` is the stream list the sample application plays. It is kept here and published to
GitHub Pages, so the list can change without rebuilding and redistributing the app:

```
https://nonbinarydev.github.io/outis/catalogue.json
```

Pages is the host rather than `raw.githubusercontent.com` because it returns
`content-type: application/json` as well as `access-control-allow-origin: *`; raw returns the CORS
header but types everything `text/plain`. The web build fetches this from a browser, so both matter.

Publishing runs from [`.github/workflows/pages.yml`](../.github/workflows/pages.yml) on any push to
`main` that touches this file, and it will not publish a catalogue that fails
[`validate-catalogue.py`](validate-catalogue.py). Run that locally before pushing:

```bash
python3 sample/validate-catalogue.py
```

This file lives here because the catalogue is the part that was expensive to assemble and cheap to lose:
every entry has been checked against the claim it makes, and several widely-cited streams turned out to
be mislabelled or dead.

## What it is for

Each entry exercises something specific about the SDK, and a good number exist to demonstrate a
*failure* rather than a success — a graceful DRM error where no CDM exists, a codec error where the
platform cannot decode. An SDK that reports those cleanly is worth showing.

## Schema

Top level:

| Field | Meaning |
| --- | --- |
| `version` | Schema version. An application should ignore a catalogue whose version it does not understand and fall back to its bundled copy. |
| `updated` | ISO date, informational. |
| `posters` | Named poster URLs, referenced by items so a shared image is declared once. |
| `rails` | Ordered groups, each with `id`, `title`, an optional `note`, and `items`. |

Each item:

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Stable, unique across the whole catalogue. |
| `title` | yes | Compact card label. |
| `label` | yes | Fuller label for the hero and overlay. |
| `url` | yes | Manifest or file URL. |
| `mimeType` | yes | `HLS`, `DASH`, `MP4` or `WEBM`, mapping to `MimeType`. |
| `tags` | no | Badge strings. |
| `poster` | no | Key into `posters`. |
| `tint` | no | Hex colour for a solid tile when there is no poster. Mutually exclusive with `poster`. |
| `drm` | no | `scheme` (`WIDEVINE`, `PLAYREADY`, `FAIRPLAY`, `CLEARKEY`), `licenseServerUrl`, `certificateUrl` for FairPlay, and `keys` (hex key id to hex key) for Clear Key, which has no server. |
| `ads` | no | `type: "clientSide"` with `adTagUri`, or `type: "serverSide"` with `breaks`: each an `id`, a `startMs` on the stream's timeline, and `ads`, each an `id`, a `durationMs`, and optionally a `title` and a `skipOffsetMs`. |
| `scte35MasterUrl` | no | Playlist to poll for live SCTE-35 cue-points. |
| `chaptersUrl` | no | WebVTT chapters sidecar. |
| `chapters` | no | Chapters declared inline, each `startMs`, optional `title` and `endMs`. They win over `chaptersUrl`, so keep the sidecar for sample builds that predate the field. |
| `thumbnailsUrl` | no | WebVTT trickplay-thumbnails sidecar. |
| `subtitles` | no | Subtitle files beside the stream, each a `url` and optionally a `language`, a `label` and a `format` (`WEBVTT` or `SRT`; without one the URL's extension decides). The first is the default. Shown on all three platforms; on iOS the player UI draws them. |
| `note` | no | Why this entry exists, or what it will not do on which platform. |

**DRM is declarative on purpose.** `DrmConfig` also carries `licenseRequestInterceptor` and
`licenseResponseInterceptor` lambdas for providers that do not accept a raw challenge. Those cannot be
expressed in JSON and none of these streams need them, so the schema covers only scheme and URLs and the
application constructs the `DrmConfig`.

## Two rules for consuming it

**Bundle a copy and fall back to it.** A sample that shows an error screen when the network is unhelpful
is a bad demo, and demos get shown on conference wifi.

**Check `version` before parsing.** The point of loading remotely is that already-distributed builds
keep working; that only holds if an old build refuses a catalogue it cannot read rather than crashing on
it.

## Notes worth keeping

Findings that cost real effort and are easy to lose:

- **Verify 4K claims.** Many listing sites label 1080p streams as 4K. The two here were checked for
  genuine 3840x2160.
- **The only 4K that plays on every engine is H.264 over HLS.** Most UHD ships as HEVC, VP9 or AV1, and
  no single engine set decodes all of those. `tos-4k` is that rare combination.
- **Poster images need CORS on web.** Compose on web renders through a canvas, and the browser blocks
  pixel readback of a cross-origin image without a permissive header — so a poster without CORS gives
  blank cards on web while working on Android and iOS. Progressive *video* through `<video>.src` does
  not need CORS; only canvas readback does.
- **The old `commondatastorage.googleapis.com` Big Buck Bunny mp4 is 403.** Anonymous access was
  removed. It is still widely cited.
- **No clean public Big Buck Bunny stream exists for Widevine or FairPlay.** Every one is clear except
  Microsoft's PlayReady-only asset, checked across the Shaka demo catalogue, Axinom, Bitmovin, EZDRM and
  Unified Streaming.
