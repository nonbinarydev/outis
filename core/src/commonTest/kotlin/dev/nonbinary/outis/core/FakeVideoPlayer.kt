/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.plugin.PlayerHost
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.track.MediaTrack
import dev.nonbinary.outis.core.track.TrackType
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory [VideoPlayer] for core tests and (later) `:ui` previews/tests.
 *
 * It applies the same state/event semantics the real engines must, and exposes `simulate*` hooks
 * for transitions only an engine can cause (reaching READY, buffering, first frame, seek
 * resolution). Event timing is a deterministic monotonic counter, so tests can assert ordering
 * without a real clock.
 *
 * Transport methods are non-suspending (matching the interface), so events are published via
 * [MutableSharedFlow.tryEmit] over a 64-slot buffer — the same shape the real actuals use.
 */
class FakeVideoPlayer(
    private val hostScope: CoroutineScope,
    initialVolume: Float = 1f,
) : VideoPlayer {

    private val _state = MutableStateFlow(PlayerState(volume = initialVolume))
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val _native = MutableStateFlow<Any?>(null)
    override val nativePlayerHandle: Any? get() = _native.value

    private val components = mutableListOf<PlayerComponent>()
    private val host = object : PlayerHost {
        override val state: StateFlow<PlayerState> get() = this@FakeVideoPlayer.state
        override val events: SharedFlow<PlayerEvent> get() = this@FakeVideoPlayer.events
        override val scope: CoroutineScope = hostScope
        override val nativePlayerHandle: StateFlow<Any?> get() = _native
    }

    private var released = false
    private var eventClock = 0L

    private fun emit(make: (positionMs: Long, elapsedRealtimeMs: Long) -> PlayerEvent) {
        _events.tryEmit(make(_state.value.positionMs, ++eventClock))
    }

    // ---- VideoPlayer transport ----

    override fun setMediaItem(item: MediaItem, autoPlay: Boolean) {
        check(!released) { "player released" }
        _state.value = _state.value.copy(
            mediaItem = item,
            playbackState = PlaybackState.BUFFERING,
            // Reflect the load-time bundle (matches engine semantics): startPositionMs seeds the
            // position; startMuted forces mute on but never unmutes.
            positionMs = item.startPositionMs ?: 0,
            durationMs = null,
            isPlaying = false,
            playWhenReady = autoPlay,
            isMuted = item.startMuted || _state.value.isMuted,
            pendingSeekTargetMs = null,
            error = null,
        )
        emit { p, t -> PlayerEvent.MediaItemTransition(item, p, t) }
        emit { p, t -> PlayerEvent.BufferingStarted(p, t) }
        emit { p, t -> PlayerEvent.PlaybackStateChanged(PlaybackState.BUFFERING, p, t) }
    }

    override fun play() {
        check(!released) { "player released" }
        _state.value = _state.value.copy(playWhenReady = true)
        reconcilePlaying()
    }

    override fun pause() {
        check(!released) { "player released" }
        _state.value = _state.value.copy(playWhenReady = false)
        reconcilePlaying()
    }

    override fun seekTo(positionMs: Long) {
        check(!released) { "player released" }
        _state.value = _state.value.copy(pendingSeekTargetMs = positionMs)
        emit { p, t -> PlayerEvent.SeekStarted(positionMs, p, t) }
    }

    override fun setPlaybackSpeed(speed: Float) {
        _state.value = _state.value.copy(playbackSpeed = speed)
    }

    override fun setVolume(volume: Float) {
        _state.value = _state.value.copy(volume = volume)
    }

    override fun setMuted(muted: Boolean) {
        _state.value = _state.value.copy(isMuted = muted)
    }

    override fun stop() {
        _state.value = _state.value.copy(
            playbackState = PlaybackState.IDLE,
            isPlaying = false,
            mediaItem = null,
        )
    }

    override fun release() {
        if (released) return
        released = true
        _native.value = null
        components.toList().forEach { it.detach() }
        components.clear()
    }

    override fun addComponent(component: PlayerComponent) {
        components += component
        component.attach(host)
    }

    override fun removeComponent(component: PlayerComponent) {
        if (components.remove(component)) component.detach()
    }

    override fun selectTrack(track: MediaTrack) {
        check(!released) { "player released" }
        when (track.type) {
            TrackType.AUDIO -> _state.value = _state.value.copy(
                audioTracks = _state.value.audioTracks
                    .map { it.copy(isSelected = it.id == track.id) }
                    .toPersistentList(),
                selectedAudioTrackId = track.id,
            )
            TrackType.TEXT -> _state.value = _state.value.copy(
                textTracks = _state.value.textTracks.map { it.copy(isSelected = it.id == track.id) }.toPersistentList(),
                selectedTextTrackId = track.id,
            )
            TrackType.VIDEO -> return
        }
        emitTracksChanged()
    }

    override fun clearTextTrack() {
        check(!released) { "player released" }
        _state.value = _state.value.copy(
            textTracks = _state.value.textTracks.map { it.copy(isSelected = false) }.toPersistentList(),
            selectedTextTrackId = null,
        )
        emitTracksChanged()
    }

    // ---- simulate*: drive engine-only transitions in tests ----

    /** The engine resolved a new set of audio/text tracks. */
    fun simulateTracksChanged(audio: List<MediaTrack> = emptyList(), text: List<MediaTrack> = emptyList()) {
        _state.value = _state.value.copy(
            audioTracks = audio.toPersistentList(),
            textTracks = text.toPersistentList(),
            selectedAudioTrackId = audio.firstOrNull { it.isSelected }?.id,
            selectedTextTrackId = text.firstOrNull { it.isSelected }?.id,
        )
        emitTracksChanged()
    }

    private fun emitTracksChanged() =
        emit { p, t -> PlayerEvent.TracksChanged(_state.value.audioTracks, _state.value.textTracks, p, t) }

    /** The engine reached READY with a (possibly unknown) duration. */
    fun simulateReady(durationMs: Long?, isLive: Boolean = false, seekable: Boolean = true) {
        _state.value = _state.value.copy(
            playbackState = PlaybackState.READY,
            durationMs = durationMs,
            isLive = isLive,
            isSeekable = seekable,
        )
        emit { p, t -> PlayerEvent.BufferingEnded(p, t) }
        emit { p, t -> PlayerEvent.PlaybackStateChanged(PlaybackState.READY, p, t) }
        reconcilePlaying()
    }

    fun simulateFirstFrame() = emit { p, t -> PlayerEvent.FirstFrameRendered(p, t) }

    fun simulateBufferingStart() {
        _state.value = _state.value.copy(playbackState = PlaybackState.BUFFERING, isPlaying = false)
        emit { p, t -> PlayerEvent.BufferingStarted(p, t) }
    }

    /** Resolve an in-flight seek: the requested position commits and the pending target clears. */
    fun completeSeek() {
        val target = _state.value.pendingSeekTargetMs ?: _state.value.positionMs
        _state.value = _state.value.copy(positionMs = target, pendingSeekTargetMs = null)
        emit { p, t -> PlayerEvent.SeekCompleted(p, t) }
    }

    fun advanceTo(positionMs: Long) {
        _state.value = _state.value.copy(positionMs = positionMs)
    }

    fun simulateNativeAttached(handle: Any?) {
        _native.value = handle
        emit { p, t -> PlayerEvent.NativePlayerAttached(handle, p, t) }
    }

    fun simulateError(error: PlayerError) {
        _state.value = _state.value.copy(playbackState = PlaybackState.IDLE, isPlaying = false, error = error)
        emit { p, t -> PlayerEvent.FatalError(error, p, t) }
    }

    private fun reconcilePlaying() {
        val s = _state.value
        val playing = s.playWhenReady && s.playbackState == PlaybackState.READY
        if (playing != s.isPlaying) {
            _state.value = s.copy(isPlaying = playing)
            emit { p, t -> PlayerEvent.IsPlayingChanged(playing, p, t) }
        }
    }
}
