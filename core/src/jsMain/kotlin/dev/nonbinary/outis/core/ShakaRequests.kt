/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.source.DrmConfig
import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.KeySystems
import dev.nonbinary.outis.core.source.LicenseRequest
import dev.nonbinary.outis.core.source.WidevineLevel
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * What the web engine adds to Shaka's requests: [mediaHeaders] on manifests and segments, and on licence
 * requests the headers and interceptors of the item's [DrmConfig]. One per engine, installed on every
 * Player it creates; the engine stages each item's values before that item loads.
 */
internal class ShakaRequests {
    /** `MediaItem.headers`, staged per item, for every request that is not a licence request. */
    var mediaHeaders: Map<String, String> = emptyMap()
    private var licenseHeaders: Map<String, String> = emptyMap()
    private var licenseRequestInterceptor: ((LicenseRequest) -> LicenseRequest)? = null
    private var licenseResponseInterceptor: ((ByteArray) -> ByteArray)? = null

    /** Register the request and response filters on [player] — once per Player, before its first load. */
    fun install(player: shaka.Player) {
        // Inject DRM license-request headers (auth tokens) into Shaka's LICENSE requests. The header
        // set is staged per media item by configureDrm (empty for clear content), so one filter suffices.
        player.getNetworkingEngine().registerRequestFilter({ type: Int, request: dynamic ->
            if (type == shaka.net.NetworkingEngine.RequestType.LICENSE) {
                licenseHeaders.forEach { (key, value) -> request.headers[key] = value }
                // Rewrite the license request (URL/body/headers) for non-standard servers — e.g. moving the
                // challenge into the query string. Shaka does the HTTP; we only transform the request.
                licenseRequestInterceptor?.let { transform ->
                    // Feed the interceptor the *actual* outgoing headers (Shaka's own — Content-Type etc. —
                    // plus the licenseHeaders applied above), and let its returned set be authoritative by
                    // replacing the header object. This matches Android/iOS, where the interceptor fully owns
                    // the request and can add, change, or drop headers.
                    val out = transform(
                        LicenseRequest(
                            request.uris[0] as String,
                            jsBodyToByteArray(request.body),
                            jsHeadersToMap(request.headers),
                        ),
                    )
                    request.uris = arrayOf(out.url)
                    request.body = out.body.toJsBody()
                    request.headers = jsHeadersOf(out.headers)
                }
            } else {
                // Manifest/segment/media requests carry the MediaItem auth headers (distinct scope from
                // the DRM-license headers above).
                mediaHeaders.forEach { (key, value) -> request.headers[key] = value }
            }
        })
        // Unwrap the license response (e.g. JSON envelope → base64-decode) before the CDM sees it.
        player.getNetworkingEngine().registerResponseFilter({ type: Int, response: dynamic ->
            if (type == shaka.net.NetworkingEngine.RequestType.LICENSE) {
                licenseResponseInterceptor?.let { transform ->
                    response.data = transform(jsBodyToByteArray(response.data)).toJsBody()
                }
            }
        })
    }

    /**
     * Point Shaka's CDM at the license server before `load`, and stage the license-request headers
     * that [install]'s request filter injects into LICENSE requests. The header set is reset each call
     * (empty for clear content). Note Shaka's `configure()` **merges** — it never deletes keys — so a
     * value a prior item set survives unless this call overwrites it; we therefore rewrite the fields a
     * later item could otherwise inherit (`servers[keySystem]`, and for Widevine the robustness — empty
     * for AUTO) rather than relying on the rebuilt-each-call `servers`/`advanced` locals to clear them.
     * Entries for key systems the current item doesn't use linger in the merged config but are inert (no
     * matching content). FairPlay (Safari) additionally needs the application certificate, which Shaka
     * fetches from `serverCertificateUri`; [DrmScheme.CLEARKEY] instead carries its keys inline via
     * `clearKeys` (no server). A Widevine [DrmConfig.widevineLevel] maps to EME video/audio
     * robustness on the key system's `advanced` entry. (`multiSession` is an Android-only flag — not
     * applied here.)
     */
    fun configureDrm(player: shaka.Player, drm: DrmConfig?) {
        licenseHeaders = drm?.licenseRequestHeaders ?: emptyMap()
        licenseRequestInterceptor = drm?.licenseRequestInterceptor
        licenseResponseInterceptor = drm?.licenseResponseInterceptor
        val servers: dynamic = js("({})")
        val advanced: dynamic = js("({})")
        val clearKeys: dynamic = js("({})")
        if (drm != null && drm.scheme == DrmScheme.CLEARKEY) {
            // Keys supplied inline (hex keyId:key) — no license server. Shaka takes them via `clearKeys`.
            drm.clearKeys.forEach { (keyId, key) -> clearKeys[keyId] = key }
        } else if (drm != null) {
            val keySystem = KeySystems.of(drm.scheme)
            servers[keySystem] = drm.licenseServerUrl
            // FairPlay requires the FPS application certificate; let Shaka fetch it.
            if (drm.scheme == DrmScheme.FAIRPLAY && drm.certificateUrl != null) {
                val fps: dynamic = js("({})")
                fps.serverCertificateUri = drm.certificateUrl
                advanced[keySystem] = fps
            }
            // Widevine security level → EME robustness. L1 demands hardware (HW_SECURE_*, fails if the
            // browser has no hardware CDM); L3 forces software (SW_SECURE_*); AUTO writes an empty list so
            // Shaka applies its own Widevine default. Always set it (even for AUTO): configure() merges and
            // never deletes keys, so writing it each time clears any HW/SW level a prior item left behind. No
            // analog for PlayReady (different tokens) or FairPlay, so the knob is Widevine-only here too.
            if (drm.scheme == DrmScheme.WIDEVINE) {
                val (videoRob, audioRob) = widevineRobustness(drm.widevineLevel)
                val wv: dynamic = js("({})")
                wv.videoRobustness = videoRob
                wv.audioRobustness = audioRob
                advanced[keySystem] = wv
            }
        }
        val drmConfig: dynamic = js("({})")
        drmConfig.servers = servers
        drmConfig.advanced = advanced
        drmConfig.clearKeys = clearKeys
        val config: dynamic = js("({})")
        config.drm = drmConfig
        player.configure(config)
    }
}

/** Read raw bytes from a Shaka request/response body (an ArrayBuffer or an ArrayBufferView). */
private fun jsBodyToByteArray(body: dynamic): ByteArray {
    if (body == null) return ByteArray(0)
    val viewBuffer = body.buffer // an ArrayBufferView (Uint8Array, …) has .buffer; an ArrayBuffer does not
    val i8 = if (viewBuffer != null) {
        Int8Array(
            viewBuffer.unsafeCast<ArrayBuffer>(),
            body.byteOffset.unsafeCast<Int>(),
            body.byteLength.unsafeCast<Int>(),
        )
    } else {
        Int8Array(body.unsafeCast<ArrayBuffer>())
    }
    return ByteArray(i8.length) { i8[it] }
}

/** A fresh ArrayBuffer holding these bytes, for use as a Shaka request/response body. */
private fun ByteArray.toJsBody(): ArrayBuffer {
    val i8 = Int8Array(size)
    for (i in indices) i8[i] = this[i]
    return i8.buffer
}

/** Read a Shaka request headers object into a Kotlin map (so an interceptor sees Shaka's own headers). */
private fun jsHeadersToMap(headers: dynamic): Map<String, String> {
    if (headers == null) return emptyMap()
    val keys: dynamic = js("Object").keys(headers)
    val len = keys.length.unsafeCast<Int>()
    val map = LinkedHashMap<String, String>()
    for (i in 0 until len) {
        val k = keys[i].unsafeCast<String>()
        map[k] = headers[k].unsafeCast<String>()
    }
    return map
}

/** Build a JS headers object from a Kotlin map. */
private fun jsHeadersOf(map: Map<String, String>): dynamic {
    val obj = js("({})")
    map.forEach { (k, v) -> obj[k] = v }
    return obj
}

/**
 * EME `(videoRobustness, audioRobustness)` for a Widevine level, as the **arrays** Shaka's config demands:
 * its type check rejects a plain string outright, leaving the level unapplied. Shaka tries each entry in
 * order. L1 = hardware-backed, L3 = software; [WidevineLevel.AUTO] is empty, which Shaka treats as "use its
 * Widevine default" — and, written every time, resets any level a prior item left in the merged config.
 */
private fun widevineRobustness(level: WidevineLevel): Pair<Array<String>, Array<String>> = when (level) {
    WidevineLevel.AUTO -> emptyArray<String>() to emptyArray()
    WidevineLevel.L1 -> arrayOf("HW_SECURE_DECODE") to arrayOf("HW_SECURE_CRYPTO")
    WidevineLevel.L3 -> arrayOf("SW_SECURE_DECODE") to arrayOf("SW_SECURE_CRYPTO")
}

/** The weakest video robustness [drm]'s Widevine level accepts; empty, for any, otherwise. */
internal fun preflightRobustness(drm: DrmConfig): String =
    if (drm.scheme == DrmScheme.WIDEVINE) widevineRobustness(drm.widevineLevel).first.firstOrNull().orEmpty() else ""
