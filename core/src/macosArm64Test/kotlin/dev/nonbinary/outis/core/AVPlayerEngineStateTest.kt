/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.CoreMedia.CMTimeMake
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSRunLoop
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSinceNow
import platform.Foundation.writeToFile
import kotlin.test.Test
import kotlin.test.assertEquals

class AVPlayerEngineStateTest {

    @Test
    fun aFileAVFoundationCannotOpenEndsInASourceError() {
        val path = NSTemporaryDirectory() + "outis-not-a-movie.mkv"
        NSString.create(string = "not a movie").writeToFile(path, true, NSUTF8StringEncoding, null)
        val engine = AVPlayerEngine(PlayerConfig())
        try {
            engine.setMediaItem(MediaItem(source = MediaSource.LocalFile(path)), autoPlay = true)
            runMainLoopUntil(timeoutSeconds = 10.0) { engine.state.value.error != null }

            val state = engine.state.value
            assertEquals(PlayerError.Category.SOURCE, state.error?.category)
            assertEquals(PlaybackState.IDLE, state.playbackState)
            assertEquals(false, state.isPlaying)
        } finally {
            engine.release()
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    @Test
    fun aClipThatPlaysToItsEndIsNotPlaying() {
        val path = NSTemporaryDirectory() + "outis-short-clip.mov"
        writeClip(path)
        val engine = AVPlayerEngine(PlayerConfig())
        try {
            engine.setMediaItem(MediaItem(source = MediaSource.LocalFile(path)), autoPlay = true)
            runMainLoopUntil(timeoutSeconds = 10.0) { engine.state.value.playbackState == PlaybackState.ENDED }

            val state = engine.state.value
            assertEquals(PlaybackState.ENDED, state.playbackState)
            assertEquals(false, state.isPlaying)
        } finally {
            engine.release()
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    // The engine's observers resolve on the main queue, which a test drains only by running the loop.
    private fun runMainLoopUntil(timeoutSeconds: Double, done: () -> Boolean) {
        val deadline = NSDate.dateWithTimeIntervalSinceNow(timeoutSeconds)
        while (!done() && deadline.timeIntervalSinceNow > 0) {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(LOOP_SLICE_SECONDS))
        }
    }

    // A silent H.264 movie of CLIP_FRAMES frames. Video only, so it plays on a CI host with no audio device.
    private fun writeClip(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        val writer = AVAssetWriter(NSURL.fileURLWithPath(path), AVFileTypeQuickTimeMovie!!, null)
        val settings = mapOf<Any?, Any?>(
            AVVideoCodecKey to AVVideoCodecTypeH264,
            AVVideoWidthKey to CLIP_SIZE,
            AVVideoHeightKey to CLIP_SIZE,
        )
        val input = AVAssetWriterInput(AVMediaTypeVideo!!, settings)
        val adaptor = AVAssetWriterInputPixelBufferAdaptor(input, null)
        writer.addInput(input)
        writer.startWriting()
        writer.startSessionAtSourceTime(CMTimeMake(0, CLIP_FPS))
        repeat(CLIP_FRAMES) { frame ->
            runMainLoopUntil(timeoutSeconds = 5.0) { input.readyForMoreMediaData }
            memScoped {
                val buffer = alloc<CVPixelBufferRefVar>()
                CVPixelBufferCreate(
                    null,
                    CLIP_SIZE.toULong(),
                    CLIP_SIZE.toULong(),
                    kCVPixelFormatType_32BGRA,
                    null,
                    buffer.ptr,
                )
                adaptor.appendPixelBuffer(buffer.value, CMTimeMake(frame.toLong(), CLIP_FPS))
                CVPixelBufferRelease(buffer.value)
            }
        }
        input.markAsFinished()
        writer.finishWritingWithCompletionHandler {}
        runMainLoopUntil(timeoutSeconds = 5.0) { writer.status != AVAssetWriterStatusWriting }
    }

    private companion object {
        const val LOOP_SLICE_SECONDS = 0.05
        const val CLIP_SIZE = 64
        const val CLIP_FPS = 25
        const val CLIP_FRAMES = 10
    }
}
