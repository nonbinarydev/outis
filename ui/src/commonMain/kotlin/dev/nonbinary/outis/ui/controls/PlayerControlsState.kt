/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nonbinary.outis.core.ExperimentalPlayerApi
import dev.nonbinary.outis.core.PlaybackState
import dev.nonbinary.outis.core.PlayerError
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.VideoRange
import dev.nonbinary.outis.core.ads.AdController
import dev.nonbinary.outis.core.ads.AdState
import dev.nonbinary.outis.core.chapters.Chapter
import dev.nonbinary.outis.core.thumbnails.ThumbnailCue
import dev.nonbinary.outis.core.track.MediaTrack
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The single source of truth a control overlay binds to. It collects `player.state` **once** and
 * exposes snapshot-backed, UI-ready derivations (so every control reads the same consistent frame),
 * plus the overlay's own ephemeral state (visibility, scrubbing) and a few transport conveniences.
 *
 * Visibility model:
 * - **Auto-hide** runs only while actively playing (not paused/ended), is suspended while a *modal*
 *   thing is active ([keepVisible] — an open menu or an active scrub), and re-arms on every
 *   interaction ([notifyInteraction], bumped by the conveniences below and by the UI on focus/key).
 * - **Explicit** [hideControls]/[toggleControls] always win — focus does NOT veto an explicit hide
 *   (that was the old TV deadlock). On TV the overlay simply re-appears on the next key press.
 *
 * Create with [rememberPlayerControlsState]; hoist and pass to building blocks for the "controls
 * beside the video" pattern.
 */
@ExperimentalPlayerUiApi
@Stable
public class PlayerControlsState internal constructor(
    /**
     * The player this overlay drives. Exposed so custom controls can reach transport calls that have no
     * convenience here (`seekTo`, `setVolume`, track selection). Calls made straight on the player do
     * **not** re-arm auto-hide — pair them with [notifyInteraction].
     */
    public val player: VideoPlayer,
    internal val autoHideMillis: Long,
    private val ads: AdController? = null,
) {
    internal var snapshot by mutableStateOf(player.state.value)
    internal var adSnapshot: AdState? by mutableStateOf(ads?.state?.value)

    /**
     * Whether the overlay should currently be drawn. Starts `true` so controls are visible on first
     * composition, and is driven by auto-hide plus [showControls]/[hideControls]/[toggleControls];
     * it is *not* a request — read it and render (or animate) accordingly.
     */
    public var controlsVisible: Boolean by mutableStateOf(true)
        private set

    /** Bumped on any interaction; auto-hide re-arms when it changes. */
    internal var interactionTick: Int by mutableIntStateOf(0)
        private set

    private var scrubPosition by mutableStateOf<Long?>(null)
    private val keepVisibleTokens = mutableStateListOf<Any>()

    /** True while a *modal* control insists auto-hide pause (open menu / active scrub). Not focus. */
    public val keepVisible: Boolean get() = keepVisibleTokens.isNotEmpty()

    // ---- snapshot-derived, UI-ready ----
    /** Engine lifecycle phase of the current item, straight off the latest snapshot. */
    public val playbackState: PlaybackState get() = snapshot.playbackState

    /**
     * `true` while the engine is rebuffering *or* doing its initial buffering — it says nothing about
     * intent. For "spinner because the user asked to play" use [isWaitingToPlay], which won't light up
     * when buffering happens to occur while paused.
     */
    public val isBuffering: Boolean get() = snapshot.playbackState == PlaybackState.BUFFERING

    /** `true` once the item has played to its end; the transport button then acts as replay. */
    public val isEnded: Boolean get() = snapshot.playbackState == PlaybackState.ENDED

    /**
     * `true` only while frames are actually advancing. Goes `false` during a rebuffer even though the
     * user still intends to play — bind the play/pause glyph to [showPlayIcon], not to this.
     */
    public val isPlaying: Boolean get() = snapshot.isPlaying

    /** Show the play glyph when paused **or ended** (so end-of-content offers replay), not on rebuffer. */
    public val showPlayIcon: Boolean get() = !snapshot.playWhenReady || isEnded

    /** Buffering *while the user wants to play* — the condition a loading spinner should bind to. */
    public val isWaitingToPlay: Boolean get() = isBuffering && snapshot.playWhenReady

    /**
     * Sampled content position in ms (never ad time). While the user drags, this keeps reporting the
     * engine's position — the scrubber thumb should follow [scrubPositionMs] instead.
     */
    public val positionMs: Long get() = snapshot.positionMs

    /**
     * Total content duration in ms, or `null` when not yet resolved (and for many live streams).
     * `null` is **not** a live signal — check [isLive].
     */
    public val durationMs: Long? get() = snapshot.durationMs

    /** How far ahead of the start the engine has buffered, in ms — the scrubber's secondary track. */
    public val bufferedPositionMs: Long get() = snapshot.bufferedPositionMs

    /** `true` for a livestream. Controls should prefer a live badge over a duration/remaining readout. */
    public val isLive: Boolean get() = snapshot.isLive

    /**
     * Whether seeking is currently permitted (a live edge-only stream or an unresolved timeline is not).
     * Disable the scrubber and skip buttons when this is `false` rather than letting seeks fail silently.
     */
    public val isSeekable: Boolean get() = snapshot.isSeekable

    /** Mute switch state, independent of [volume] — unmuting restores the previous level. */
    public val isMuted: Boolean get() = snapshot.isMuted

    /** Linear output volume in `0f`..`1f`. Unaffected by [isMuted]; a muted player keeps its level. */
    public val volume: Float get() = snapshot.volume

    /** Playback rate multiplier, `1f` for normal speed. */
    public val playbackSpeed: Float get() = snapshot.playbackSpeed

    /** The most recent fatal or surfaced playback error, or `null` when there is none. */
    public val error: PlayerError? get() = snapshot.error

    /** Selectable audio renditions; empty until the manifest/container has been parsed. */
    public val audioTracks: List<MediaTrack> get() = snapshot.audioTracks

    /** Selectable subtitle/caption tracks; empty until parsed, and empty for assets that carry none. */
    public val textTracks: List<MediaTrack> get() = snapshot.textTracks

    /** Chapter markers for the current media, sorted by start; empty when the asset carries none. */
    public val chapters: List<Chapter> get() = snapshot.chapters

    /** Trickplay seek-preview thumbnails, sorted by start; empty when the asset carries none. */
    public val thumbnails: List<ThumbnailCue> get() = snapshot.thumbnails

    /** Colour/dynamic range of the current video rendition; [VideoRange.SDR] until an HDR track is selected. */
    public val videoRange: VideoRange get() = snapshot.videoRange

    /** Id of the active audio track, or `null` when nothing has been resolved yet. */
    public val selectedAudioTrackId: String? get() = snapshot.selectedAudioTrackId

    /** Id of the active subtitle track. `null` means subtitles are **off**, not "unknown". */
    public val selectedTextTrackId: String? get() = snapshot.selectedTextTrackId

    /** Alternate video renditions of the same content; empty unless the manifest offers a real choice. */
    public val videoTracks: List<MediaTrack> get() = snapshot.videoTracks

    /** [MediaTrack.id] of the active video rendition, or `null` before the engine reports one. */
    public val selectedVideoTrackId: String? get() = snapshot.selectedVideoTrackId

    /** What the scrubber should show: local drag, else an in-flight seek, else the sampled position. */
    public val scrubPositionMs: Long get() = scrubPosition ?: snapshot.pendingSeekTargetMs ?: snapshot.positionMs

    /**
     * `true` only between [onScrubMove] and [onScrubCommit]/[cancelScrub], i.e. while the *user* is
     * dragging. An in-flight seek the app started programmatically does not count.
     */
    public val isScrubbing: Boolean get() = scrubPosition != null

    // ---- visibility ----
    // ---- server-side ads ----
    /**
     * The state of the [AdController] this overlay was created with, or `null` without one. Client-side
     * ads never appear here: IMA draws its own ad UI over the video.
     */
    public val adState: AdState? get() = adSnapshot

    /** `true` while the playhead is inside an unwatched server-side ad break. Seeking is locked meanwhile. */
    public val isInAdBreak: Boolean get() = adSnapshot?.isInAdBreak == true

    /** Where the server-side ad breaks start, in ms, for marking on a timeline. Empty without ads. */
    public val adCuePoints: List<Long> get() = adSnapshot?.cuePoints.orEmpty()

    /**
     * Skip the current server-side ad break and seek past it. Does nothing until [AdState.canSkip], so a
     * button can call it unconditionally.
     */
    public fun skipAd() {
        if (adSnapshot?.canSkip != true) return
        ads?.skipCurrentBreak()?.let(player::seekTo)
        notifyInteraction()
    }

    /** Mark that the user interacted; re-arms the auto-hide countdown without changing visibility. */
    public fun notifyInteraction() { interactionTick++ }

    /** Show the overlay and re-arm the auto-hide countdown. Safe to call when already visible. */
    public fun showControls() {
        controlsVisible = true
        notifyInteraction()
    }

    /** Explicit hide — always succeeds (auto-hide pausing via [keepVisible] does not block this). */
    public fun hideControls() { controlsVisible = false }

    /**
     * Flip visibility — the natural binding for a tap on the video surface. Hiding this way is an
     * *explicit* hide, so it wins even while a menu holds [keepVisible].
     */
    public fun toggleControls() { if (controlsVisible) hideControls() else showControls() }

    /** Latch auto-hide *paused* for [token]'s lifetime (ref-counted by distinct tokens). */
    public fun keepVisible(token: Any) {
        if (token !in keepVisibleTokens) keepVisibleTokens.add(token)
        controlsVisible = true
        notifyInteraction()
    }

    /**
     * Drop [token]'s hold; auto-hide resumes once **every** token has been released. Must be paired with
     * each [keepVisible] call — a leaked token pins the overlay open for good, so release it from the
     * same `DisposableEffect`/dismissal path that took it.
     */
    public fun releaseVisible(token: Any) {
        keepVisibleTokens.remove(token)
        notifyInteraction()
    }

    // ---- transport conveniences (each counts as an interaction) ----
    /**
     * Toggle play *intent*: pauses when the player is trying to play, otherwise plays. Keyed off intent,
     * not [isPlaying], so tapping during a rebuffer pauses rather than re-issuing play.
     */
    public fun playPause() {
        if (snapshot.playWhenReady) player.pause() else player.play()
        notifyInteraction()
    }

    /** Flip the mute switch. [volume] is left untouched, so unmuting returns to the previous level. */
    public fun toggleMute() {
        player.setMuted(!snapshot.isMuted)
        notifyInteraction()
    }

    /**
     * Report the drag position in ms while the user scrubs. Does **not** seek — it only moves
     * [scrubPositionMs] and pins the overlay open until [onScrubCommit] or [cancelScrub] runs.
     *
     * @param ms the dragged-to content position in milliseconds.
     */
    public fun onScrubMove(ms: Long) {
        keepVisible(ScrubToken)
        scrubPosition = ms
    }

    /**
     * Finish a scrub: seek to the last [onScrubMove] position and release the auto-hide hold. A no-op
     * seek-wise if no drag was in progress, so it is safe to call from an unconditional gesture-end path.
     * With server-side ads the seek goes through [AdController.resolveSeek], so it stops at the start of
     * an unwatched break instead of jumping it.
     */
    public fun onScrubCommit() {
        scrubPosition?.let { target -> player.seekTo(ads?.resolveSeek(snapshot.positionMs, target) ?: target) }
        scrubPosition = null
        releaseVisible(ScrubToken)
    }

    /** Abandon an in-flight scrub (e.g. the scrubber left composition) without seeking. */
    public fun cancelScrub() {
        scrubPosition = null
        releaseVisible(ScrubToken)
    }

    private companion object {
        private val ScrubToken = Any()
    }
}

/** Remember a [PlayerControlsState] for [player], collecting its state and running auto-hide. */
@ExperimentalPlayerUiApi
@Composable
public fun rememberPlayerControlsState(
    player: VideoPlayer,
    autoHide: Duration = 3.seconds,
): PlayerControlsState = rememberControlsState(player, ads = null, autoHide)

/**
 * [rememberPlayerControlsState] for a stream with server-side ads. The overlay reads [ads] for the ad
 * countdown, the skip button and the cue-points on the scrubber, locks the scrubber during a break, and
 * routes every scrub through [AdController.resolveSeek]. Driving [ads] with the playhead stays the app's
 * job: this only reads it.
 */
@ExperimentalPlayerApi
@ExperimentalPlayerUiApi
@Composable
public fun rememberPlayerControlsState(
    player: VideoPlayer,
    ads: AdController,
    autoHide: Duration = 3.seconds,
): PlayerControlsState = rememberControlsState(player, ads, autoHide)

@Composable
private fun rememberControlsState(player: VideoPlayer, ads: AdController?, autoHide: Duration): PlayerControlsState {
    val state = remember(player, ads) { PlayerControlsState(player, autoHide.inWholeMilliseconds, ads) }
    LaunchedEffect(state) {
        player.state.collect { state.snapshot = it }
    }
    if (ads != null) {
        LaunchedEffect(state) {
            ads.state.collect { state.adSnapshot = it }
        }
    }
    // Auto-hide while actively playing; never while paused/ended or while a menu/scrub is active.
    // Re-arms on every interaction (interactionTick) and on relevant state changes.
    LaunchedEffect(state.controlsVisible, state.keepVisible, state.showPlayIcon, state.interactionTick) {
        if (state.controlsVisible && !state.keepVisible && !state.showPlayIcon) {
            delay(state.autoHideMillis.milliseconds)
            state.hideControls()
        }
    }
    return state
}
