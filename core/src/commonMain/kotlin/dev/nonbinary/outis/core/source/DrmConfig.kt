/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * DRM parameters for a [MediaItem].
 *
 * Wiring by scheme:
 * - [DrmScheme.WIDEVINE] / [DrmScheme.PLAYREADY] — **Android (Media3)** and **Web (Shaka)**; usually a
 *   [MimeType.DASH] source.
 * - [DrmScheme.FAIRPLAY] — **Web (Shaka, Safari)** and **iOS (AVPlayer / `AVContentKeySession`)**; an
 *   HLS source plus a [certificateUrl] (the FPS application certificate).
 * - [DrmScheme.CLEARKEY] — **Android (Media3)**, **Web (Shaka)** and **iOS (AVPlayer /
 *   `AVContentKeySession`, HLS only)**; keys supplied inline via [clearKeys] (no license server). A
 *   demo/test scheme.
 *
 * A scheme that the current engine/browser can't satisfy (e.g. Widevine in Safari, or FairPlay
 * without a certificate) surfaces a DRM source error rather than playing.
 *
 * **Non-standard license servers.** Many providers (thePlatform/MPX, custom proxies, …) don't accept a
 * plain "POST the challenge, get the raw key back" exchange — they want the challenge moved into the
 * URL, a templated URL, or the key wrapped in a JSON/SOAP envelope. [licenseRequestInterceptor] and
 * [licenseResponseInterceptor] are the hook for that: rewrite the outgoing [LicenseRequest] and/or
 * unwrap the response, in engine-neutral terms, and the SDK adapts it per platform (Android custom
 * `MediaDrmCallback`, Shaka request/response filters, iOS FairPlay SPC/CKC).
 */
data class DrmConfig(
    /**
     * Key system to use. Must be one the **content itself** is packaged for *and* the running
     * engine/browser supports — a mismatch (Widevine content in Safari, FairPlay on Android) surfaces a
     * DRM source error rather than falling back to another scheme.
     */
    val scheme: DrmScheme,
    /**
     * License (key) server URL the CDM posts its challenge to. Null for [DrmScheme.CLEARKEY], which
     * carries its keys inline in [clearKeys] rather than fetching them from a server.
     */
    val licenseServerUrl: String? = null,
    /**
     * Application certificate URL — **required for [DrmScheme.FAIRPLAY]** (Apple's FPS server
     * certificate), ignored by Widevine/PlayReady.
     */
    val certificateUrl: String? = null,
    /**
     * Clear Key content keys as `keyId` → `key`, both **hex**. Used only by [DrmScheme.CLEARKEY]: the keys
     * are handed to the CDM directly with no license exchange, so it's "encryption without secrecy" — a
     * demo/test scheme, not real protection. Honoured on **Android** (Media3 local ClearKey), **Web**
     * (Shaka `clearKeys`) and **iOS** (`AVContentKeySystemClearKey`, **HLS only** — AVPlayer has no DASH,
     * so an iOS Clear Key item must be an HLS source).
     */
    val clearKeys: ImmutableMap<String, String> = persistentMapOf(),
    /**
     * Extra headers on the license request (e.g. an auth token). Honoured on Android, Web and
     * iOS (FairPlay SPC POST).
     */
    val licenseRequestHeaders: ImmutableMap<String, String> = persistentMapOf(),
    /** Allow multiple concurrent key sessions — some live / key-rotating streams require it (Android only). */
    val multiSession: Boolean = false,
    /**
     * Force the Widevine CDM security level. **Widevine only** — ignored for PlayReady and FairPlay
     * (those schemes have a different/absent notion of security level, so honouring it here would be a
     * false promise). Per level:
     * - [WidevineLevel.AUTO] (default) — the platform negotiates its best available level; no change to
     *   today's behaviour.
     * - [WidevineLevel.L3] — force the **software** CDM. The intended use is the "expired L1 certificate"
     *   workaround for older devices that advertise L1 but can no longer provision it. Honoured on
     *   **Android** (`securityLevel=L3` on the `MediaDrm`) and **Web** (Shaka `SW_SECURE_*` robustness).
     * - [WidevineLevel.L1] — require/prefer the **hardware** CDM. Honoured on **Web** (Shaka `HW_SECURE_*`
     *   robustness — playback fails if the browser has no hardware Widevine). On **Android** this is
     *   best-effort: a device cannot be software-forced *up* to L1, so it behaves like [WidevineLevel.AUTO].
     *
     * No-op on iOS (FairPlay has no client-settable security level).
     */
    val widevineLevel: WidevineLevel = WidevineLevel.AUTO,
    /**
     * Rewrite the license/key request before it is sent. Receives the default request — the
     * [licenseServerUrl] as `url`, the CDM challenge as `body`, and [licenseRequestHeaders] — and returns
     * the request to actually send (a different/templated URL, an empty body with the challenge moved
     * into the query string, extra headers, …). `null` ⇒ POST the raw challenge to [licenseServerUrl].
     * Runs on a background/license thread — keep it pure and non-blocking. Honoured on **Android**
     * (Widevine/PlayReady), **Web** (all schemes, via Shaka's request filter) and **iOS** (FairPlay —
     * `body` is the SPC).
     */
    val licenseRequestInterceptor: ((LicenseRequest) -> LicenseRequest)? = null,
    /**
     * Transform the raw license-server response into the key bytes the CDM expects — e.g. pull the
     * license out of a JSON envelope and base64-decode it. Receives the raw HTTP response body, returns
     * the license/key. `null` ⇒ hand the response through unchanged. On **iOS** (FairPlay) this is the CKC.
     */
    val licenseResponseInterceptor: ((ByteArray) -> ByteArray)? = null,
)

/**
 * A DRM license/key HTTP request the SDK is about to send — passed to
 * [DrmConfig.licenseRequestInterceptor], which returns the (possibly rewritten) request to send. Build
 * a variant with [copy].
 */
data class LicenseRequest(
    /** Where to POST. Defaults to [DrmConfig.licenseServerUrl]. */
    val url: String,
    /** The request body. Defaults to the CDM challenge (Widevine/PlayReady) or the SPC (FairPlay). */
    val body: ByteArray,
    /**
     * Request headers. Defaults to [DrmConfig.licenseRequestHeaders]; on **Web** also includes the
     * headers Shaka itself set (e.g. `Content-Type`). The returned map is authoritative — headers it
     * omits are dropped.
     */
    val headers: Map<String, String>,
) {
    /**
     * Compares [url] and [headers] structurally but [body] **by identity**, matching the array default a
     * data class would give. This is deliberate: a `LicenseRequest` is a transient value passed to an
     * interceptor, never used as a map key or compared for content, so hashing a multi-kilobyte
     * challenge on every comparison would be wasted work.
     */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is LicenseRequest && url == other.url && body === other.body && headers == other.headers)

    /**
     * Consistent with [equals]: [body] contributes its identity hash, not its contents.
     *
     * The identity hash on an array is the point here, not an oversight — [equals] compares [body]
     * with `===`, so hashing its contents would break the equals/hashCode contract in the other
     * direction. Hence the suppression.
     */
    @Suppress("KotlinArrayHashCode")
    override fun hashCode(): Int = (url.hashCode() * 31 + body.hashCode()) * 31 + headers.hashCode()
}

/** DRM key system. WIDEVINE / PLAYREADY / CLEARKEY — Android + Web; FAIRPLAY — Web (Safari) + iOS. */
enum class DrmScheme {
    /** Google Widevine (`com.widevine.alpha`) — Android (Media3) and Web; typically DASH content. */
    WIDEVINE,

    /**
     * Microsoft PlayReady (`com.microsoft.playready`) — Android and Web, but **only where the device or
     * browser actually ships a PlayReady CDM**; typically DASH content.
     */
    PLAYREADY,

    /**
     * Apple FairPlay Streaming — iOS (`AVContentKeySession`) and Safari. HLS content, and it **requires**
     * [DrmConfig.certificateUrl]; without one, playback fails with a DRM error.
     */
    FAIRPLAY,

    /**
     * W3C Clear Key (`org.w3.clearkey`) — Android (Media3), Web (Shaka) and iOS (AVPlayer via
     * `AVContentKeySystemClearKey`, **HLS only**). Keys are supplied inline via [DrmConfig.clearKeys] with
     * no license server, so it's really "encryption without secrecy": a demo/test scheme, not content
     * protection.
     */
    CLEARKEY,
}

/**
 * Requested Widevine CDM security level — see [DrmConfig.widevineLevel].
 *
 * - [AUTO] — platform default (device picks the best available level).
 * - [L1] — hardware-backed CDM (Web: enforced; Android: best-effort, can't force *up*).
 * - [L3] — software CDM (the expired-L1-certificate workaround). Honoured on Android + Web.
 */
enum class WidevineLevel {
    /**
     * Let the platform negotiate its best available level — the default, and the effective behaviour on
     * iOS, which has no client-settable security level.
     */
    AUTO,

    /**
     * Require hardware-backed Widevine. Enforced on Web (Shaka `HW_SECURE_*` robustness — playback fails
     * where the browser has no hardware CDM); on Android this is **best-effort only**, since a device
     * cannot be forced *up* to L1, so it behaves like [AUTO].
     */
    L1,

    /**
     * Force the software CDM (Android `securityLevel=L3`, Web `SW_SECURE_*` robustness). The workaround
     * for older devices that advertise L1 but can no longer provision it.
     */
    L3,
}
