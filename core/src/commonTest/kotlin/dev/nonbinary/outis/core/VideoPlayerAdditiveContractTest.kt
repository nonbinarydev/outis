/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.track.MediaTrack
import dev.nonbinary.outis.core.track.TrackType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CI tripwire: [MinimalPlayer] implements ONLY the pre-existing [VideoPlayer] members and relies on
 * the default bodies of [VideoPlayer.selectTrack] / [VideoPlayer.clearTextTrack]. If anyone makes
 * those (or any future track method) abstract, this file stops compiling — which is the point:
 * track additions must stay source-additive.
 */
private object MinimalPlayer : VideoPlayer {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    override val events: SharedFlow<PlayerEvent> = MutableSharedFlow<PlayerEvent>().asSharedFlow()
    override fun setMediaItem(item: MediaItem, autoPlay: Boolean) {}
    override fun play() {}
    override fun pause() {}
    override fun seekTo(positionMs: Long) {}
    override fun setPlaybackSpeed(speed: Float) {}
    override fun setVolume(volume: Float) {}
    override fun setMuted(muted: Boolean) {}
    override fun stop() {}
    override fun release() {}
    override val nativePlayerHandle: Any? = null
    override fun addComponent(component: PlayerComponent) {}
    override fun removeComponent(component: PlayerComponent) {}
    // selectTrack / clearTextTrack intentionally NOT overridden — they rely on default bodies.
}

class VideoPlayerAdditiveContractTest {

    @Test
    fun trackMethods_haveDefaultNoOpBodies() {
        MinimalPlayer.selectTrack(MediaTrack("x", TrackType.TEXT))
        MinimalPlayer.clearTextTrack()
        assertNull(MinimalPlayer.state.value.selectedTextTrackId)
        assertTrue(MinimalPlayer.state.value.textTracks.isEmpty())
    }

    @Test
    fun fakeTracksChanged_populatesStateAndSelection() = runTest {
        val p = FakeVideoPlayer(backgroundScope)
        p.simulateTracksChanged(
            audio = listOf(
                MediaTrack("a-en", TrackType.AUDIO, language = "en", isSelected = true),
                MediaTrack("a-de", TrackType.AUDIO, language = "de"),
            ),
            text = listOf(MediaTrack("t-en", TrackType.TEXT, language = "en")),
        )
        assertEquals(2, p.state.value.audioTracks.size)
        assertEquals(1, p.state.value.textTracks.size)
        assertEquals("a-en", p.state.value.selectedAudioTrackId)
        assertNull(p.state.value.selectedTextTrackId) // nothing pre-selected => subtitles off
    }

    @Test
    fun selectTrack_movesSelectionWithinTypeAndClearTurnsSubtitlesOff() = runTest {
        val p = FakeVideoPlayer(backgroundScope)
        p.simulateTracksChanged(
            audio = listOf(MediaTrack("a-en", TrackType.AUDIO, isSelected = true), MediaTrack("a-de", TrackType.AUDIO)),
            text = listOf(MediaTrack("t-en", TrackType.TEXT), MediaTrack("t-fr", TrackType.TEXT)),
        )

        p.selectTrack(MediaTrack("a-de", TrackType.AUDIO))
        assertEquals("a-de", p.state.value.selectedAudioTrackId)
        assertTrue(p.state.value.audioTracks.single { it.id == "a-de" }.isSelected)
        assertTrue(p.state.value.audioTracks.none { it.id == "a-en" && it.isSelected })

        p.selectTrack(MediaTrack("t-fr", TrackType.TEXT))
        assertEquals("t-fr", p.state.value.selectedTextTrackId)

        p.clearTextTrack()
        assertNull(p.state.value.selectedTextTrackId)
        assertTrue(p.state.value.textTracks.none { it.isSelected })
    }
}
