# DRM integration guide

How to play Widevine-, PlayReady- and FairPlay-protected streams with Outis. Standard
(clear) playback is covered in [playback.md](playback.md); this guide is only the DRM layer.

DRM is attached to a `MediaItem` via a single `DrmConfig` — the SDK maps it onto each engine's native
key system (Media3 `DrmConfiguration`, Shaka `drm.*`, AVFoundation `AVContentKeySession`).

---

## 1. What works where

DRM key systems are platform-bound — this is the one split you can't engineer away:

| Scheme | Android (Media3) | iOS (AVPlayer) | Web — Chrome/Edge/FF | Web — Safari |
|---|---|---|---|---|
| **Widevine** | ✅ | ❌ | ✅ | ❌ |
| **PlayReady** | ✅ | ❌ | ✅ (Edge) | ❌ |
| **FairPlay** | ❌ | ✅ | ❌ | ✅ |

Practical takeaways:
- **Apple platforms (iOS + Safari) → FairPlay.** They have no Widevine/PlayReady CDM.
- **Everything else → Widevine** (or PlayReady on Microsoft stacks).
- A scheme the current platform can't satisfy surfaces a **`PlayerError`** (category `DRM` on
  Android/Web; a source/decode error on iOS) — it does **not** silently play.
- Container note: Widevine/PlayReady are usually carried in **DASH**, FairPlay in **HLS**. Since iOS
  can't play DASH *and* can't do Widevine, your Apple delivery is **HLS + FairPlay**; your
  Android/Web delivery is typically **DASH + Widevine**. (CMAF lets both share segments.)

To pick the right scheme per platform at runtime, branch on the target — see [§6](#6-multi-drm-one-codebase).

---

## 2. The `DrmConfig` API

```kotlin
import dev.nonbinary.outis.core.source.*

data class DrmConfig(
    val scheme: DrmScheme,                       // WIDEVINE / PLAYREADY / FAIRPLAY
    val licenseServerUrl: String,                // where the CDM posts its challenge
    val certificateUrl: String? = null,          // REQUIRED for FairPlay (FPS app certificate); ignored otherwise
    val licenseRequestHeaders: ImmutableMap<String, String> = persistentMapOf(),  // auth token, etc.
    val multiSession: Boolean = false,           // multiple concurrent key sessions (Android only)
)

enum class DrmScheme { WIDEVINE, PLAYREADY, FAIRPLAY }
```

Attach it to the item:

```kotlin
player.setMediaItem(
    MediaItem(
        source = MediaSource.Url("https://cdn.example.com/movie.mpd"),
        mimeType = MimeType.DASH,
        drmConfig = DrmConfig(
            scheme = DrmScheme.WIDEVINE,
            licenseServerUrl = "https://license.example.com/widevine",
            licenseRequestHeaders = persistentMapOf("X-Auth-Token" to token),
        ),
    ),
    autoPlay = true,
)
```

---

## 3. Widevine / PlayReady (Android + Web)

Same shape for both — only the `scheme` changes:

```kotlin
DrmConfig(
    scheme = DrmScheme.WIDEVINE,                 // or DrmScheme.PLAYREADY
    licenseServerUrl = "https://license.example.com/wv",
    licenseRequestHeaders = persistentMapOf("Authorization" to "Bearer $token"),
)
```

- **Android (Media3):** mapped to `ExoMediaItem.DrmConfiguration` (`C.WIDEVINE_UUID` / `C.PLAYREADY_UUID`)
  with the license URI, request headers and `multiSession`. ExoPlayer builds the
  `DefaultDrmSessionManager` automatically.
- **Web (Shaka):** mapped to `drm.servers['com.widevine.alpha' | 'com.microsoft.playready']`; request
  headers are injected via a Shaka networking filter.
- **iOS:** no Widevine/PlayReady CDM → the item fails with a DRM/source error. Serve FairPlay to Apple.

`multiSession = true` (some live / key-rotating streams) is honoured on Android.

---

## 4. FairPlay (iOS + Safari)

FairPlay needs **two** URLs — the license (key) server **and** the FPS application certificate — plus
an **HLS** source:

```kotlin
DrmConfig(
    scheme = DrmScheme.FAIRPLAY,
    licenseServerUrl = "https://license.example.com/fairplay",     // gets the SPC, returns the CKC
    certificateUrl   = "https://license.example.com/fairplay.cer", // FPS application certificate
    licenseRequestHeaders = persistentMapOf("X-Auth-Token" to token),
)
```

```kotlin
MediaItem(
    source = MediaSource.Url("https://cdn.example.com/movie.m3u8"),
    mimeType = MimeType.HLS,
    drmConfig = fairPlayConfig,
)
```

- **iOS (AVPlayer):** the SDK runs the full `AVContentKeySession` flow — fetch the certificate, build
  the SPC (`makeStreamingContentKeyRequestDataForApp`), POST it to `licenseServerUrl`, answer with the
  returned CKC.
- **Web (Safari/Shaka):** mapped to `drm.servers['com.apple.fps']` + `advanced['com.apple.fps']
  .serverCertificateUri`.

> **Provider-specific licence formats.** FairPlay license servers vary in how they expect the SPC
> request and return the CKC (content-id derivation from the `skd://` key URI, request body encoding,
> response wrapping). The SDK uses the common form (content-id = the key URI minus `skd://`, raw SPC →
> raw CKC), which matches the standard/open-proxy case. If your provider needs a different shape the
> failure shows up at the **license step** specifically — file it and we'll add the provider mapping.

---

## 5. License-request headers (token auth)

Most production license servers gate on an auth token. Put it in `licenseRequestHeaders`:

```kotlin
DrmConfig(
    scheme = DrmScheme.WIDEVINE,
    licenseServerUrl = "https://license.example.com/wv",
    licenseRequestHeaders = persistentMapOf(
        "Authorization"  to "Bearer $jwt",
        "X-Custom-Header" to "value",
    ),
)
```

Honoured on **Android** (Media3 `setLicenseRequestHeaders`), **Web** (Shaka request filter) and **iOS**
(added to the SPC POST). These are headers on the *license* request, distinct from `MediaItem.headers`
(which apply to manifest/segment requests).

---

## 6. Multi-DRM, one codebase

Because the scheme is per-platform, build the `DrmConfig` for the running target. A simple
`expect/actual` (or any platform branch) does it:

```kotlin
// commonMain
expect fun drmFor(licenseBase: String, token: String): DrmConfig

// androidMain / jsMain (Chromium)
actual fun drmFor(licenseBase: String, token: String) = DrmConfig(
    scheme = DrmScheme.WIDEVINE,
    licenseServerUrl = "$licenseBase/widevine",
    licenseRequestHeaders = persistentMapOf("X-Auth-Token" to token),
)

// iosMain  (and Safari, if you detect it on Web)
actual fun drmFor(licenseBase: String, token: String) = DrmConfig(
    scheme = DrmScheme.FAIRPLAY,
    licenseServerUrl = "$licenseBase/fairplay",
    certificateUrl   = "$licenseBase/fairplay.cer",
    licenseRequestHeaders = persistentMapOf("X-Auth-Token" to token),
)
```

…then pick the matching **source** too (DASH for Widevine, HLS for FairPlay). Most multi-DRM packagers
emit both a DASH+Widevine and an HLS+FairPlay manifest over shared CMAF segments.

---

## 7. Testing with public DRM streams

Useful for wiring things up without your own license server:

- **Widevine (DASH)** — ExoPlayer's Tears-of-Steel CENC asset + the public Widevine UAT proxy (open,
  no headers needed). Works on Android + Chromium browsers.
  - manifest `https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd`
  - license `https://proxy.uat.widevine.com/proxy?video_id=2015_tears&provider=widevine_test`
- **FairPlay (HLS)** — EZDRM demo. Works on iOS + Safari.
  - manifest `https://fps.ezdrm.com/demo/video/ezdrm.m3u8`
  - certificate `https://fps.ezdrm.com/demo/video/eleisure.cer`
  - license `https://fps.ezdrm.com/api/licenses/<asset-id>`

Wire both as entries in your own test harness.

---

## 8. Troubleshooting

| Symptom | Likely cause |
|---|---|
| `PlayerError(category = DRM)` immediately | Wrong scheme for the platform (e.g. Widevine on Safari/iOS), or a bad license/cert URL. |
| FairPlay: cert loads, then fails at the **license** step | Provider-specific SPC/CKC format — see the note in [§4](#4-fairplay-ios--safari). |
| iOS: a clear DASH/avc3 stream "won't play" but DRM looks configured | Not DRM — iOS can't play DASH at all, and rejects in-band-parameter-set `avc3` HLS. Use HLS `avc1`. |
| License rejected only in production | `licenseRequestHeaders` token missing/expired — see [§5](#5-license-request-headers-token-auth). |
| After a failed DRM load, nothing else plays (Web) | Fixed in-engine: a failed load recreates the Shaka player so the next source recovers. Make sure you're on a current build. |

A DRM failure is recoverable: the **next `setMediaItem` clears `state.error`** and starts fresh.
