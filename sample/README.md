[Outis](../README.md) › Sample

# Sample

A minimal Compose Multiplatform application that plays one stream through `PlayerView`, and the
catalogue a fuller sample would draw on.

## The application

`:sample` is a Kotlin Multiplatform module deliberately kept to one screen: construct a `VideoPlayer`,
load a single item, render it with the SDK's own `DefaultControls`. No catalogue browsing, no custom
chrome, no third-party design system — what a consumer gets out of the box, shown unmodified.

It plays Big Buck Bunny over HLS. That stream is chosen because its master playlist is all `avc1`, with
no in-band parameter sets, so Media3, AVPlayer and Shaka can all decode it, and it carries no DRM. If it
does not play, the problem is the integration rather than the content — which is what makes it useful as
a smoke test.

A status line under the video reports the `PlaybackState`, because "nothing rendered" and "rendered but
never became ready" look identical on a black surface.

### Running it

The web target is the one that runs today:

```bash
./gradlew :sample:jsBrowserRun        # local dev server
./gradlew :sample:jsBrowserDistribution   # the bundle published to /demo/
```

The production bundle takes several minutes — Compose for web ships skiko, which is large.

Android and iOS targets **compile**, which is what makes this a portability check rather than a
web-only sample, but neither has a host application yet: Android needs an `com.android.application`
module and iOS needs an Xcode project. `mainViewController()` in `iosMain` is the entry point an iOS
host would present.

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

The application itself does not exist yet — see
[#2](https://github.com/nonbinarydev/outis/issues/2). This file is here because the catalogue is the
part that was expensive to assemble and cheap to lose: every entry has been checked against the claim it
makes, and several widely-cited streams turned out to be mislabelled or dead.

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
| `mimeType` | yes | `HLS`, `DASH` or `MP4`, mapping to `MimeType`. |
| `tags` | no | Badge strings. |
| `poster` | no | Key into `posters`. |
| `tint` | no | Hex colour for a solid tile when there is no poster. Mutually exclusive with `poster`. |
| `drm` | no | `scheme` (`WIDEVINE`, `PLAYREADY`, `FAIRPLAY`), `licenseServerUrl`, and `certificateUrl` for FairPlay. |
| `ads` | no | `type: "clientSide"` with `adTagUri`, or `type: "serverSide"` with `breaks`. |
| `scte35MasterUrl` | no | Playlist to poll for live SCTE-35 cue-points. |
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
