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
import dev.nonbinary.outis.core.PlaybackState
import dev.nonbinary.outis.core.PlayerError
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.track.MediaTrack
import kotlinx.collections.immutable.ImmutableList
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
@Stable
class PlayerControlsState internal constructor(
    /**
     * The player this overlay drives. Exposed so custom controls can reach transport calls that have no
     * convenience here (`seekTo`, `setVolume`, track selection). Calls made straight on the player do
     * **not** re-arm auto-hide — pair them with [notifyInteraction].
     */
    val player: VideoPlayer,
    internal val autoHideMillis: Long,
) {
    internal var snapshot by mutableStateOf(player.state.value)

    /**
     * Whether the overlay should currently be drawn. Starts `true` so controls are visible on first
     * composition, and is driven by auto-hide plus [showControls]/[hideControls]/[toggleControls];
     * it is *not* a request — read it and render (or animate) accordingly.
     */
    var controlsVisible by mutableStateOf(true)
        private set

    /** Bumped on any interaction; auto-hide re-arms when it changes. */
    var interactionTick by mutableIntStateOf(0)
        private set

    private var scrubPosition by mutableStateOf<Long?>(null)
    private val keepVisibleTokens = mutableStateListOf<Any>()

    /** True while a *modal* control insists auto-hide pause (open menu / active scrub). Not focus. */
    val keepVisible: Boolean get() = keepVisibleTokens.isNotEmpty()

    // ---- snapshot-derived, UI-ready ----
    /** Engine lifecycle phase of the current item, straight off the latest snapshot. */
    val playbackState: PlaybackState get() = snapshot.playbackState

    /**
     * `true` while the engine is rebuffering *or* doing its initial buffering — it says nothing about
     * intent. For "spinner because the user asked to play" use [isWaitingToPlay], which won't light up
     * when buffering happens to occur while paused.
     */
    val isBuffering: Boolean get() = snapshot.playbackState == PlaybackState.BUFFERING

    /** `true` once the item has played to its end; the transport button then acts as replay. */
    val isEnded: Boolean get() = snapshot.playbackState == PlaybackState.ENDED

    /**
     * `true` only while frames are actually advancing. Goes `false` during a rebuffer even though the
     * user still intends to play — bind the play/pause glyph to [showPlayIcon], not to this.
     */
    val isPlaying: Boolean get() = snapshot.isPlaying

    /** Show the play glyph when paused **or ended** (so end-of-content offers replay), not on rebuffer. */
    val showPlayIcon: Boolean get() = !snapshot.playWhenReady || isEnded

    /** Buffering *while the user wants to play* — the condition a loading spinner should bind to. */
    val isWaitingToPlay: Boolean get() = isBuffering && snapshot.playWhenReady

    /**
     * Sampled content position in ms (never ad time). While the user drags, this keeps reporting the
     * engine's position — the scrubber thumb should follow [scrubPositionMs] instead.
     */
    val positionMs: Long get() = snapshot.positionMs

    /**
     * Total content duration in ms, or `null` when not yet resolved (and for many live streams).
     * `null` is **not** a live signal — check [isLive].
     */
    val durationMs: Long? get() = snapshot.durationMs

    /** How far ahead of the start the engine has buffered, in ms — the scrubber's secondary track. */
    val bufferedPositionMs: Long get() = snapshot.bufferedPositionMs

    /** `true` for a livestream. Controls should prefer a live badge over a duration/remaining readout. */
    val isLive: Boolean get() = snapshot.isLive

    /**
     * Whether seeking is currently permitted (a live edge-only stream or an unresolved timeline is not).
     * Disable the scrubber and skip buttons when this is `false` rather than letting seeks fail silently.
     */
    val isSeekable: Boolean get() = snapshot.isSeekable

    /** Mute switch state, independent of [volume] — unmuting restores the previous level. */
    val isMuted: Boolean get() = snapshot.isMuted

    /** Linear output volume in `0f`..`1f`. Unaffected by [isMuted]; a muted player keeps its level. */
    val volume: Float get() = snapshot.volume

    /** Playback rate multiplier, `1f` for normal speed. */
    val playbackSpeed: Float get() = snapshot.playbackSpeed

    /** The most recent fatal or surfaced playback error, or `null` when there is none. */
    val error: PlayerError? get() = snapshot.error

    /** Selectable audio renditions; empty until the manifest/container has been parsed. */
    val audioTracks: ImmutableList<MediaTrack> get() = snapshot.audioTracks

    /** Selectable subtitle/caption tracks; empty until parsed, and empty for assets that carry none. */
    val textTracks: ImmutableList<MediaTrack> get() = snapshot.textTracks

    /** Id of the active audio track, or `null` when nothing has been resolved yet. */
    val selectedAudioTrackId: String? get() = snapshot.selectedAudioTrackId

    /** Id of the active subtitle track. `null` means subtitles are **off**, not "unknown". */
    val selectedTextTrackId: String? get() = snapshot.selectedTextTrackId

    /** What the scrubber should show: local drag, else an in-flight seek, else the sampled position. */
    val scrubPositionMs: Long get() = scrubPosition ?: snapshot.pendingSeekTargetMs ?: snapshot.positionMs

    /**
     * `true` only between [onScrubMove] and [onScrubCommit]/[cancelScrub], i.e. while the *user* is
     * dragging. An in-flight seek the app started programmatically does not count.
     */
    val isScrubbing: Boolean get() = scrubPosition != null

    // ---- visibility ----
    /** Mark that the user interacted; re-arms the auto-hide countdown without changing visibility. */
    fun notifyInteraction() { interactionTick++ }

    /** Show the overlay and re-arm the auto-hide countdown. Safe to call when already visible. */
    fun showControls() {
        controlsVisible = true
        notifyInteraction()
    }

    /** Explicit hide — always succeeds (auto-hide pausing via [keepVisible] does not block this). */
    fun hideControls() { controlsVisible = false }

    /**
     * Flip visibility — the natural binding for a tap on the video surface. Hiding this way is an
     * *explicit* hide, so it wins even while a menu holds [keepVisible].
     */
    fun toggleControls() { if (controlsVisible) hideControls() else showControls() }

    /** Latch auto-hide *paused* for [token]'s lifetime (ref-counted by distinct tokens). */
    fun keepVisible(token: Any) {
        if (token !in keepVisibleTokens) keepVisibleTokens.add(token)
        controlsVisible = true
        notifyInteraction()
    }

    /**
     * Drop [token]'s hold; auto-hide resumes once **every** token has been released. Must be paired with
     * each [keepVisible] call — a leaked token pins the overlay open for good, so release it from the
     * same `DisposableEffect`/dismissal path that took it.
     */
    fun releaseVisible(token: Any) {
        keepVisibleTokens.remove(token)
        notifyInteraction()
    }

    // ---- transport conveniences (each counts as an interaction) ----
    /**
     * Toggle play *intent*: pauses when the player is trying to play, otherwise plays. Keyed off intent,
     * not [isPlaying], so tapping during a rebuffer pauses rather than re-issuing play.
     */
    fun playPause() {
        if (snapshot.playWhenReady) player.pause() else player.play()
        notifyInteraction()
    }

    /** Flip the mute switch. [volume] is left untouched, so unmuting returns to the previous level. */
    fun toggleMute() {
        player.setMuted(!snapshot.isMuted)
        notifyInteraction()
    }

    /**
     * Report the drag position in ms while the user scrubs. Does **not** seek — it only moves
     * [scrubPositionMs] and pins the overlay open until [onScrubCommit] or [cancelScrub] runs.
     *
     * @param ms the dragged-to content position in milliseconds.
     */
    fun onScrubMove(ms: Long) {
        keepVisible(ScrubToken)
        scrubPosition = ms
    }

    /**
     * Finish a scrub: seek to the last [onScrubMove] position and release the auto-hide hold. A no-op
     * seek-wise if no drag was in progress, so it is safe to call from an unconditional gesture-end path.
     */
    fun onScrubCommit() {
        scrubPosition?.let { player.seekTo(it) }
        scrubPosition = null
        releaseVisible(ScrubToken)
    }

    /** Abandon an in-flight scrub (e.g. the scrubber left composition) without seeking. */
    fun cancelScrub() {
        scrubPosition = null
        releaseVisible(ScrubToken)
    }

    private companion object {
        private val ScrubToken = Any()
    }
}

/** Remember a [PlayerControlsState] for [player], collecting its state and running auto-hide. */
@Composable
fun rememberPlayerControlsState(
    player: VideoPlayer,
    autoHide: Duration = 3.seconds,
): PlayerControlsState {
    val state = remember(player) { PlayerControlsState(player, autoHide.inWholeMilliseconds) }
    LaunchedEffect(player) {
        player.state.collect { state.snapshot = it }
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
