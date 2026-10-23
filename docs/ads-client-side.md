[Outis](../README.md) › [Docs](README.md) › Client-side ads

# Client-side ads (IMA)

Client-side ad insertion (CSAI) means the player fetches a VAST/VMAP tag itself and stitches the ads
into playback. Outis models it as one field on a `MediaItem` — `AdConfig.ClientSide(adTagUri)` — and
mirrors whatever the platform IMA SDK reports into `PlayerState.adState`.

**It is implemented on Android and on Web only, and on Web only for Shaka (MSE) sources.
`AdConfig.ClientSide` is silently ignored on iOS — no iOS code path reads `MediaItem.adConfig` — and
silently ignored for progressive sources on Web (`ShakaEngine.kt`). Setting it on
those paths produces no ad, no error and no log line: content simply plays.**

Server-side ads are a completely separate mechanism with a separate API — see
[Server-side ads (SSAI)](ads-server-side.md). No engine reads `AdConfig.ServerSide`.

---

## What ships

| Platform | Ad SDK | Where it lives | What you write |
|---|---|---|---|
| **Android** | Media3 IMA extension (`media3-exoplayer-ima`, pulling `com.google.ads.interactivemedia.v3:interactivemedia`) | Inside the engine (`ExoPlayerEngine`) | Nothing. Set `adConfig` and, if you are not using `:ui`, hand the engine an ad view. |
| **Web** | IMA HTML5, via `shaka.ads.AdManager` | Inside the engine (`ShakaEngine`) | Add the `ima3.js` script tag to your host page. |
| **iOS** | Google IMA iOS SDK | Nowhere — no adapter ships | All of it. The engine exposes three bridge functions; the coordinator that drives them is yours. |

The asymmetry is not neglect. Media3-IMA is a plain Gradle dependency callable from Kotlin, so it can
live inside `:core`. IMA **iOS** is a `UIView`/`UIViewController`-centric Objective-C framework that
has to come in through CocoaPods and cinterop, which would make `:core` pod-dependent and stop it
being distributable as a plain XCFramework. So the iOS ad SDK stays in the integration layer.

Full per-platform capability claims live in
[Platform support](platform-support.md#ads); this page is about the API and the behaviour.

---

## Configuring an item

`AdConfig` is a sealed interface in `dev.nonbinary.outis.core.ads`
([`Ads.kt`](../core/src/commonMain/kotlin/dev/nonbinary/outis/core/ads/Ads.kt)):

```kotlin
sealed interface AdConfig {
    data class ServerSide(val breaks: List<AdBreak>) : AdConfig   // stream already stitched
    data class ClientSide(val adTagUri: String) : AdConfig        // VAST/VMAP tag, player stitches
}
```

`adTagUri` is handed straight to the platform IMA SDK. Outis does not parse it: macro substitution,
redirects and VAST wrapper resolution are all IMA's job. A VMAP tag carries its own pre/mid/post-roll
schedule; a bare VAST tag yields a single pre-roll.

Attach it to the item you load — this is the whole integration on Android and Web:

```kotlin
import dev.nonbinary.outis.core.ads.AdConfig
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType

player.setMediaItem(
    MediaItem(
        MediaSource.Url("https://example.com/master.m3u8"),
        mimeType = MimeType.HLS,
        adConfig = AdConfig.ClientSide("https://pubads.g.doubleclick.net/gampad/ads?…&output=vast"),
    ),
    autoPlay = true,
)
```

`adConfig` is read at load time only. Changing it means calling `setMediaItem` again.

---

## Android

You add no dependency: `outis-core` already declares `media3-exoplayer-ima` and it reaches your app
transitively.

On `setMediaItem`, when the item carries a `ClientSide` config, the engine builds an `ImaAdsLoader`
once and binds it to the ExoPlayer instance *before* the ad media source is set — Media3's required
order (`ExoPlayerEngine.bindImaAdsLoader`). The tag rides on the Media3 `MediaItem` as an
`AdsConfiguration`, and the loader plus the ad view are handed to the media-source factory via
`setLocalAdInsertionComponents` (`ExoPlayerEngine.buildMediaSource`). On `release()` the loader is
unbound and released.

### The ad view

IMA renders its own skip button, countdown and click-through affordance, and it needs a `ViewGroup`
laid over the video to render them into. The engine takes one through:

```kotlin
// androidMain, package dev.nonbinary.outis.core.ads
@ExperimentalPlayerApi
fun VideoPlayer.setAdContainer(container: ViewGroup?, obstructions: List<AdObstruction> = emptyList())

@ExperimentalPlayerApi
class AdObstruction(val view: View, val purpose: Purpose, val reason: String? = null) {
    enum class Purpose { CONTROLS, CLOSE_AD, OTHER, NOT_VISIBLE }
}
```

`obstructions` are the views drawn over the video — your controls, a close button — that IMA's
Open Measurement viewability check should treat as friendly rather than count against the ad.

If you use `:ui`, this is already done: `PlayerSurface` registers its Media3 `PlayerView`'s ad view
group and overlay views on attach and clears them on release (`PlayerSurface.android.kt`).

If you are on `:core` only, call it yourself with a `ViewGroup` from your own layout. You need no
Media3 dependency to do so: the bridge takes only Android framework types and Outis's own
`AdObstruction`. Until a container is set the engine substitutes an unattached `FrameLayout`, so ad
media still plays but IMA's ad UI has nowhere to appear.

Both are `@ExperimentalPlayerApi` (see [Opting in](ui.md#opting-in)) — they may change before 1.0.

### Ad errors

The engine's IMA error listener only logs, under the Logcat tag `PlayerIMA`
(`ExoPlayerEngine.bindImaAdsLoader`). An ad failure never becomes a `PlayerError` and never appears on
`PlayerState.error` or the `events` stream on any platform — content plays on. If ad requests fail on
a machine where they work elsewhere, check for network-level filtering of the ad host before
suspecting the integration.

---

## Web

The web engine drives CSAI through Shaka's ad manager, which wraps the IMA HTML5 SDK. On a successful
Shaka load of a `ClientSide` item it hands the ad manager its container and wires the ad events once per
Player instance, then issues the request; Shaka rebuilds its IMA client for each asset itself
(`ShakaAds.request`).

Two requirements you will not discover from the API.

**1. The host page must load `ima3.js`.** `shaka.ads` needs the global `google.ima`, and the engine
constructs the request with `js("new google.ima.AdsRequest()")` (`ShakaAds.request`). Add the
script tag to your `index.html`:

```html
<script src="https://imasdk.googleapis.com/js/sdkloader/ima3.js"></script>
```

Without it the ad path throws where it is called, inside the load success handler
(`ShakaEngine.kt`). Nothing is surfaced as a `PlayerError`, so the symptom is content playing
with no ads and an exception in the browser console.

**2. The ad overlay has to sit above the Compose canvas.** The web surface draws the whole Compose UI
into one `<canvas>` at `z-index: 1` over the `<video>` at `z-index: 0`
(`PlayerSurface.js.kt`). The engine creates its own ad-container `<div>`, appends
it to `document.body` and styles it `z-index: 2` with `pointer-events: none`
(`ShakaAds.kt`). On every reconcile tick, while a break is active, it aligns the
div over the `<video>`, re-asserts `z-index: 2` — the surface's `raiseComposeHostsAbove` sets
`z-index: 1` on *every* direct child of `body`, including this div — and flips `pointer-events` to
`auto` so IMA's skip and click-through are reachable. Outside a break only `pointer-events` is set
back to `none`; the geometry is left as it was (`ShakaAds.sync`).

The practical consequence: if your app renders its own DOM chrome above `z-index: 2`, it will cover
IMA's ad UI.

### Progressive sources are not covered

`ShakaAds.request` is only reached in the Shaka `load()` success handler
(`ShakaEngine.kt`). Progressive sources — `MimeType.MP4`, any `.mp4`/`.webm` URL, and every
`MediaSource.LocalFile` (`ShakaEngine.kt`) — take the native `<video>.src` branch and
return before that point. `AdConfig.ClientSide` on those is a silent no-op. If you need
web CSAI, deliver HLS or DASH.

---

## iOS

No IMA adapter ships. `AVPlayerEngine` never reads `MediaItem.adConfig`, so setting
`AdConfig.ClientSide` does nothing at all on iOS today.

What does exist is the seam an adapter would need — three `@ExperimentalPlayerApi` functions in
`dev.nonbinary.outis.core.ads` (`AdContainer.ios.kt`), each a no-op on any player that is not the
AVPlayer engine:

```kotlin
fun VideoPlayer.setAdState(adState: AdState?)                 // push ad state into PlayerState.adState
fun VideoPlayer.setAdContainer(controller: UIViewController?) // register the hosting view controller
fun VideoPlayer.adContainer(): UIViewController?              // read it back to anchor IMA's ad UI
```

`:ui` already registers the surface: `PlayerSurface` calls `setAdContainer(controller)` with its
`AVPlayerViewController` on attach and `setAdContainer(null)` on release (`PlayerSurface.ios.kt`),
so an adapter can read `player.adContainer()` for both the container view and the presenting view
controller. The content `AVPlayer` is reachable through `player.nativePlayerHandle`.

Writing the coordinator means adding the Kotlin CocoaPods plugin and the
`GoogleAds-IMA-iOS-SDK` pod to *your* module (not `:core`), then driving IMA's content pause/resume
model against the bridge above. That work is out of scope for this SDK today and nothing in this
repository has been built or run against the IMA iOS pod, so no code for it is published here.

---

## What lands in `PlayerState.adState`

`adState` is `null` whenever no ad is active. Both engines write it in exactly three places: once
when the break opens, once when each ad starts, and once to clear it. **Nothing updates it while an
ad plays.** Every field below is therefore a snapshot taken at ad start, not a live value.

| `AdState` field | Android | Web |
|---|---|---|
| `isInAdBreak` | `true` for the whole break | `true` for the whole break |
| `currentBreak` | Always `null` | Always `null` |
| `currentAd` | Set at `STARTED`; `null` in the pause-requested state that precedes it | Set at `AD_STARTED`; `null` if the event carries no ad |
| `adIndexInBreak` | `adPodInfo.adPosition - 1`, else `0` | `getPositionInSequence() - 1`, floored at `0` |
| `adCountInBreak` | `adPodInfo.totalAds`, else `0` | `getSequenceLength()` |
| `adPositionMs` | Always `0` | Always `0` |
| `adRemainingMs` | The ad's **full duration**, set once | `getRemainingTime()` sampled once at ad start, so also ≈ the full duration |
| `canSkip` | Always `false` | `isSkippable() && canSkipNow()` sampled once at ad start — so `false` for any ad with a non-zero skip offset, and it never flips |
| `cuePoints` | Always empty | Always empty |

Sources: `ExoPlayerEngine.kt` and `ShakaAds.kt`.

Inside `currentAd` (`Ad`, [`Ads.kt`](../core/src/commonMain/kotlin/dev/nonbinary/outis/core/ads/Ads.kt)):

| `Ad` field | Android | Web |
|---|---|---|
| `id` | IMA `adId`, else `""` | Shaka `getAdId()`, else `""` |
| `durationMs` | IMA `duration` × 1000 | `getDuration()` × 1000 |
| `title` | IMA `title` | `getTitle()` |
| `skipOffsetMs` | IMA `skipTimeOffset` × 1000 when ≥ 0, else `null` (unskippable) | `getTimeUntilSkippable()` at ad start when the ad is skippable, else `null` |
| `clickThroughUrl` | **Never populated** | **Never populated** |

Two things follow that are easy to get wrong.

`Ad.clickThroughUrl` is always `null` under CSAI on both platforms. Do not render a click-through
affordance from it — IMA draws its own, in the ad view on Android and in the ad container `<div>` on
Web.

`adRemainingMs` does not count down, and `adPositionMs` does not advance. If you want a countdown you
have to derive it yourself from wall-clock time since the ad started, and treat `adRemainingMs` as
the ad's length.

### Clearing

| Trigger | Android | Web |
|---|---|---|
| Content resume requested | Clears | Clears |
| All ads completed | Clears | Clears |
| Ad error | Not handled — only logged; state clears when IMA subsequently requests content resume | Clears |
| `setMediaItem` for a new item | **Not cleared** | **Not cleared** |
| `release()` | Player is gone | Player is gone |

Neither engine resets `adState` in `setMediaItem` (`ExoPlayerEngine.kt`,
`ShakaEngine.kt`), so a break that never reaches a resume or completion event leaves a stale
`adState` on the next item. If your chrome gates on `isInAdBreak`, clear or ignore it on
`PlayerEvent.MediaItemTransition`.

### Position during an ad, on Android

The Android engine deliberately freezes `positionMs` and `bufferedPositionMs` while
`ExoPlayer.isPlayingAd` is true, because ExoPlayer reports ad time there and `PlayerState.positionMs`
is defined as content time (`ExoPlayerEngine.kt`). The web engine has no
equivalent guard — it writes `video.currentTime` unconditionally on every reconcile tick
(`ShakaEngine.kt`) — so do not rely on `positionMs` during a web ad break.

---

## What you still have to write

The shipped Compose chrome has **no ad awareness on any platform**. Nothing under `ui/src` reads
`adState`, and the scrubber is not disabled during a break. Gating the UI is the host app's job:

```kotlin
import dev.nonbinary.outis.core.ads.AdState

scope.launch {
    player.state.collect { s ->
        val ad: AdState? = s.adState
        val inAd = ad?.isInAdBreak == true
        scrubberEnabled = !inAd
        adLabel = if (inAd && ad.adCountInBreak > 0) {
            "Ad ${ad.adIndexInBreak + 1} of ${ad.adCountInBreak}"
        } else {
            null
        }
    }
}
```

Gate on `isInAdBreak`, never on `currentAd != null` — the pause-requested state that opens a break
has `isInAdBreak = true` and no ad yet. See [The Compose UI](ui.md) for building a custom overlay,
and [Playback](playback.md) for the rest of the state contract.

Also yours: blocking seeks through unwatched ads (Outis does not clamp seeks for CSAI — that logic
exists only in the SSAI `AdController`), any ad-break markers on the scrubber, and any analytics
beaconing beyond what IMA reports to its own back end.

---

**See also:** [Server-side ads (SSAI)](ads-server-side.md) · [Playback](playback.md) ·
[The Compose UI](ui.md) · [Platform support](platform-support.md) ·
[Troubleshooting](troubleshooting.md)
