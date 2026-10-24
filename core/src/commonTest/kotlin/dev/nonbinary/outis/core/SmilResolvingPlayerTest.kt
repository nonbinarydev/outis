/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.net.HttpFetch
import dev.nonbinary.outis.core.net.HttpResult
import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.core.source.SmilSelector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SmilResolvingPlayerTest {

    private val smilUrl = "https://origin.example.com/v/a.smil"
    private val smilItem = MediaItem(MediaSource.Smil(smilUrl))
    private val plainItem = MediaItem(MediaSource.Url("https://e/b.mp4"))
    private val document = HttpResult.Ok(
        """<smil><body><video src="a.mpd"/><video src="a.m3u8"/></body></smil>""",
        smilUrl,
    )
    private val loadDash = "setMediaItem https://origin.example.com/v/a.mpd DASH"

    private val engine = RecordingEngine()
    private val network = Network()

    @Test
    fun aPlainItemGoesStraightToTheEngine() = runTest {
        val player = player()

        player.setMediaItem(plainItem, autoPlay = true)
        player.play()

        assertEquals(listOf("setMediaItem https://e/b.mp4 null autoPlay=true start=null", "play"), engine.calls)
        assertTrue(network.requested.isEmpty())
        player.release()
    }

    @Test
    fun aSmilItemStopsTheCurrentOneThenLoadsTheChosenRendition() = runTest {
        val player = player()

        player.setMediaItem(smilItem)
        assertTrue(engine.calls.isEmpty())
        runCurrent()
        assertEquals(listOf("stop"), engine.calls)
        assertEquals(listOf(smilUrl), network.requested)

        network.respond(smilUrl, document)
        runCurrent()
        assertEquals(listOf("stop", "$loadDash autoPlay=false start=null"), engine.calls)
        player.release()
    }

    @Test
    fun transportWhileResolvingShapesTheLoadInsteadOfReachingTheEngine() = runTest {
        val player = player()

        player.setMediaItem(smilItem.copy(startPositionMs = 1_000))
        player.play()
        player.seekTo(42_000)
        runCurrent()
        network.respond(smilUrl, document)
        runCurrent()
        player.pause()
        player.seekTo(7)

        assertEquals(listOf("stop", "$loadDash autoPlay=true start=42000", "pause", "seekTo 7"), engine.calls)
        player.release()
    }

    @Test
    fun pauseWhileResolvingWithdrawsAutoPlay() = runTest {
        val player = player()

        player.setMediaItem(smilItem, autoPlay = true)
        player.pause()
        runCurrent()
        network.respond(smilUrl, document)
        runCurrent()

        assertEquals(listOf("stop", "$loadDash autoPlay=false start=null"), engine.calls)
        player.release()
    }

    @Test
    fun aNewerPlainItemSupersedesAPendingOne() = runTest {
        val player = player()

        player.setMediaItem(smilItem)
        runCurrent()
        player.setMediaItem(plainItem, autoPlay = true)
        network.respond(smilUrl, document)
        runCurrent()
        player.play()

        assertEquals(listOf("stop", "setMediaItem https://e/b.mp4 null autoPlay=true start=null", "play"), engine.calls)
        player.release()
    }

    @Test
    fun aNewerSmilItemSupersedesAPendingOne() = runTest {
        val player = player()
        val otherUrl = "https://origin.example.com/w/b.smil"

        player.setMediaItem(smilItem)
        runCurrent()
        player.setMediaItem(MediaItem(MediaSource.Url(otherUrl))) // a SMIL by its extension
        runCurrent()
        network.respond(otherUrl, HttpResult.Ok(document.body, otherUrl))
        network.respond(smilUrl, document)
        runCurrent()

        assertEquals(
            listOf("stop", "stop", "setMediaItem https://origin.example.com/w/a.mpd DASH autoPlay=false start=null"),
            engine.calls,
        )
        player.release()
    }

    @Test
    fun anItemSupersededBeforeItsResolutionRanNeverStopsItsSuccessor() = runTest {
        val player = player()

        player.setMediaItem(smilItem)
        player.setMediaItem(plainItem, autoPlay = true)
        runCurrent()

        assertEquals(listOf("setMediaItem https://e/b.mp4 null autoPlay=true start=null"), engine.calls)
        assertTrue(network.requested.isEmpty())
        player.release()
    }

    @Test
    fun aFailedResolutionIsReportedByTheEngine() = runTest {
        val player = player()

        player.setMediaItem(smilItem)
        runCurrent()
        network.respond(smilUrl, HttpResult.Status(403))
        runCurrent()

        assertEquals(listOf("stop", "failLoad SOURCE"), engine.calls)
        assertSame(smilItem, engine.failedItem)
        player.release()
    }

    @Test
    fun stopAbandonsAPendingLoad() = runTest {
        val player = player()

        player.setMediaItem(smilItem, autoPlay = true)
        runCurrent()
        player.stop()
        network.respond(smilUrl, document)
        runCurrent()
        player.play()

        assertEquals(listOf("stop", "stop", "play"), engine.calls)
        player.release()
    }

    @Test
    fun releaseAbandonsAPendingLoad() = runTest {
        val player = player()

        player.setMediaItem(smilItem)
        runCurrent()
        player.release()
        network.respond(smilUrl, document)
        runCurrent()

        assertEquals(listOf("stop", "release"), engine.calls)
    }

    @Test
    fun everythingElsePassesThroughWhileResolving() = runTest {
        val player = player()

        player.setMediaItem(smilItem)
        player.setVolume(0.5f)
        player.setMuted(true)
        player.setPlaybackSpeed(1.5f)

        assertEquals(listOf("volume 0.5", "muted true", "speed 1.5"), engine.calls)
        player.release()
    }

    @Test
    fun theBridgesSeeTheEngineBehindTheDecorator() {
        val player: VideoPlayer =
            SmilResolvingPlayer(engine, pickDash, TIMEOUT_MS, UnconfinedTestDispatcher(), network.fetch)

        assertSame(engine, player.engine)
        assertSame(engine, (engine as VideoPlayer).engine)
        player.release()
    }

    private fun TestScope.player() =
        SmilResolvingPlayer(engine, pickDash, TIMEOUT_MS, StandardTestDispatcher(testScheduler), network.fetch)

    private class Network {
        val requested = mutableListOf<String>()
        private val responses = mutableMapOf<String, CompletableDeferred<HttpResult>>()

        val fetch: HttpFetch = { url, _, _ ->
            requested += url
            response(url).await()
        }

        fun respond(url: String, result: HttpResult) {
            response(url).complete(result)
        }

        private fun response(url: String) = responses.getOrPut(url) { CompletableDeferred() }
    }

    private class RecordingEngine : PlatformEngine {
        val calls = mutableListOf<String>()
        var failedItem: MediaItem? = null

        override val state: StateFlow<PlayerState> = MutableStateFlow(PlayerState())
        override val events: SharedFlow<PlayerEvent> = MutableSharedFlow()
        override val nativePlayerHandle: Any? = null

        override fun setMediaItem(item: MediaItem, autoPlay: Boolean) {
            val url = (item.source as MediaSource.Url).url
            calls += "setMediaItem $url ${item.mimeType} autoPlay=$autoPlay start=${item.startPositionMs}"
        }

        override fun play() {
            calls += "play"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seekTo $positionMs"
        }

        override fun setPlaybackSpeed(speed: Float) {
            calls += "speed $speed"
        }

        override fun setVolume(volume: Float) {
            calls += "volume $volume"
        }

        override fun setMuted(muted: Boolean) {
            calls += "muted $muted"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun release() {
            calls += "release"
        }

        override fun addComponent(component: PlayerComponent) = Unit

        override fun removeComponent(component: PlayerComponent) = Unit

        override fun failLoad(item: MediaItem, error: PlayerError) {
            calls += "failLoad ${error.category}"
            failedItem = item
        }
    }

    private companion object {
        const val TIMEOUT_MS = 1_000
        val pickDash = SmilSelector { renditions, _ -> renditions.firstOrNull { it.format == MimeType.DASH } }
    }
}
