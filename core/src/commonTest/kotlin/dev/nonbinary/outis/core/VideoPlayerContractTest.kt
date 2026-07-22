/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.plugin.PlayerHost
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun item(url: String = "https://example.com/video.mp4") = MediaItem(MediaSource.Url(url))

/**
 * Pins the v1 contract — the four invariants and the state/event semantics every real engine
 * (Media3/AVPlayer/Shaka) must reproduce. Driven entirely through [FakeVideoPlayer].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerContractTest {

    @Test
    fun initialStateIsIdle() = runTest {
        val p = FakeVideoPlayer(backgroundScope)
        assertEquals(PlaybackState.IDLE, p.state.value.playbackState)
        assertFalse(p.state.value.isPlaying)
        assertNull(p.state.value.mediaItem)
        assertNull(p.nativePlayerHandle)
    }

    @Test
    fun setMediaItemAutoPlayBuffersButHoldsIntentUntilReady() = runTest {
        val p = FakeVideoPlayer(backgroundScope)
        p.setMediaItem(item(), autoPlay = true)
        assertEquals(PlaybackState.BUFFERING, p.state.value.playbackState)
        assertTrue(p.state.value.playWhenReady) // intent retained...
        assertFalse(p.state.value.isPlaying) // ...but not playing while buffering
        assertEquals(item(), p.state.value.mediaItem)
    }

    @Test
    fun becomesPlayingOnlyWhenReadyAndIntending() = runTest {
        val p = FakeVideoPlayer(backgroundScope)
        p.setMediaItem(item(), autoPlay = true)
        p.simulateReady(durationMs = 60_000)
        assertEquals(PlaybackState.READY, p.state.value.playbackState)
        assertTrue(p.state.value.isPlaying)

        p.pause()
        assertFalse(p.state.value.isPlaying)
        assertFalse(p.state.value.playWhenReady)
    }

    @Test
    fun durationNullAndIsLiveAreIndependent() = runTest {
        val p = FakeVideoPlayer(backgroundScope)
        p.setMediaItem(item())
        // VOD whose duration hasn't resolved: null duration, NOT live.
        assertNull(p.state.value.durationMs)
        assertFalse(p.state.value.isLive)
        // A live stream: explicitly live, duration still null — the two never conflate.
        p.simulateReady(durationMs = null, isLive = true, seekable = false)
        assertTrue(p.state.value.isLive)
        assertNull(p.state.value.durationMs)
    }

    @Test
    fun seekSetsPendingTargetThenClearsOnComplete() = runTest {
        val p = FakeVideoPlayer(backgroundScope)
        p.setMediaItem(item())
        p.simulateReady(60_000)

        p.seekTo(30_000)
        assertEquals(30_000, p.state.value.pendingSeekTargetMs)
        assertEquals(0, p.state.value.positionMs) // not committed yet

        p.completeSeek()
        assertNull(p.state.value.pendingSeekTargetMs)
        assertEquals(30_000, p.state.value.positionMs)
    }

    @Test
    fun everyEventCarriesContentPositionAndMonotonicTime() = runTest {
        val seen = mutableListOf<PlayerEvent>()
        val p = FakeVideoPlayer(backgroundScope)
        val job: Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            p.events.collect { seen.add(it) }
        }

        p.setMediaItem(item(), autoPlay = true)
        p.simulateReady(60_000)
        p.simulateFirstFrame()
        p.advanceTo(10_000)
        p.seekTo(25_000)
        p.completeSeek()
        job.cancel()

        assertTrue(seen.isNotEmpty())
        // Invariant: elapsedRealtimeMs is monotonic non-decreasing across the stream.
        val times = seen.map { it.elapsedRealtimeMs }
        assertEquals(times.sorted(), times)
        // Invariant: the startup anchor and seek lifecycle events are present.
        assertTrue(seen.any { it is PlayerEvent.FirstFrameRendered })
        assertTrue(seen.any { it is PlayerEvent.SeekStarted && it.targetMs == 25_000L })
        assertTrue(seen.any { it is PlayerEvent.SeekCompleted })
    }

    @Test
    fun releaseIsIdempotent() = runTest {
        val p = FakeVideoPlayer(backgroundScope)
        p.release()
        p.release() // must not throw
    }

    @Test
    fun componentAttachesToSeamAndReceivesEvents() = runTest {
        val p = FakeVideoPlayer(backgroundScope)
        val seen = mutableListOf<PlayerEvent>()
        var attached = false
        var detached = false
        var collector: Job? = null

        val component = object : PlayerComponent {
            override fun attach(host: PlayerHost) {
                attached = true
                collector = host.scope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    host.events.collect { seen.add(it) }
                }
            }

            override fun detach() {
                detached = true
                collector?.cancel()
            }
        }

        p.addComponent(component)
        assertTrue(attached)

        p.setMediaItem(item(), autoPlay = true)
        p.simulateReady(60_000)
        assertTrue(seen.any { it is PlayerEvent.MediaItemTransition })

        p.removeComponent(component)
        assertTrue(detached)
    }

    @Test
    fun nativeHandleReattachIsObservableForReBinding() = runTest {
        val p = FakeVideoPlayer(backgroundScope)
        val handles = mutableListOf<Any?>()
        var collector: Job? = null
        p.addComponent(object : PlayerComponent {
            override fun attach(host: PlayerHost) {
                collector = host.scope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    host.nativePlayerHandle.collect { handles.add(it) }
                }
            }
            override fun detach() { collector?.cancel() }
        })

        val first = Any()
        val rebuilt = Any()
        p.simulateNativeAttached(first)
        p.simulateNativeAttached(rebuilt) // e.g. Android config-change reattach

        assertTrue(handles.contains(first))
        assertTrue(handles.contains(rebuilt))
    }
}
