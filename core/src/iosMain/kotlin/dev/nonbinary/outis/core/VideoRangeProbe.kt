/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:OptIn(ExperimentalForeignApi::class)

package dev.nonbinary.outis.core

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.asset
import platform.AVFoundation.currentTime
import platform.AVFoundation.formatDescriptions
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFStringGetCStringPtr
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreMedia.CMFormatDescriptionGetExtension
import platform.CoreMedia.CMFormatDescriptionGetMediaSubType
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.kCMFormatDescriptionExtension_TransferFunction
import platform.CoreVideo.CVBufferGetAttachment
import platform.CoreVideo.CVBufferRelease
import platform.CoreVideo.kCVImageBufferTransferFunctionKey

// 'dvh1' / 'dvhe' as FourCharCode — the Dolby Vision HEVC sample-entry codec types.
private const val FOUR_CC_DVH1: UInt = 0x64766831u
private const val FOUR_CC_DVHE: UInt = 0x64766865u

/**
 * Colour range of the current video. Two sources, because AVFoundation surfaces this differently by
 * container:
 *  1. Progressive / local — the asset's video track has a [CMFormatDescription]: Dolby Vision from the
 *     codec sub-type, HDR10/HLG from the transfer-function extension. Exact, incl. DV.
 *  2. HLS — the asset exposes **no** video track (HLS uses internal variants), so we read the decoded
 *     frame's transfer function off the [output] tap. This tells HDR10/HLG/SDR but **cannot** tell
 *     Dolby Vision apart from HDR10 (DV decodes to a PQ buffer). Apple surfaces no per-variant codec.
 * Pointer identity against the interned transfer constants throughout — CFEqual isn't importable in K/N.
 */
internal fun videoRangeOf(item: AVPlayerItem?, output: AVPlayerItemVideoOutput?, fallback: VideoRange): VideoRange {
    val tracks = item?.asset?.tracksWithMediaType(AVMediaTypeVideo)
    val track = tracks?.firstOrNull() as? AVAssetTrack
    val desc = track?.formatDescriptions?.firstOrNull() as? CMFormatDescriptionRef
    if (desc != null) {
        val sub = CMFormatDescriptionGetMediaSubType(desc)
        if (sub == FOUR_CC_DVH1 || sub == FOUR_CC_DVHE) return VideoRange.DOLBY_VISION
        val ext = CMFormatDescriptionGetExtension(desc, kCMFormatDescriptionExtension_TransferFunction)
        return transferToRange(ext) ?: VideoRange.SDR
    }
    // HLS: no video AVAssetTrack — read the decoded frame's transfer function off the video-output tap.
    val time = item?.currentTime()
    val hasNew = output != null && time != null && output.hasNewPixelBufferForItemTime(time)
    val buffer = when {
        !hasNew || output == null || time == null -> null
        else -> output.copyPixelBufferForItemTime(time, null)
    }
    val tf = buffer?.let { CVBufferGetAttachment(it, kCVImageBufferTransferFunctionKey, null) }
    val range = if (buffer == null) fallback else (transferToRange(tf) ?: VideoRange.SDR)
    buffer?.let { CVBufferRelease(it) }
    return range
}

/** HDR range from a CoreVideo/CoreMedia transfer-function value, matched by string — pointer identity fails. */
private fun transferToRange(cf: COpaquePointer?): VideoRange? = when (cfStringValue(cf)) {
    "SMPTE_ST_2084_PQ" -> VideoRange.HDR10
    "ITU_R_2100_HLG" -> VideoRange.HLG
    null -> null
    else -> VideoRange.SDR
}

/** A CFString's value as a Kotlin string, or null. */
private fun cfStringValue(cf: COpaquePointer?): String? =
    cf?.let { CFStringGetCStringPtr(it.reinterpret(), kCFStringEncodingUTF8)?.toKString() }
