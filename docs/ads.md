# Ads integration guide (SSAI + CSAI)

How Outis models ads, and how to wire **client-side ad insertion (CSAI)** per platform. Clear
playback is covered in [playback.md](playback.md).

The SDK exposes **one shared ads contract** in `commonMain`; the per-platform ad SDKs plug in behind it,
so the Compose chrome reacts identically everywhere.

---

## 1. The shared contract

Attach ads to a `MediaItem` via `AdConfig`:

```kotlin
sealed interface AdConfig {
    data class ServerSide(val breaks: List<AdBreak>) : AdConfig   // SSAI — stream already stitched
    data class ClientSide(val adTagUri: String) : AdConfig        // CSAI — VAST/VMAP tag, player stitches
}
```

Both feed the same output: `PlayerState.adState: AdState?` (`null` ⇒ no ad active). The chrome reads it to
lock the scrubber and draw ad UI — it never cares which kind of ad produced it.

- **SSAI** (`ServerSide`) is fully cross-platform today: the stream plays unchanged and the shared
  `AdController` tracks cue-points over the timeline. See the sample's SCTE-35 / simulated cards.
- **CSAI** (`ClientSide`) uses the platform's IMA SDK to fetch + stitch ads from a VAST/VMAP tag.

### CSAI status by platform

| Platform | Ad SDK | Where it lives | Status |
|---|---|---|---|
| **Android** | Media3 IMA extension (`media3-exoplayer-ima`) | **engine-owned** (`ExoPlayerEngine`) | ✅ shipped + device-verified |
| **iOS** | Google IMA iOS SDK (`GoogleInteractiveMediaAds`) | **integration layer** (see §3) | bridge ✅ ; IMA adapter = this guide |
| **Web** | IMA HTML5 via `shaka.ads.AdManager` | **engine-owned** (`ShakaEngine`) | ✅ implemented + compiles (runtime needs a browser) |

The split exists because Media3-IMA is a plain Gradle dependency callable from Kotlin, whereas IMA **iOS**
is a UIView/`UIViewController`-centric Objective-C framework that must come in via CocoaPods + cinterop.

---

## 2. Android CSAI (reference — already works)

```kotlin
MediaItem(
    MediaSource.Url("https://…/master.m3u8"),
    mimeType = MimeType.HLS,
    adConfig = AdConfig.ClientSide("https://pubads.g.doubleclick.net/gampad/ads?…&output=vast&…"),
)
```

The engine owns the lifecycle: it builds an `ImaAdsLoader`, adds the tag as an `AdsConfiguration`, wires
`setLocalAdInsertionComponents`, and maps IMA's `AdEventType` into `PlayerState.adState`. The `:ui`
`PlayerView` (an `AdViewProvider`) is pushed into the engine via `VideoPlayer.setAdViewProvider(view)`.
Build note: the IMA extension needs **core-library desugaring** enabled in the consuming app module.

Use Google's `media.exolist.json` sample tags. A request that returns `AdError 1005 / cause-6` is an
**HTTP rejection of the ad request** — usually network/DNS ad-filtering of `doubleclick.net`, not code.

---

## 3. iOS CSAI — Google IMA iOS adapter

### 3.1 Architecture

This iOS app is a thin SwiftUI shell around Compose Multiplatform — the player surface
(`AVPlayerViewController`) is created **inside** Compose/KMP, so there is no Swift app layer to host IMA.
The adapter therefore lives in **Kotlin/Native (`iosMain`)** and calls the IMA SDK via cinterop.

To keep `:core` **pod-free and binary-distributable as the `Outis` XCFramework**, the IMA
dependency is isolated in the **integration layer** (a dedicated `ima-ios` module or the app's `iosMain`),
not in core. Core only exposes the contract; the adapter drives it through one public bridge:

```kotlin
// core/iosMain — already implemented + compiled green
fun VideoPlayer.updateAdState(adState: AdState?)            // push IMA ad state into PlayerState.adState
fun VideoPlayer.setAdContainer(controller: UIViewController?) // :ui surface registers its AVPlayerViewController
fun VideoPlayer.adContainer(): UIViewController?             // adapter reads it back to anchor IMA's ad UI
```

These are the iOS parallel of Android's `setAdViewProvider`. `PlayerSurface.ios` already calls
`setAdContainer(controller)` with its `AVPlayerViewController` (and clears it on release), so the adapter
just reads `player.adContainer()` — no manual view/VC plumbing. The content `AVPlayer` is reached via
`player.nativePlayerHandle as AVPlayer`; the adapter pauses/resumes it directly (IMA iOS uses a
**content pause/resume** model rather than a stitched timeline, so no position-guarding is needed).

```
 MediaItem(adConfig = ClientSide(tag))
        │  app reads player.state.mediaItem.adConfig
        ▼
 ImaIosAdCoordinator (integration layer, cinterop)
   • IMAAdsLoader.requestAds(tag, contentPlayhead = AVPlayer, container = adView, vc = rootVC)
   • IMAAdsManagerDelegate:
       didRequestContentPause → player.pause()
       didReceive(STARTED)    → player.updateAdState(AdState(isInAdBreak=true, …))
       didRequestContentResume→ player.play()
       allAdsCompleted        → player.updateAdState(null)
        │
        ▼
 PlayerState.adState  →  your controls overlay (scrubber locks; IMA draws its own skip/countdown)
```

### 3.2 Build setup (integration module / app `iosMain`)

Add the Kotlin CocoaPods plugin **only to the module that owns the adapter** (NOT core):

```kotlin
plugins { kotlin("native.cocoapods") }

kotlin {
    cocoapods {
        ios.deploymentTarget = "14.0"
        pod("GoogleAds-IMA-iOS-SDK") { version = "~> 3.20" }   // module name: GoogleInteractiveMediaAds
    }
}
```

Then `pod install` (needs CocoaPods on a Mac). Cinterop symbols import from `cocoapods.GoogleInteractiveMediaAds.*`.

### 3.3 The coordinator

> ⚠️ **Not compile-verified in this repo** — it depends on the IMA pod, which needs a Mac + `pod install`
> + Xcode. The logic + flow are correct; **double-check the exact cinterop binding names** (Kotlin/Native
> maps ObjC selectors/enums predictably but verify against the generated bindings). This is a drop-in start.

```kotlin
@OptIn(ExperimentalForeignApi::class)
class ImaIosAdCoordinator(
    private val player: VideoPlayer,
) : NSObject(), IMAAdsLoaderDelegateProtocol, IMAAdsManagerDelegateProtocol {

    private val adsLoader = IMAAdsLoader(settings = null)
    private var adsManager: IMAAdsManager? = null

    init { adsLoader.delegate = this }

    /** Call when a ClientSide item is loaded. */
    fun requestAds(adTagUri: String) {
        val avPlayer = player.nativePlayerHandle as? AVPlayer ?: return
        val container = player.adContainer() ?: return       // the :ui AVPlayerViewController (setAdContainer)
        val display = IMAAdDisplayContainer(
            adContainer = container.view,                     // ad UI is anchored over the video surface
            viewController = container,
        )
        val playhead = IMAAVPlayerContentPlayhead(avPlayer = avPlayer)
        val request = IMAAdsRequest(
            adTagUrl = adTagUri,
            adDisplayContainer = display,
            contentPlayhead = playhead,
            userContext = null,
        )
        adsLoader.requestAdsWithRequest(request)
    }

    // IMAAdsLoaderDelegate
    override fun adsLoader(loader: IMAAdsLoader, adsLoadedWithData: IMAAdsLoadedData) {
        adsManager = adsLoadedWithData.adsManager?.also {
            it.delegate = this
            it.initializeWithAdsRenderingSettings(null)
        }
    }
    override fun adsLoader(loader: IMAAdsLoader, failedWithErrorData: IMAAdLoadingErrorData) {
        // VAST_EMPTY_RESPONSE / network → no ad; content plays. Log + clear.
        player.updateAdState(null)
    }

    // IMAAdsManagerDelegate
    override fun adsManager(adsManager: IMAAdsManager, didReceiveAdEvent event: IMAAdEvent) {
        when (event.type) {
            IMAAdEventType.kIMAAdEvent_STARTED -> player.updateAdState(event.toAdState())
            IMAAdEventType.kIMAAdEvent_ALL_ADS_COMPLETED -> player.updateAdState(null)
            else -> Unit   // LOADED, QUARTILE, COMPLETE, SKIPPED, TAPPED, CLICKED …
        }
    }
    override fun adsManager(adsManager: IMAAdsManager, didReceiveAdError error: IMAAdError) {
        player.updateAdState(null)
    }
    override fun adsManagerDidRequestContentPause(adsManager: IMAAdsManager) {
        player.updateAdState(AdState(isInAdBreak = true))   // entering break
        player.pause()
    }
    override fun adsManagerDidRequestContentResume(adsManager: IMAAdsManager) {
        player.updateAdState(null)
        player.play()
    }

    fun start() { adsManager?.start() }
    fun release() { adsManager?.destroy(); adsLoader.contentComplete() }
}

private fun IMAAdEvent.toAdState(): AdState {
    val ad = this.ad
    val pod = ad?.adPodInfo
    return AdState(
        isInAdBreak = true,
        currentAd = ad?.let {
            Ad(
                id = "",
                durationMs = (it.duration * 1000).toLong(),
                title = it.adTitle,
                skipOffsetMs = it.skipTimeOffset.takeIf { o -> o >= 0.0 }?.let { o -> (o * 1000).toLong() },
            )
        },
        adIndexInBreak = ((pod?.adPosition ?: 1).toInt()) - 1,
        adCountInBreak = (pod?.totalAds ?: 0).toInt(),
        adRemainingMs = ((ad?.duration ?: 0.0) * 1000).toLong(),
    )
}
```

### 3.4 IMA iOS `AdEventType` → `AdState`

| IMA iOS event | Action |
|---|---|
| `adsManagerDidRequestContentPause` | `updateAdState(isInAdBreak=true)` + `player.pause()` |
| `STARTED` | `updateAdState(adState with ad title/duration/skip from adPodInfo)` |
| `adsManagerDidRequestContentResume` | `updateAdState(null)` + `player.play()` |
| `ALL_ADS_COMPLETED` | `updateAdState(null)` |
| `didReceiveAdError` / loader `failedWith` | `updateAdState(null)` (content plays; ad is skipped) |
| `LOADED`,`COMPLETE`,`QUARTILE`,`SKIPPED`,`TAPPED`,`CLICKED` | (optional analytics — no state change) |

### 3.5 Wiring it up

1. **Ad container + presenting VC.** ✅ Handled by the SDK: `PlayerSurface.ios` pushes its
   `AVPlayerViewController` into the engine via `setAdContainer`, so the coordinator gets both the ad
   container view (`container.view`) and the presenting view controller from `player.adContainer()` — no
   manual plumbing, and IMA's ad UI is correctly anchored over the video.
2. **Trigger.** When a `ClientSide` item loads (observe `player.state` for
   `mediaItem.adConfig is AdConfig.ClientSide`), build the coordinator, `requestAds(adTagUri)`, and call
   `start()` once content is ready to play.
3. **Lifecycle.** `release()` the coordinator on item change / dispose.

### 3.6 What's verified vs. needs a Mac

- ✅ **Verified here:** the shared contract (`AdConfig.ClientSide`, `AdState`, `PlayerState.adState`) and
  the full iOS SDK-side seam — `VideoPlayer.updateAdState(...)`, `setAdContainer(...)`/`adContainer()`, and
  `PlayerSurface.ios` registering its `AVPlayerViewController` — all compile green on `iosSimulatorArm64`
  (+ Android + JS + tests). The chrome already reacts to `adState` identically to Android.
- 🖥 **Needs a Mac (Xcode + CocoaPods):** the IMA pod, the cinterop bindings, the coordinator above, and
  on-device verification that the ad renders + content pause/resume works. Run on a network **without**
  ad-filtering (see the Android note re: `doubleclick.net`).

---

## 4. Web CSAI — Shaka IMA HTML5 (implemented)

The web engine (`ShakaEngine`) owns CSAI via Shaka's built-in ad manager (which wraps the IMA HTML5 SDK),
mirroring Android — same `AdConfig.ClientSide` in, same `PlayerState.adState` out. The shared
**"CSAI · IMA" sample card already drives web** (its `ClientSide` config is in `commonMain`).

Flow, on a successful Shaka (MSE) content load:

```kotlin
val adManager = shakaPlayer.getAdManager()
adManager.initClientSide(adContainer, video)        // once per Player
// + addEventListener(AD_CONTENT_PAUSE_REQUESTED / AD_STARTED / AD_CONTENT_RESUME_REQUESTED /
//                    ALL_ADS_COMPLETED / AD_ERROR) → _state.copy(adState = …)
val request = js("new google.ima.AdsRequest()"); request.adTagUrl = adTagUri
adManager.requestClientSideAds(request)
```

`shaka.extern.IAd` → `AdState`: `getDuration`/`getRemainingTime` (×1000 → ms), `getTitle`,
`getTimeUntilSkippable` (→ `skipOffsetMs`), `getPositionInSequence`/`getSequenceLength`
(→ `adIndexInBreak`/`adCountInBreak`), `isSkippable` + `canSkipNow` (→ `canSkip`).

### Two web requirements

1. **The IMA SDK script.** `shaka.ads` needs the global `google.ima`, so the host page must load
   `https://imasdk.googleapis.com/js/sdkloader/ima3.js` — add the `<script>` tag to your app's
   `index.html`.
2. **The ad-overlay vs. the Compose hole-punch.** The web surface draws the Compose UI into one `<canvas>`
   (z-index 1) over the `<video>` (z-index 0). The engine creates an **ad container `<div>`** and, on its
   reconcile poll, keeps it aligned over the `<video>` and flips it to `z-index 2` + `pointer-events: auto`
   **only during an ad break** — so IMA's skip/click-through is reachable above the canvas, while outside
   ads the container is inert and the chrome receives touches. (The scrubber is locked during ads anyway.)

### Verified vs. needs a browser

- ✅ **Verified here:** the `ShakaEngine` integration + `AdState` mapping + ad-container lifecycle compile
  green on `compileKotlinJs` (+ all other targets).
- 🌐 **Needs runtime (a browser):** that IMA actually fetches/renders the ad, the container alignment/z-index
  behaves, and skip works. Use a network **without** ad-filtering (same `doubleclick.net` caveat as §2).
