/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.ads.AdState
import dev.nonbinary.outis.core.chapters.Chapter
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.track.MediaTrack
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Immutable snapshot of everything a UI needs to render the player, carried on [VideoPlayer.state].
 *
 * A few fields exist now only to keep the published contract stable as the roadmap (ads, tracks,
 * QoS) lands — they are reserved data slots, never behaviour. See the field docs.
 */
data class PlayerState(
    /**
     * Coarse engine lifecycle stage. Orthogonal to [isPlaying] and [playWhenReady]: `READY` only means the
     * engine *could* render, not that it is. Resets to [PlaybackState.IDLE] when a load fails or the
     * player is released.
     */
    val playbackState: PlaybackState = PlaybackState.IDLE,
    /** Whether frames are actually advancing right now. */
    val isPlaying: Boolean = false,
    /**
     * Play *intent*, kept separate from [isPlaying]. Disambiguates "buffering while trying to
     * play" from "deliberately paused" — the overlay's play/pause icon binds to this, not [isPlaying].
     */
    val playWhenReady: Boolean = false,
    /** Content position in ms. Never ad time — ad position will live on [adState] when ads ship. */
    val positionMs: Long = 0,
    /**
     * How far ahead media is buffered, in ms, expressed as an **absolute timeline position** (not a
     * duration ahead of [positionMs]) — subtract [positionMs] for the buffered-ahead amount. `0` before
     * anything is loaded. On iOS this is currently approximated as [positionMs], so **do not** rely on it
     * for a meaningful buffer-ahead readout there.
     */
    val bufferedPositionMs: Long = 0,
    /**
     * Duration in ms, or `null` when unknown / not yet resolved. **Never** overloaded to signal
     * live — use [isLive] for that, so a VOD asset whose duration hasn't resolved yet isn't
     * mistaken for a livestream.
     */
    val durationMs: Long? = null,
    /**
     * Whether the loaded asset is a livestream. The authoritative live signal — never infer live from a
     * `null` [durationMs]. A live asset may still report a finite [durationMs] (the DVR window length).
     */
    val isLive: Boolean = false,
    /**
     * Whether seeking is currently permitted. `false` until the asset is loaded far enough to know, and
     * for live streams with no DVR window — bind the scrubber's enabled state to this rather than to
     * [durationMs] being non-`null`.
     */
    val isSeekable: Boolean = false,
    /**
     * Target of an in-flight seek: set on [PlayerEvent.SeekStarted], cleared on
     * [PlayerEvent.SeekCompleted]. Lets the scrubber show the requested position instead of
     * rubber-banding back to the last sampled position on slow (live/HLS) seeks.
     */
    val pendingSeekTargetMs: Long? = null,
    /**
     * Rate multiplier applied to playback; `1f` is normal speed, `2f` double speed. Never `0f` — pause
     * instead. Audio pitch correction is the platform engine's default behaviour.
     */
    val playbackSpeed: Float = 1f,
    /**
     * Player output gain in the range `0f`..`1f`, independent of the device's system volume. Retained
     * across [isMuted] toggles, so unmuting restores this level rather than jumping to full scale.
     */
    val volume: Float = 1f,
    /** Whether output is silenced. **Independent of [volume]** — a muted player keeps its volume value. */
    val isMuted: Boolean = false,
    /**
     * Decoded video dimensions of the current rendition, or `null` before the first frame is decoded (and
     * for audio-only content). Changes mid-playback as adaptive streaming switches rendition. **Android
     * only** at present — the iOS and web engines leave this `null`, so never gate layout on it.
     */
    val videoSize: VideoSize? = null,
    /** What is currently loaded, so late subscribers can read "what's playing" without replaying events. */
    val mediaItem: MediaItem? = null,
    /**
     * The failure that stopped playback, or `null` when healthy. Set alongside [PlaybackState.IDLE]; it is
     * **sticky** until the next load or a release, so clear your own error UI on load rather than waiting
     * for this to return to `null` on its own.
     */
    val error: PlayerError? = null,

    // --- Reserved for the roadmap; always default/empty in v1 ---
    /** Reserved — populated when track/quality selection ships. */
    val currentTrack: VideoFormat? = null,
    /** Reserved — populated when video track/quality selection ships. */
    val availableTracks: ImmutableList<VideoFormat> = persistentListOf(),
    /**
     * Ad playback state — `null` when no ad is active. For **client-side** ads (IMA/CSAI) the engine
     * populates this from the ad SDK; for **server-side** ads the app's `AdController` produces the same
     * [AdState] type, so the chrome consumes one shape either way.
     */
    val adState: AdState? = null,

    // --- Audio & text (subtitle) tracks. Append-only — never reorder these. ---
    /**
     * Audio renditions offered by the current asset, in manifest order; empty until the manifest is parsed
     * and for single-audio content that exposes no alternatives.
     */
    val audioTracks: ImmutableList<MediaTrack> = persistentListOf(),
    /**
     * Subtitle/caption tracks offered by the current asset, in manifest order. Only *selectable* tracks —
     * burnt-in subtitles are invisible here. Empty until the manifest is parsed.
     */
    val textTracks: ImmutableList<MediaTrack> = persistentListOf(),
    /**
     * [MediaTrack.id] of the active audio track, or `null` when the engine hasn't reported a selection yet
     * (**not** "no audio"). Unlike [selectedTextTrackId], `null` here never means "off".
     */
    val selectedAudioTrackId: String? = null,
    /** `null` == subtitles **off** (not "unknown"). */
    val selectedTextTrackId: String? = null,

    /**
     * Embedded chapter markers (MP4/M4V QuickTime/Nero, Matroska `Chapters`), sorted by start; empty when
     * the container carries none or the platform can't read them (web). Populated at load time on Android
     * and iOS, both parsing the local container via
     * [dev.nonbinary.outis.core.chapters.ChapterExtractor].
     */
    val chapters: ImmutableList<Chapter> = persistentListOf(),
)

/**
 * Coarse engine lifecycle stage, mapped from the underlying platform player. Deliberately minimal so all
 * three engines agree — anything finer (stalls, seeks, ad breaks) is a [PlayerEvent], not a state.
 */
enum class PlaybackState {
    /** Nothing loaded, load failed, or the player was released. The state after any [PlayerError]. */
    IDLE,

    /** Loading or rebuffering: not enough media to render, whatever [PlayerState.playWhenReady] says. */
    BUFFERING,

    /**
     * Enough media buffered to render. Says nothing about whether frames are advancing — see
     * [PlayerState.isPlaying].
     */
    READY,

    /** Playback reached the end of the item. Position stays at the end until a seek or a new load. */
    ENDED,
}

/**
 * Decoded video frame dimensions in **pixels**, before any display scaling or aspect-ratio correction, so
 * `width / height` is the pixel aspect ratio rather than necessarily the intended display one.
 */
data class VideoSize(
    /** Frame width in pixels. */
    val width: Int,
    /** Frame height in pixels. */
    val height: Int,
)

/** A selectable/active rendition. Reserved — populated when track selection ships. */
data class VideoFormat(
    /** Peak bandwidth of the rendition in **bits per second**, or `null` when the manifest omits it. */
    val bitrate: Int? = null,
    /** Coded frame width in pixels, or `null` when unknown (audio-only renditions). */
    val width: Int? = null,
    /** Coded frame height in pixels, or `null` when unknown (audio-only renditions). */
    val height: Int? = null,
    /**
     * RFC 6381 codec string as written in the manifest (e.g. `"avc1.640028"`), or `null` when absent.
     * Passed through verbatim — **not** normalised across platforms, so match on prefixes, not equality.
     */
    val codecs: String? = null,
    /** Frame rate in frames per second, or `null` when the manifest omits it. */
    val frameRate: Float? = null,
)
