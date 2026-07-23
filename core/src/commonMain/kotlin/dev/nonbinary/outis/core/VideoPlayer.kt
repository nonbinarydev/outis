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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Engine-agnostic video player contract.
 *
 * One implementation per platform, each wrapping the native engine — Media3/ExoPlayer on
 * Android, AVPlayer on iOS, Shaka on Web. No platform type and no Compose type ever appears in
 * this interface: UI lives in the separate `:ui` module, and analytics/ads adapters
 * attach through [PlayerComponent].
 *
 * Transport calls ([play], [pause], [seekTo], …) are **fire-and-forget**: they request a change
 * and return immediately; the result lands on [state] and [events]. This mirrors how Media3's
 * `Player` and AVFoundation's `AVPlayer` actually behave.
 *
 * Threading: methods are safe to call from any thread. Each actual marshals to whatever thread
 * its engine requires (the main thread, on iOS) internally.
 *
 * Construct one with the [VideoPlayer] factory function.
 */
interface VideoPlayer {

    /** Always-readable, conflated snapshot of the player. */
    val state: StateFlow<PlayerState>

    /**
     * One-shot, time-stamped events (no replay). Analytics/QoS adapters consume this stream;
     * every event carries [PlayerEvent.positionMs] and a monotonic [PlayerEvent.elapsedRealtimeMs].
     */
    val events: SharedFlow<PlayerEvent>

    /** Set (and implicitly prepare) the item to play. Pass [autoPlay] to start once ready. */
    fun setMediaItem(item: MediaItem, autoPlay: Boolean = false)

    /**
     * Begin (or resume) playback. Fire-and-forget: this sets play *intent*, so
     * [PlayerState.playWhenReady] flips immediately while [PlayerState.isPlaying] only becomes `true`
     * once frames actually advance — they differ whenever the player is still buffering.
     */
    fun play()

    /** Pause playback, leaving the current position intact. Safe to call when already paused. */
    fun pause()

    /**
     * Seek to [positionMs] in **content** time. Emits [PlayerEvent.SeekStarted] then, once the
     * engine resolves it, [PlayerEvent.SeekCompleted].
     */
    fun seekTo(positionMs: Long)

    /**
     * Set the playback rate, where `1f` is normal speed. Audio pitch correction is the engine's own
     * behaviour and is not normalised across platforms. Rate is **not** reset by [setMediaItem].
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Set the output volume in the range `0f`..`1f`. Independent of [setMuted] — muting does not zero
     * this value, so unmuting restores the level set here.
     */
    fun setVolume(volume: Float)

    /** Mute or unmute without disturbing the [setVolume] level, which is restored on unmute. */
    fun setMuted(muted: Boolean)

    /** Stop playback and clear the current item, keeping the player reusable. */
    fun stop()

    /** Release all native resources. **Idempotent** — safe to call more than once. */
    fun release()

    /**
     * Escape hatch to the underlying native player (`ExoPlayer` / `AVPlayer` / `shaka.Player`),
     * or `null` before it exists / after [release]. Analytics SDKs that bind to the concrete
     * player cast this. Prefer [PlayerHost.nativePlayerHandle] when you must react to re-creation.
     */
    val nativePlayerHandle: Any?

    /** Register an extension (analytics, ads, …). The seam exists in v1; nothing ships registered. */
    fun addComponent(component: PlayerComponent)

    /** Detach a previously [added][addComponent] extension. A no-op if it was never registered. */
    fun removeComponent(component: PlayerComponent)

    // --- Track selection -----------------------------------------------------------------------
    // ADDITIVE RULE: these MUST keep default bodies (never make them abstract). Doing so is a hard
    // source-break for every implementer (ExoPlayerEngine, FakeVideoPlayer, future iOS/Web actuals)
    // and is guarded by VideoPlayerAdditiveContractTest.

    /**
     * Select an audio or text track from [PlayerState.audioTracks] / [PlayerState.textTracks].
     * Fire-and-forget: the engine applies the change and re-emits the track lists +
     * [PlayerEvent.TracksChanged]; the selection lands on [state] (don't assume it optimistically).
     */
    fun selectTrack(track: MediaTrack) {}

    /** Turn subtitles/captions off (clears the selected text track). */
    fun clearTextTrack() {}
}
