/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.source.DrmConfig
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVContentKeyRecipientProtocol
import platform.AVFoundation.AVContentKeyRequest
import platform.AVFoundation.AVContentKeyResponse
import platform.AVFoundation.AVContentKeySession
import platform.AVFoundation.AVContentKeySessionDelegateProtocol
import platform.AVFoundation.AVContentKeySystemClearKey
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addContentKeyRecipient
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import kotlin.native.ref.WeakReference

/**
 * Keeps each live manager strongly reachable until [ClearKeyContentKeyManager.release] — the delegate
 * holds only a [WeakReference] back, mirroring [FairPlayContentKeyManager]'s `liveKeyManagers`.
 */
private val liveClearKeyManagers = mutableSetOf<ClearKeyContentKeyManager>()

/**
 * W3C Clear Key content-key delivery for the iOS [AVPlayerEngine], via `AVContentKeySystemClearKey`.
 *
 * Unlike FairPlay there is no server handshake: the keys ([DrmConfig.clearKeys], hex `keyId` → hex `key`)
 * are already in hand, so when AVPlayer asks for a key we answer immediately with the raw key bytes
 * ([AVContentKeyResponse.contentKeyResponseWithClearKeyData], no IV — AVPlayer takes the SAMPLE-AES IV
 * from the playlist). Because we supply the key ourselves we never read the manifest's key URI, which
 * sidesteps packagers (Shaka among them, bug shaka-project/shaka-packager#1219) that put the *keyId*
 * rather than the key in the `EXT-X-KEY` `data:` URI.
 *
 * **iOS scope:** this key system serves **HLS** (SAMPLE-AES, fMP4 segments) only — AVPlayer has no DASH —
 * so an iOS Clear Key item must be an HLS source; a DASH one fails at the source, before any key request.
 *
 * **Threading/memory:** same contract as [FairPlayContentKeyManager] — the session's delegate runs on the
 * main queue, the delegate weak-refs this manager, [liveClearKeyManagers] keeps it alive until [release],
 * and **callers MUST call release().**
 */
internal class ClearKeyContentKeyManager(private val onError: (PlayerError) -> Unit) {
    private val session =
        AVContentKeySession.contentKeySessionWithKeySystem(AVContentKeySystemClearKey)
    private val delegate = ClearKeyDelegate(this)
    private var keys: Map<String, NSData> = emptyMap() // lowercase hex keyId → raw key bytes
    private var released = false

    init {
        liveClearKeyManagers.add(this)
        session.setDelegate(delegate, dispatch_get_main_queue())
    }

    /** Register [asset] for Clear Key delivery with [config]. Call before the AVPlayerItem loads. */
    fun prepare(asset: AVURLAsset, config: DrmConfig) {
        if (released) return
        keys = config.clearKeys.entries.associate { (kid, key) -> kid.lowercase() to hexToNSData(key) }
        // AVURLAsset conforms to AVContentKeyRecipient at runtime; K/N doesn't model that statically.
        @Suppress("CAST_NEVER_SUCCEEDS")
        session.addContentKeyRecipient(asset as AVContentKeyRecipientProtocol)
    }

    fun release() {
        if (released) return
        released = true
        liveClearKeyManagers.remove(this)
        session.setDelegate(null, null)
    }

    fun onKeyRequest(keyRequest: AVContentKeyRequest) {
        if (released) return
        val key = resolveKey(keyRequest.identifier)
        if (key == null) {
            fail(keyRequest, "ClearKey: no key for the requested identifier")
            return
        }
        val response = AVContentKeyResponse.contentKeyResponseWithClearKeyData(key, null)
        keyRequest.processContentKeyResponse(response)
    }

    // One key → use it (the common single-key case; no identifier parsing to get wrong). Otherwise match
    // the hex keyId carried in the request identifier — `skd://<hex>`, a bare hex id, or the trailing
    // token of a URI. A multi-key stream whose identifier is a base64 `data:` URI isn't resolved here.
    private fun resolveKey(identifier: Any?): NSData? {
        val single = keys.values.singleOrNull()
        if (single != null) return single
        val id = (identifier as? String) ?: (identifier as? NSURL)?.absoluteString
        return id?.let { keys[it.substringAfterLast('/').substringAfterLast(',').lowercase()] }
    }

    private fun fail(keyRequest: AVContentKeyRequest, message: String) {
        keyRequest.processContentKeyResponseError(nsError(message))
        onError(PlayerError(PlayerError.Category.DRM, message = message))
    }
}

/** Delegate kept ObjC-retained by the session; weak-refs the manager to avoid a retain cycle. */
private class ClearKeyDelegate(manager: ClearKeyContentKeyManager) :
    NSObject(),
    AVContentKeySessionDelegateProtocol {
    private val ref = WeakReference(manager)

    override fun contentKeySession(session: AVContentKeySession, didProvideContentKeyRequest: AVContentKeyRequest) {
        ref.get()?.onKeyRequest(didProvideContentKeyRequest)
    }
}

private const val HEX_RADIX = 16

private fun hexToNSData(hex: String): NSData {
    val bytes = hex.chunked(2).map { it.toInt(HEX_RADIX).toByte() }.toByteArray()
    if (bytes.isEmpty()) return NSData()
    return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
}

private fun nsError(message: String): NSError = NSError.errorWithDomain(
    "dev.nonbinary.outis.clearkey",
    code = -1,
    userInfo = mapOf<Any?, Any?>(NSLocalizedDescriptionKey to message),
)
