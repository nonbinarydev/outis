/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.track.MediaTrack
import kotlinx.collections.immutable.ImmutableList

/**
 * One-shot player events.
 *
 * A sealed **interface** (not an enum) on purpose: the ads/QoS/DRM/downloads roadmap adds new
 * subtypes without touching core. Every event is timed — [positionMs] (content time) plus a
 * monotonic [elapsedRealtimeMs] from which QoS adapters derive startup time, rebuffer duration
 * and seek latency. Shipping these timestamps in v1 is load-bearing: adding them later would be
 * a breaking change to every event.
 *
 * The QoS signals ([BitrateChanged], [BandwidthSample], [DroppedFrames]) are emitted in v1 even
 * though nothing consumes them yet, so a Conviva/Mux adapter attaches later to a populated stream.
 */
sealed interface PlayerEvent {
    /** Content position when the event occurred. */
    val positionMs: Long

    /** Monotonic timestamp (ms). Use *deltas* between events; absolute value has no wall-clock meaning. */
    val elapsedRealtimeMs: Long

    /**
     * Playback stalled and the engine is refilling its buffer. Pair with the next [BufferingEnded]
     * and subtract the [elapsedRealtimeMs] values to get rebuffer duration. Also fires for the
     * *initial* buffering of a new item, so QoS adapters must not treat every occurrence as a
     * mid-playback rebuffer — use [FirstFrameRendered] to tell startup from re-buffering.
     */
    data class BufferingStarted(
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /** The engine has enough buffered media to resume; closes the preceding [BufferingStarted]. */
    data class BufferingEnded(
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * A seek was requested. [positionMs] is where playback was *before* the seek, so
     * `targetMs - positionMs` is the jump distance; pair with [SeekCompleted] for seek latency.
     */
    data class SeekStarted(
        /** Requested destination in content time (ms). The engine may land on a nearby keyframe instead. */
        val targetMs: Long,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * The seek finished; [positionMs] is where playback actually landed, which can differ from the
     * requested `SeekStarted.targetMs` when the engine snaps to a keyframe or clamps to the
     * seekable window.
     */
    data class SeekCompleted(
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /** First decoded frame presented on screen — the startup-time anchor. */
    data class FirstFrameRendered(
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * The player moved to a different item — a new [dev.nonbinary.outis.core.source.MediaItem] was
     * loaded, or a playlist advanced. Treat it as the boundary of a viewing session: reset per-item
     * QoS accumulators here.
     */
    data class MediaItemTransition(
        /** The item now loaded, or `null` when the player was cleared and nothing is loaded. */
        val item: MediaItem?,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * The coarse lifecycle state changed. This is the event-stream mirror of the state exposed by
     * the player; **not** a play/pause signal — use [IsPlayingChanged] for that.
     */
    data class PlaybackStateChanged(
        /** The state just entered. */
        val state: PlaybackState,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * Whether media is actually advancing changed. Distinct from [PlaybackStateChanged]: this goes
     * `false` for buffering, seeking and audio-focus loss as well as an explicit pause, so it is the
     * right signal for driving a play/pause icon or a watch-time timer.
     */
    data class IsPlayingChanged(
        /** `true` when frames are actually being presented; `false` for pause, buffering or a stall. */
        val isPlaying: Boolean,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    // --- QoS signals: emitted in v1 even with no consumer ---

    /**
     * Adaptive streaming switched the video rendition up or down. Fires on every switch, including
     * the first one after start-up, so consecutive events describe the whole ABR ladder walk.
     */
    data class BitrateChanged(
        /** The rendition now being decoded. Its fields are individually nullable — see [VideoFormat]. */
        val format: VideoFormat,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * A throughput estimate from the engine's bandwidth meter. This is the *measured network*
     * estimate, **not** the bitrate of the rendition being played — for that use [BitrateChanged].
     */
    data class BandwidthSample(
        /** Estimated available throughput in **bits** per second (not bytes). */
        val bitsPerSecond: Long,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * The video renderer dropped frames — a rendering-performance signal, distinct from network
     * trouble. Currently emitted by the Android engine only.
     */
    data class DroppedFrames(
        /** Frames dropped **since the previous [DroppedFrames] event**, not a running total. Sum to accumulate. */
        val count: Int,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * The native engine was (re)constructed. Analytics SDKs bound to the concrete native player
     * must re-bind on this signal (e.g. after an Android config-change reattach).
     */
    data class NativePlayerAttached(
        /**
         * The platform player object — `ExoPlayer` on Android, `AVPlayer` on iOS, the
         * `HTMLVideoElement` on web — typed as `Any?` to keep core platform-agnostic. Cast with
         * `as?` in platform source sets; `null` means no native player is currently attached.
         */
        val handle: Any?,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * Playback reached the end of the current item. For a playlist a [MediaItemTransition] follows;
     * for a single item the player idles at the end rather than releasing itself.
     */
    data class Ended(
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * Playback stopped and the engine could not recover. Contrast with [PlaybackRecovered], which
     * reports a fault playback survived. Hosts should surface an error UI and offer a retry.
     */
    data class FatalError(
        /** The classified failure — inspect [PlayerError] for the category and any underlying cause. */
        val error: PlayerError,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /**
     * The engine **auto-recovered** from a transient fault (a prolonged stall, or falling behind the
     * live window) instead of surfacing a [FatalError] — playback continues. Informational: a host may
     * surface a brief "reconnecting…" hint or feed it to QoS as a recoverable-error signal.
     */
    data class PlaybackRecovered(
        /** Which transient fault was recovered from — see [RecoveryReason] for platform coverage. */
        val reason: RecoveryReason,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent

    /** The available audio/text tracks (or the current selection) changed. */
    data class TracksChanged(
        /**
         * Every selectable audio track, in engine order. Empty when the engine exposes no
         * selectable alternatives, which is **not** the same as the asset having no audio.
         */
        val audioTracks: ImmutableList<MediaTrack>,
        /** Every selectable subtitle/caption track, in engine order; empty when the asset has none. */
        val textTracks: ImmutableList<MediaTrack>,
        override val positionMs: Long,
        override val elapsedRealtimeMs: Long,
    ) : PlayerEvent
}

/** Why [PlayerEvent.PlaybackRecovered] fired. */
enum class RecoveryReason {
    /** A live stream fell behind its seekable window; the engine re-snapped to the live edge (Android). */
    BEHIND_LIVE_WINDOW,

    /** Playback stalled (buffering with no progress) for too long; the engine nudged it back to life (iOS;
     *  Android/Web engines self-recover natively). */
    STALL,
}
