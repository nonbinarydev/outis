/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.source.DrmConfig
import dev.nonbinary.outis.core.source.LicenseRequest
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVContentKeyRecipientProtocol
import platform.AVFoundation.AVContentKeyRequest
import platform.AVFoundation.AVContentKeyResponse
import platform.AVFoundation.AVContentKeySession
import platform.AVFoundation.AVContentKeySessionDelegateProtocol
import platform.AVFoundation.AVContentKeySystemFairPlayStreaming
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addContentKeyRecipient
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.native.ref.WeakReference

/** Assumed status when the response is not an `NSHTTPURLResponse` (e.g. a `file:` certificate URL). */
private const val HTTP_OK = 200L

/** First status treated as a failure. 3xx redirects are followed by NSURLSession before we see them. */
private const val HTTP_FIRST_ERROR_STATUS = 400L

/**
 * Keeps each live manager strongly reachable until [FairPlayContentKeyManager.release]. The delegate
 * holds only a [WeakReference] back (the AVContentKeySession retains the delegate), so without this a
 * manager whose engine reference is dropped could be GC'd while a key request is in flight.
 */
private val liveKeyManagers = mutableSetOf<FairPlayContentKeyManager>()

/**
 * FairPlay (FPS) content-key delivery for the iOS [AVPlayerEngine].
 *
 * Standard flow per encrypted asset: AVPlayer asks for a key → the delegate gets a key request → we
 * fetch the application certificate, build the SPC ("server playback context") with
 * `makeStreamingContentKeyRequestDataForApp`, POST it to the license server to get the CKC ("content
 * key context"), and answer the request with it.
 *
 * **Provider-specific bits** (the parts most likely to need tuning per DRM provider): the
 * `contentIdentifier` derived from the `skd://` key URI, and the license request/response encoding
 * (here: the raw SPC is POSTed and a raw CKC is expected back). EZDRM's demo uses this raw form.
 *
 * **Threading/memory:** the session's delegate queue is the main queue; the async cert/license
 * callbacks hop back to main. The delegate weak-refs this manager; [liveKeyManagers] keeps it alive
 * until [release]. **Callers MUST call release().**
 */
internal class FairPlayContentKeyManager(
    private val onError: (PlayerError) -> Unit,
) {
    private val session =
        AVContentKeySession.contentKeySessionWithKeySystem(AVContentKeySystemFairPlayStreaming)
    private val delegate = KeyDelegate(this)
    private var drm: DrmConfig? = null
    private var released = false

    init {
        liveKeyManagers.add(this)
        session.setDelegate(delegate, dispatch_get_main_queue())
    }

    /** Register [asset] for FairPlay key delivery with [config]. Call before the AVPlayerItem loads. */
    fun prepare(asset: AVURLAsset, config: DrmConfig) {
        if (released) return
        drm = config
        // AVURLAsset conforms to AVContentKeyRecipient at runtime; K/N doesn't model that statically.
        @Suppress("CAST_NEVER_SUCCEEDS")
        session.addContentKeyRecipient(asset as AVContentKeyRecipientProtocol)
    }

    fun release() {
        if (released) return
        released = true
        liveKeyManagers.remove(this)
        session.setDelegate(null, null)
    }

    // The FairPlay handshake is a fixed sequence of guarded steps (certificate, SPC, CKC, response
    // interceptor, error paths); each guard is a branch and the order is dictated by AVFoundation.
    @Suppress("CyclomaticComplexMethod")
    fun onKeyRequest(keyRequest: AVContentKeyRequest) {
        if (released) return
        val config = drm
        val certUrl = config?.certificateUrl
        if (config == null || certUrl == null) {
            fail(keyRequest, "FairPlay: no DRM config / certificate URL")
            return
        }
        val skd = (keyRequest.identifier as? String) ?: (keyRequest.identifier as? NSURL)?.absoluteString
        if (skd == null) {
            fail(keyRequest, "FairPlay: missing key identifier")
            return
        }
        // The content identifier the SPC is built against is the key URI WITHOUT the `skd://` scheme —
        // the asset id the license server provisioned. (The exact form is provider-specific; stripping
        // the scheme is the common case, EZDRM included.)
        val assetId = skd.removePrefix("skd://")
        // Kotlin String bridges to NSString at runtime; the compiler can't see it (false positive).

        @Suppress("CAST_NEVER_SUCCEEDS")
        val contentId = (assetId as NSString).dataUsingEncoding(NSUTF8StringEncoding)

        // 1. Application certificate.
        httpGet(certUrl) { cert, certErr ->
            if (released) return@httpGet
            if (cert == null) {
                fail(keyRequest, "FairPlay: certificate fetch failed (${certErr?.localizedDescription})")
                return@httpGet
            }
            // 2. SPC (server playback context).
            keyRequest.makeStreamingContentKeyRequestDataForApp(cert, contentId, null) { spc, spcErr ->
                if (released) return@makeStreamingContentKeyRequestDataForApp
                if (spc == null) {
                    fail(keyRequest, "FairPlay: SPC build failed (${spcErr?.localizedDescription})")
                    return@makeStreamingContentKeyRequestDataForApp
                }
                // 3. CKC (content key context) from the license server. A request interceptor can rewrite
                //    the URL/body/headers (here `body` is the SPC); a response interceptor can unwrap the
                //    CKC from a JSON envelope before AVFoundation sees it.
                val initialReq =
                    LicenseRequest(config.licenseServerUrl, spc.toByteArray(), config.licenseRequestHeaders)
                val licReq = config.licenseRequestInterceptor?.invoke(initialReq) ?: initialReq
                httpPost(licReq.url, licReq.body.toNSData(), licReq.headers) { rawCkc, licErr ->
                    if (released) return@httpPost
                    if (rawCkc == null) {
                        fail(keyRequest, "FairPlay: license request failed (${licErr?.localizedDescription})")
                        return@httpPost
                    }
                    val ckc = config.licenseResponseInterceptor?.let { it(rawCkc.toByteArray()).toNSData() } ?: rawCkc
                    // 4. Answer the key request.
                    val response = AVContentKeyResponse.contentKeyResponseWithFairPlayStreamingKeyResponseData(ckc)
                    keyRequest.processContentKeyResponse(response)
                }
            }
        }
    }

    private fun fail(keyRequest: AVContentKeyRequest, message: String) {
        keyRequest.processContentKeyResponseError(nsError(message))
        onError(PlayerError(PlayerError.Category.DRM, message = message))
    }
}

/** Delegate kept ObjC-retained by the session; weak-refs the manager to avoid a retain cycle. */
private class KeyDelegate(manager: FairPlayContentKeyManager) : NSObject(), AVContentKeySessionDelegateProtocol {
    private val ref = WeakReference(manager)

    override fun contentKeySession(session: AVContentKeySession, didProvideContentKeyRequest: AVContentKeyRequest) {
        ref.get()?.onKeyRequest(didProvideContentKeyRequest)
    }
}

private fun httpGet(urlString: String, completion: (NSData?, NSError?) -> Unit) =
    request(urlString, body = null, headers = null, completion = completion)

private fun httpPost(
    urlString: String,
    body: NSData,
    headers: Map<String, String>,
    completion: (NSData?, NSError?) -> Unit,
) = request(urlString, body = body, headers = headers, completion = completion)

/** GET (body == null) or POST [urlString]; the completion hops back to the main queue. */
private fun request(
    urlString: String,
    body: NSData?,
    headers: Map<String, String>?,
    completion: (NSData?, NSError?) -> Unit
) {
    val url = NSURL(string = urlString)
    val req = NSMutableURLRequest(uRL = url)
    if (body != null) {
        req.setHTTPMethod("POST")
        req.setHTTPBody(body)
    }
    headers?.forEach { (k, v) -> req.setValue(v, forHTTPHeaderField = k) }
    NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
        // An HTTP error status is NOT an NSURLSession transport error — `error` is nil and `data` is
        // the error-page body. Treat >= 400 as failure so it isn't handed to AVFoundation as a cert/CKC.
        val status = (response as? NSHTTPURLResponse)?.statusCode ?: HTTP_OK
        dispatch_async(dispatch_get_main_queue()) {
            if (error == null && status >= HTTP_FIRST_ERROR_STATUS) {
                completion(null, nsError("HTTP $status from $urlString"))
            } else {
                completion(data, error)
            }
        }
    }.resume()
}

private fun nsError(message: String): NSError =
    NSError.errorWithDomain(
        "dev.nonbinary.outis.fairplay",
        code = -1,
        userInfo = mapOf<Any?, Any?>(NSLocalizedDescriptionKey to message)
    )

/** Copy these bytes into a fresh NSData (a FairPlay license POST body). */
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
}

/** Copy an NSData (a FairPlay license response) into a ByteArray. */
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
