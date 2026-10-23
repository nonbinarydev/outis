/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.PlayerState
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.ads.Ad
import dev.nonbinary.outis.core.ads.AdBreak
import dev.nonbinary.outis.core.ads.AdController
import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.source.MediaItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerControlsStateTest {

    @Test
    fun aScrubWithoutAdsSeeksWhereItWasDropped() {
        val player = RecordingPlayer(positionMs = 10_000)
        val state = PlayerControlsState(player, AUTO_HIDE_MS)

        state.onScrubMove(30_000)
        state.onScrubCommit()

        assertEquals(listOf(30_000L), player.seeks)
    }

    @Test
    fun aScrubPastAnUnwatchedBreakStopsAtItsStart() {
        val player = RecordingPlayer(positionMs = 10_000)
        val ads = AdController(listOf(AdBreak("mid", startMs = 20_000, ads = listOf(Ad("a", durationMs = 10_000)))))
        val state = PlayerControlsState(player, AUTO_HIDE_MS, ads)

        state.onScrubMove(30_000)
        state.onScrubCommit()

        assertEquals(listOf(20_000L), player.seeks)
    }

    @Test
    fun skipAdWaitsForTheSkipOffsetThenSeeksPastTheBreak() {
        val player = RecordingPlayer(positionMs = 0)
        val preroll = AdBreak("pre", startMs = 0, ads = listOf(Ad("a", durationMs = 8_000, skipOffsetMs = 5_000)))
        val ads = AdController(listOf(preroll))
        val state = PlayerControlsState(player, AUTO_HIDE_MS, ads)

        ads.onPosition(2_000)
        state.adSnapshot = ads.state.value
        state.skipAd()
        assertEquals(emptyList(), player.seeks)

        ads.onPosition(6_000)
        state.adSnapshot = ads.state.value
        state.skipAd()
        assertEquals(listOf(8_000L), player.seeks)
    }

    @Test
    fun theAdStateComesFromTheController() {
        val ads = AdController(listOf(AdBreak("mid", startMs = 20_000, ads = listOf(Ad("a", durationMs = 10_000)))))
        val state = PlayerControlsState(RecordingPlayer(positionMs = 0), AUTO_HIDE_MS, ads)
        assertEquals(listOf(20_000L), state.adCuePoints)
        assertFalse(state.isInAdBreak)

        ads.onPosition(21_000)
        state.adSnapshot = ads.state.value

        assertTrue(state.isInAdBreak)
        assertEquals(9_000, state.adState?.adRemainingMs)
    }

    @Test
    fun hidingTheOverlayHoldsFocusOnlyIfThePlayerHadIt() {
        val state = PlayerControlsState(RecordingPlayer(positionMs = 0), AUTO_HIDE_MS)

        state.playerHasFocus = true
        state.hideControls()
        assertTrue(state.holdsFocus)

        state.showControls()
        state.playerHasFocus = false
        state.hideControls()
        assertFalse(state.holdsFocus)
    }

    private companion object {
        const val AUTO_HIDE_MS = 3_000L
    }
}

/** A player that records seeks and otherwise does nothing. */
private class RecordingPlayer(positionMs: Long) : VideoPlayer {
    val seeks = mutableListOf<Long>()
    override val state = MutableStateFlow(PlayerState(positionMs = positionMs))
    override val events = MutableSharedFlow<PlayerEvent>()
    override val nativePlayerHandle: Any? = null

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
    }

    override fun setMediaItem(item: MediaItem, autoPlay: Boolean) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun setPlaybackSpeed(speed: Float) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun setMuted(muted: Boolean) = Unit
    override fun stop() = Unit
    override fun release() = Unit
    override fun addComponent(component: PlayerComponent) = Unit
    override fun removeComponent(component: PlayerComponent) = Unit
}
