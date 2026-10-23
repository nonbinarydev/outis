[Outis](../README.md) › [Docs](README.md) › DRM

# DRM guide

How to play Widevine-, PlayReady- and FairPlay-protected streams with Outis. Clear playback is covered
in [playback.md](playback.md); this guide is only the DRM layer.

DRM is attached to a `MediaItem` through a single `DrmConfig`, which the SDK maps onto each engine's
native key system — Media3 `DrmConfiguration`, Shaka `drm.*`, AVFoundation `AVContentKeySession`.

---

## 1. What works where

DRM key systems are platform-bound — this is the one split you can't engineer away:

| Scheme | Android (Media3) | iOS (AVPlayer) | Web — Chrome/Edge/FF | Web — Safari |
|---|---|---|---|---|
| **Widevine** | ✅ | ❌ | ✅ | ❌ |
| **PlayReady** | ✅ (device CDM only) | ❌ | ✅ (Edge) | ❌ |
| **FairPlay** | ❌ | ✅ (devices, not the simulator) | ❌ | ✅ |
| **Clear Key** | ✅ (device CDM) | ✅ (HLS only) | ✅ | ❌ |

Practical takeaways:

- **Apple platforms (iOS + Safari) → FairPlay.** They have no Widevine/PlayReady CDM.
- **Everything else → Widevine** (or PlayReady on Microsoft stacks). PlayReady on Android is probed
  against the device with `MediaDrm.isCryptoSchemeSupported` and most phones ship Widevine only, so
  treat the Android PlayReady cell as "if the device has the CDM".
- A scheme the device can't satisfy fails the item at once, before anything is fetched, with
  `Category.DRM` and a message naming the scheme — the same on every platform. Ask in advance with
  [`drmSupport()`](#checking-what-the-device-supports).
- **Web: DRM is ignored for progressive sources.** `ShakaEngine` routes `MimeType.MP4`, any
  `MediaSource.LocalFile`, and `.mp4`/`.webm` URLs to the browser's `<video>` element and returns
  before `configureDrm` is ever reached (`ShakaEngine.kt`). DRM on Web means
  HLS or DASH through Shaka.
- Container note: Widevine/PlayReady are usually carried in **DASH**, FairPlay in **HLS**. Since iOS
  can't play DASH *and* can't do Widevine, your Apple delivery is **HLS + FairPlay**; your Android/Web
  delivery is typically **DASH + Widevine**. (CMAF lets both share segments.)

To pick the right scheme per platform at runtime, branch on the target — see
[§7](#7-multi-drm-one-codebase).

### Checking what the device supports

The table is the platforms' rule, but the answer for a given device is the device's: PlayReady is on
some Android phones and not most, and a browser's CDMs depend on its build. `drmSupport()` asks the
platform itself:

```kotlin
import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.drmSupport

val support = drmSupport()                 // suspend; asked once per process, then kept
if (DrmScheme.WIDEVINE in support.schemes) {
    support.widevineLevel                  // L1 (hardware-backed) or L3 (software)
}
```

| Platform | Asked of | Notes |
|---|---|---|
| Android | `MediaDrm.isCryptoSchemeSupported`, and Widevine's `securityLevel` property | L2 is reported as L3: it decodes video in software, which HD licences refuse. |
| Web | `navigator.requestMediaKeySystemAccess`, per key system | EME exists only in a secure context — `https`, or `localhost` — so a plain `http` page reports nothing. L1 means the browser grants hardware robustness. |
| iOS | Nothing to ask — FairPlay and Clear Key | The simulator has no FairPlay CDM, so it reports Clear Key alone. |

The engines check the same answer before loading a protected item, so you need not call it to get a
clean error — only to choose a scheme, or to say in advance that one won't work.

---

## 2. The `DrmConfig` API

Every example below assumes:

```kotlin
import dev.nonbinary.outis.core.source.*
```

### The full declaration

All nine parameters, in order:

```kotlin
data class DrmConfig(
    val scheme: DrmScheme,                        // WIDEVINE / PLAYREADY / FAIRPLAY / CLEARKEY
    val licenseServerUrl: String? = null,         // where the CDM's challenge is POSTed; null for CLEARKEY
    val certificateUrl: String? = null,           // FairPlay FPS application certificate; ignored otherwise
    val clearKeys: Map<String, String> = emptyMap(),             // CLEARKEY only: hex keyId -> hex key
    val licenseRequestHeaders: Map<String, String> = emptyMap(),
    val multiSession: Boolean = false,            // multiple concurrent key sessions (Android only)
    val widevineLevel: WidevineLevel = WidevineLevel.AUTO,   // Widevine only; see §3
    val licenseRequestInterceptor: ((LicenseRequest) -> LicenseRequest)? = null,   // see §6
    val licenseResponseInterceptor: ((ByteArray) -> ByteArray)? = null,            // see §6
)

enum class DrmScheme { WIDEVINE, PLAYREADY, FAIRPLAY, CLEARKEY }

enum class WidevineLevel { AUTO, L1, L3 }

data class LicenseRequest(
    val url: String,
    val body: ByteArray,
    val headers: Map<String, String>,
)
```

`LicenseRequest` compares `body` by **identity**, not contents (`DrmConfig.kt`) — it is a
transient value handed to an interceptor, so build variants with `copy(...)` and don't use it as a map
key.

### Attaching it to an item

```kotlin
player.setMediaItem(
    MediaItem(
        source = MediaSource.Url("https://cdn.example.com/movie.mpd"),
        mimeType = MimeType.DASH,
        drmConfig = DrmConfig(
            scheme = DrmScheme.WIDEVINE,
            licenseServerUrl = "https://license.example.com/widevine",
            licenseRequestHeaders = mapOf("X-Auth-Token" to token),
        ),
    ),
    autoPlay = true,
)
```

`DrmConfig` is a data class holding two function references, which `equals` compares by identity.
Hoist the config into a `val` and reuse it rather than allocating fresh lambdas on every call, or
otherwise-identical `MediaItem`s will compare unequal.

---

## 3. Widevine / PlayReady (Android + Web)

Same shape for both — only the `scheme` changes:

```kotlin
val drm = DrmConfig(
    scheme = DrmScheme.WIDEVINE,                 // or DrmScheme.PLAYREADY
    licenseServerUrl = "https://license.example.com/wv",
    licenseRequestHeaders = mapOf("Authorization" to "Bearer $token"),
)
```

- **Android (Media3):** mapped to `ExoMediaItem.DrmConfiguration` with `C.WIDEVINE_UUID` /
  `C.PLAYREADY_UUID`, the license URI, the request headers and `multiSession`
  (`ExoPlayerEngine.kt`). ExoPlayer's default media-source factory builds the
  `DefaultDrmSessionManager` from it.
- **Web (Shaka):** mapped to `drm.servers['com.widevine.alpha' | 'com.microsoft.playready']`
  (`ShakaRequests.kt`, key-system names in `KeySystems.kt`); headers are injected by a networking
  request filter (`ShakaRequests.kt`).
- **iOS:** there is no Widevine or PlayReady CDM. No key session is created at all, so the item fails
  as a plain source error — see [§9](#9-troubleshooting). Serve FairPlay to Apple.

`multiSession = true` (some live / key-rotating streams) is honoured on Android, on both the
declarative path (`ExoPlayerEngine.kt`) and the custom-session path. It is ignored
elsewhere.

### Forcing the Widevine security level

`widevineLevel` exists for one specific, common production problem: older devices that advertise
Widevine L1 but can no longer provision it, because the factory L1 certificate has expired. Forcing L3
makes them play again.

```kotlin
DrmConfig(
    scheme = DrmScheme.WIDEVINE,
    licenseServerUrl = "https://license.example.com/wv",
    widevineLevel = WidevineLevel.L3,   // software CDM
)
```

| Value | Android | Web (Shaka) | iOS |
|---|---|---|---|
| `AUTO` (default) | platform negotiates | empty robustness lists — Shaka's Widevine default | n/a |
| `L3` | `securityLevel=L3` forced on a fresh `MediaDrm` before any session opens (`ExoPlayerEngine.kt`) | `SW_SECURE_DECODE` / `SW_SECURE_CRYPTO` robustness (`ShakaRequests.kt`) | n/a |
| `L1` | **best-effort only** — a device can't be software-forced *up*, so it behaves like `AUTO` (`ExoPlayerEngine.kt`) | `HW_SECURE_DECODE` / `HW_SECURE_CRYPTO` — playback **fails** where the browser has no hardware CDM | n/a |

It is **Widevine only**. PlayReady and FairPlay ignore it: on Android `setPropertyString` on a
non-Widevine plugin throws, so the SDK never touches it (`ExoPlayerEngine.kt`); on Web there
is no equivalent robustness token; on iOS FairPlay has no client-settable level and the field is
deliberately ignored (`AVPlayerEngine.kt`).

Setting `L3` on Android takes the same custom `DrmSessionManagerProvider` path as an interceptor
(`ExoPlayerEngine.kt`), but keeps Media3's stock HTTP callback, so the license exchange is
byte-for-byte what the declarative path would have done.

---

## 4. FairPlay (iOS + Safari)

FairPlay needs **two** URLs — the license (key) server **and** the FPS application certificate — plus
an **HLS** source:

```kotlin
val fairPlay = DrmConfig(
    scheme = DrmScheme.FAIRPLAY,
    licenseServerUrl = "https://license.example.com/fairplay",     // takes the SPC, returns the CKC
    certificateUrl = "https://license.example.com/fairplay.cer",   // FPS application certificate
    licenseRequestHeaders = mapOf("X-Auth-Token" to token),
)

player.setMediaItem(
    MediaItem(
        source = MediaSource.Url("https://cdn.example.com/movie.m3u8"),
        mimeType = MimeType.HLS,
        drmConfig = fairPlay,
    ),
    autoPlay = true,
)
```

- **iOS (AVPlayer):** the SDK runs the full `AVContentKeySession` flow — fetch the certificate, build
  the SPC with `makeStreamingContentKeyRequestDataForApp`, POST it to `licenseServerUrl`, answer the
  key request with the returned CKC (`FairPlayContentKeyManager.kt`). The content identifier
  the SPC is built against is the `skd://` key URI with the scheme stripped
  (`FairPlayContentKeyManager.kt`); if your provider derives it differently, rewrite the
  request in [§6](#6-non-standard-license-servers-the-interceptors).
- **Web (Safari/Shaka):** mapped to `drm.servers['com.apple.fps']` plus
  `advanced['com.apple.fps'].serverCertificateUri` (`ShakaRequests.kt`).

> **`certificateUrl` is not optional on iOS, and omitting it does not give you a DRM error.** The key
> manager is only created when `scheme == FAIRPLAY && certificateUrl != null`
> (`AVPlayerEngine.kt`). With a null certificate URL the encrypted asset is handed to AVPlayer
> bare and fails as `Category.SOURCE`, with no mention of DRM anywhere. A *present but broken*
> certificate URL does produce `Category.DRM` (`FairPlayContentKeyManager.kt`).

---

## 5. License-request headers (token auth)

Most production license servers gate on an auth token. Put it in `licenseRequestHeaders`:

```kotlin
DrmConfig(
    scheme = DrmScheme.WIDEVINE,
    licenseServerUrl = "https://license.example.com/wv",
    licenseRequestHeaders = mapOf(
        "Authorization" to "Bearer $jwt",
        "X-Custom-Header" to "value",
    ),
)
```

These are headers on the *license* request, distinct from `MediaItem.headers`, which apply to
manifest and segment requests. The two scopes really are separate in the engines
(`ShakaRequests.kt` splits on Shaka's `RequestType.LICENSE`; iOS puts `MediaItem.headers` on the
`AVURLAsset` at `AVPlayerEngine.kt` and the license headers on the SPC POST at
`FairPlayContentKeyManager.kt`).

Where they are applied:

| Platform | Applied to | Notes |
|---|---|---|
| Android | Media3 `setLicenseRequestHeaders` (`ExoPlayerEngine.kt`) | Bypassed when an interceptor is set — see below. |
| Web | Shaka request filter, LICENSE requests only (`ShakaRequests.kt`) | |
| iOS | The SPC POST (`FairPlayContentKeyManager.kt`) | **Not** the certificate GET. |

Two behaviours that bite in production:

- **iOS never sends headers with the certificate fetch.** `httpGet` is called with `headers = null`
  (`FairPlayContentKeyManager.kt`), so a token-gated certificate endpoint fails on iOS while
  working everywhere else. Serve the FPS certificate from an unauthenticated URL, or move the token
  into the URL itself.
- **On Android, an interceptor takes over the headers.** With `licenseRequestInterceptor` set, the
  request no longer goes through `setLicenseRequestHeaders`; it goes through
  `InterceptingMediaDrmCallback`, whose returned header map is authoritative
  (`ExoPlayerEngine.kt`). Headers your interceptor omits are dropped. Same rule on Web and iOS
  (`DrmConfig.kt`).

---

## 6. Non-standard license servers: the interceptors

Plenty of license servers do not accept "POST the raw challenge, get the raw key back". thePlatform/MPX
and most custom proxies want the challenge moved into the query string, a templated URL, or the key
wrapped in a JSON or SOAP envelope. `licenseRequestInterceptor` and `licenseResponseInterceptor` are
the hook, and they are implemented on **all three platforms** — you do not need to fork the SDK or
file an issue for this.

```kotlin
import kotlin.io.encoding.Base64

val drm = DrmConfig(
    scheme = DrmScheme.WIDEVINE,
    licenseServerUrl = "https://mpx.example.com/license",
    licenseRequestHeaders = mapOf("Authorization" to "Bearer $jwt"),

    // Move the challenge into the URL and send an empty body.
    licenseRequestInterceptor = { request ->
        val challenge = Base64.UrlSafe.encode(request.body)
        request.copy(
            url = "${request.url}?token=$accountToken&challenge=$challenge",
            body = ByteArray(0),
            headers = request.headers + ("Content-Type" to "application/x-www-form-urlencoded"),
        )
    },

    // Unwrap {"license":"<base64>"} into the raw key bytes the CDM expects.
    licenseResponseInterceptor = { raw ->
        val json = raw.decodeToString()
        val payload = json.substringAfter("\"license\":\"").substringBefore('"')
        Base64.decode(payload)
    },
)
```

(The response parse above is deliberately crude to keep the example dependency-free; use
kotlinx-serialization in real code. If your Kotlin version still marks `kotlin.io.encoding.Base64` as
experimental, add `@OptIn(ExperimentalEncodingApi::class)`.)

What each platform hands you:

| | Android (Widevine/PlayReady) | iOS (FairPlay) | Web (all schemes) |
|---|---|---|---|
| Request interceptor | `ExoPlayerEngine.kt` | `FairPlayContentKeyManager.kt` | `ShakaRequests.kt` |
| Response interceptor | `ExoPlayerEngine.kt` | `FairPlayContentKeyManager.kt` | `ShakaRequests.kt` |
| `LicenseRequest.body` is | the CDM key-request challenge | the **SPC** | the CDM challenge as Shaka built it |
| Response bytes are | the raw license HTTP body | the **CKC** | the raw license HTTP body |
| Default `url` | the CDM-supplied license URL if the `KeyRequest` carries one, else `licenseServerUrl` (`ExoPlayerEngine.kt`) | `licenseServerUrl` | `licenseServerUrl` |
| Default `headers` | `licenseRequestHeaders` only | `licenseRequestHeaders` only | `licenseRequestHeaders` **plus** the headers Shaka itself set, e.g. `Content-Type` (`DrmConfig.kt`) |

Rules that apply everywhere:

- The interceptors are **synchronous** — no suspending, no coroutine. Keep them pure and
  non-blocking; on Android `executeKeyRequest` runs on a background license thread
  (`ExoPlayerEngine.kt`, `DrmConfig.kt`), so never touch UI state from inside one.
- The returned header map is **authoritative**. Start from `request.headers` and add to it; building
  a fresh map drops everything you didn't list.
- Setting either interceptor on Android switches the whole exchange to a custom
  `DrmSessionManagerProvider`, which then owns the license URL, headers, `multiSession` and the CDM
  provider (`ExoPlayerEngine.buildMediaSource`).
- FairPlay interceptors run only on the iOS and Web FairPlay paths. There is no Android FPS, so
  `customDrmSessionManagerProvider` returns null for FairPlay (`ExoPlayerEngine.kt`).

---

## 7. Multi-DRM, one codebase

The scheme depends on where the app runs, and [`drmSupport()`](#checking-what-the-device-supports)
says which that is — so one function in `commonMain` covers every target, Safari and Chromium included,
with no platform branch and no browser sniffing:

```kotlin
// commonMain
suspend fun drmFor(licenseBase: String, token: String): DrmConfig? {
    val schemes = drmSupport().schemes
    val headers = mapOf("X-Auth-Token" to token)
    return when {
        DrmScheme.WIDEVINE in schemes -> DrmConfig(
            scheme = DrmScheme.WIDEVINE,
            licenseServerUrl = "$licenseBase/widevine",
            licenseRequestHeaders = headers,
        )
        DrmScheme.FAIRPLAY in schemes -> DrmConfig(
            scheme = DrmScheme.FAIRPLAY,
            licenseServerUrl = "$licenseBase/fairplay",
            certificateUrl = "$licenseBase/fairplay.cer",
            licenseRequestHeaders = headers,
        )
        else -> null // nothing here can decrypt this asset
    }
}
```

…then pick the matching **source** too — DASH for Widevine, HLS for FairPlay. Most multi-DRM packagers
emit both a DASH+Widevine and an HLS+FairPlay manifest over shared CMAF segments. If your backend lists
them in a SMIL document, hand the player that instead: the default
[SMIL selection](playback.md#smil) tries the format the item's DRM scheme pairs with first.

Outis maps exactly one `DrmScheme` per item, so the choice is yours to make before `setMediaItem` — the
function above is that choice.

---

## 8. Testing with public DRM streams

Useful for wiring things up before your own license server exists. These are third-party endpoints
outside this project's control — they move and expire.

- **Widevine (DASH)** — the Tears of Steel CENC asset with the public Widevine UAT proxy (open, no
  headers). Android and Chromium browsers.
  - manifest `https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd`
  - license `https://proxy.uat.widevine.com/proxy?video_id=2015_tears&provider=widevine_test`
- **FairPlay (HLS)** — the EZDRM demo, which uses the raw SPC/CKC form the SDK implements by default.
  iOS and Safari.
  - manifest `https://fps.ezdrm.com/demo/video/ezdrm.m3u8`
  - certificate `https://fps.ezdrm.com/demo/video/eleisure.cer`
  - license `https://fps.ezdrm.com/api/licenses/<asset-id>`

---

## 9. Troubleshooting

A scheme the device lacks fails the same way everywhere. Failures after that point carry each engine's
own detail, so read `code` and `nativeCause` alongside the category.

| Platform | Failure | What you actually observe |
|---|---|---|
| All | A scheme the device has no CDM for — any FairPlay on Android, Widevine or PlayReady on iOS, Widevine in Safari, FairPlay in Chrome, FairPlay on the iOS simulator | `PlayerError(Category.DRM, message = "<Scheme> DRM isn't supported on this device")`, before anything is fetched |
| Web | A forced `widevineLevel` the browser can't grant | `Category.DRM`, `"Widevine L1 isn't supported on this device"` (or `L3`), before anything is fetched |
| iOS | FairPlay with `certificateUrl = null` | `Category.DRM`, `"FairPlay needs DrmConfig.certificateUrl"`, before anything is fetched |
| Android | License server rejection, key errors | `Category.DRM` — Media3 error codes 6000-6999 |
| iOS | FairPlay handshake failure (certificate fetch, SPC build, license POST) | `Category.DRM`. Errors latch first-wins, so if the `AVPlayerItem` gives up first you get `SOURCE` instead |
| Web | A failure during or after `load()` | Classified by Shaka's own category — `DRM` for its 6xxx key-system errors — with Shaka's numeric code in `code` and its error object in `nativeCause` |
| Web | Progressive source (`MimeType.MP4`, `LocalFile`, `.mp4`/`.webm`) with a `DrmConfig` | Nothing. `configureDrm` is never called; the item plays clear or fails opaquely |

Symptom table:

| Symptom | Likely cause |
|---|---|
| `Category.DRM` immediately on Android | Wrong scheme for the device, or a bad license/certificate URL. |
| `Category.DRM` on web with a 6xxx `code` | A key-system or licence problem Shaka hit; the code names it in Shaka's error reference. |
| FairPlay: certificate loads, then it fails at the **license** step | Provider-specific SPC/CKC shape. Fix it with the interceptors — [§6](#6-non-standard-license-servers-the-interceptors). |
| FairPlay works on Web, fails on iOS at the **certificate** step | The certificate endpoint requires a token. iOS sends no headers with that GET — [§5](#5-license-request-headers-token-auth). |
| License rejected only in production | Expired or missing `licenseRequestHeaders` token, or an interceptor that returned a header map without it. |
| Playback works on most devices, fails on a few old Android handsets | Expired L1 certificate. Try `widevineLevel = WidevineLevel.L3` — [§3](#3-widevine--playready-android--web). |
| iOS: a clear DASH or `avc3` stream "won't play" but DRM looks configured | Not DRM — iOS can't play DASH at all, and rejects in-band-parameter-set `avc3` HLS. Use HLS `avc1`. |
| Nothing plays on Web after one DRM failure | A failed Shaka load wedges the Player; the engine flags it and the next `setMediaItem` recreates it. Recovery is automatic. |

A DRM failure is recoverable: the next `setMediaItem` clears `state.error` on all three engines and
starts fresh.

---

**See also:** [Playback guide](playback.md) · [Platform support](platform-support.md) ·
[Troubleshooting](troubleshooting.md)
