/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.formatTime

/** Default rates offered by [PlaybackSpeedButton], as multipliers of normal speed. */
private val DefaultPlaybackSpeeds = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

// Building blocks inherit their colour from LocalContentColor (Icon/Text defaults), so an app can
// retheme them by wrapping in CompositionLocalProvider(LocalContentColor provides ...). The default
// overlay provides white; reused on a light surface, they pick up the app's content colour.

/**
 * Icon button that toggles between play and pause via [PlayerControlsState.playPause].
 *
 * **Always enabled**, even while the player is idle, so it stays reachable with a D-pad on TV; the
 * click simply does nothing when there is no media. The icon and its content description follow
 * [PlayerControlsState.showPlayIcon], so an ended stream shows "Play" rather than "Pause".
 */
@ExperimentalPlayerUiApi
@Composable
fun PlayPauseButton(state: PlayerControlsState, modifier: Modifier = Modifier) {
    // Always enabled (so it stays D-pad-focusable even in IDLE); the click no-ops when there's nothing to do.
    IconButton(onClick = state::playPause, modifier = modifier.controlFocusRing()) {
        Icon(
            imageVector = if (state.showPlayIcon) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = if (state.showPlayIcon) "Play" else "Pause",
        )
    }
}

/**
 * Oversized centre-screen variant of [PlayPauseButton] — a 72.dp touch target around a 48.dp icon,
 * intended for the middle of the overlay. Behaviour is identical to [PlayPauseButton]; any size
 * applied through [modifier] is **overridden** by the built-in 72.dp size.
 */
@ExperimentalPlayerUiApi
@Composable
fun BigPlayButton(state: PlayerControlsState, modifier: Modifier = Modifier) {
    IconButton(onClick = state::playPause, modifier = modifier.size(72.dp).controlFocusRing()) {
        Icon(
            imageVector = if (state.showPlayIcon) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = if (state.showPlayIcon) "Play" else "Pause",
            modifier = Modifier.size(48.dp),
        )
    }
}

/**
 * Seek bar over the current timeline, in milliseconds from `0` to [PlayerControlsState.durationMs].
 *
 * **Renders nothing at all** when there is no scrubbable timeline — a live stream, or a duration that
 * is `null` or non-positive because it has not resolved yet. Lay out around it accordingly: it
 * occupies no space in those cases. On a seekable-but-unresolved asset it will appear later, once the
 * duration arrives.
 *
 * Dragging only moves the preview position; the seek is issued on release. If the scrubber leaves
 * composition mid-drag the scrub is abandoned without seeking, so the auto-hide latch is not stuck on.
 * The slider is disabled (but still drawn) while [PlayerControlsState.isSeekable] is `false`.
 */
@ExperimentalPlayerUiApi
@Composable
fun Scrubber(state: PlayerControlsState, modifier: Modifier = Modifier) {
    val duration = state.durationMs
    // No scrubbable timeline yet (live edge or unresolved duration).
    if (state.isLive || duration == null || duration <= 0L) return
    // Self-heal: if the scrubber leaves composition mid-drag (Slider.onValueChangeFinished may not
    // fire on cancel), abandon the scrub so the keep-visible latch / drag position don't stick.
    DisposableEffect(Unit) {
        onDispose { if (state.isScrubbing) state.cancelScrub() }
    }
    Slider(
        value = state.scrubPositionMs.coerceIn(0L, duration).toFloat(),
        onValueChange = { state.onScrubMove(it.toLong()) },
        onValueChangeFinished = state::onScrubCommit,
        valueRange = 0f..duration.toFloat(),
        enabled = state.isSeekable,
        modifier = modifier.semantics {
            contentDescription = "Seek bar"
            stateDescription = "${formatTime(state.scrubPositionMs)} of ${formatTime(duration)}"
        },
    )
}

/**
 * Elapsed-time readout, formatted `mm:ss` or `h:mm:ss` once the hour mark is passed.
 *
 * Shows the literal text `LIVE` for a live stream — [showDuration] is then irrelevant. Unknown or
 * negative times render as `--:--` rather than `00:00`, so an unresolved duration is visibly distinct
 * from the start of the stream. While the user drags the [Scrubber] this tracks the drag position,
 * not the actual playhead.
 *
 * @param showDuration `true` to render `position / duration`; `false` for the position alone.
 */
@ExperimentalPlayerUiApi
@Composable
fun TimeLabel(state: PlayerControlsState, modifier: Modifier = Modifier, showDuration: Boolean = true) {
    val text = when {
        state.isLive -> "LIVE"
        showDuration -> "${formatTime(state.scrubPositionMs)} / ${formatTime(state.durationMs)}"
        else -> formatTime(state.scrubPositionMs)
    }
    Text(text = text, style = MaterialTheme.typography.labelMedium, modifier = modifier)
}

/**
 * Indeterminate spinner shown only while playback is stalled *and* the user wants it to play
 * ([PlayerControlsState.isWaitingToPlay]) — buffering while deliberately paused draws **nothing**,
 * so a paused player does not look broken.
 *
 * Emits no layout at all when hidden, and inherits its colour from `LocalContentColor`. Announced as
 * a polite live region for screen readers.
 */
@ExperimentalPlayerUiApi
@Composable
fun BufferingIndicator(state: PlayerControlsState, modifier: Modifier = Modifier) {
    if (state.isWaitingToPlay) {
        CircularProgressIndicator(
            color = LocalContentColor.current,
            modifier = modifier.semantics {
                contentDescription = "Buffering"
                liveRegion = LiveRegionMode.Polite
            },
        )
    }
}

/**
 * Icon button toggling the mute flag via [PlayerControlsState.toggleMute]. Mute is a flag separate
 * from the volume level, so toggling it does not change the volume the player was set to.
 */
@ExperimentalPlayerUiApi
@Composable
fun MuteButton(state: PlayerControlsState, modifier: Modifier = Modifier) {
    IconButton(onClick = state::toggleMute, modifier = modifier.controlFocusRing()) {
        Icon(
            imageVector = if (state.isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
            contentDescription = if (state.isMuted) "Unmute" else "Mute",
        )
    }
}

/**
 * Icon button opening a drop-down of playback rates, ticking the one currently in effect.
 *
 * While the menu is open the overlay's auto-hide is **latched off**, and the latch is released both
 * when the menu closes and if the button leaves composition — so a dismissed menu can never leave the
 * controls pinned on screen.
 *
 * @param speeds rates offered, as multipliers of normal speed (`1f` is normal). Order is preserved
 *   in the menu. A rate the player does not support is passed through regardless — the engine
 *   decides what to do with it.
 */
@ExperimentalPlayerUiApi
@Composable
fun PlaybackSpeedButton(
    state: PlayerControlsState,
    modifier: Modifier = Modifier,
    speeds: List<Float> = DefaultPlaybackSpeeds,
) {
    var open by remember { mutableStateOf(false) }
    val token = remember { Any() }
    // Keep the latch tied to the menu's actual state, and always release it if we leave composition.
    LaunchedEffect(open) { if (open) state.keepVisible(token) else state.releaseVisible(token) }
    DisposableEffect(Unit) { onDispose { state.releaseVisible(token) } }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.controlFocusRing()) {
            Icon(Icons.Filled.Speed, contentDescription = "Playback speed")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            speeds.forEach { speed ->
                DropdownMenuItem(
                    text = { Text("${speed}x") },
                    onClick = {
                        state.player.setPlaybackSpeed(speed)
                        state.notifyInteraction()
                        open = false
                    },
                    trailingIcon = {
                        if (state.playbackSpeed == speed) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null
                            )
                        }
                    },
                )
            }
        }
    }
}

// --- Zero-argument scope-member variants (called inside the `controls` lambda) ---

/** [PlayPauseButton] against the scope's own [PlayerControlsScope.state]. Behaviour is identical. */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.PlayPauseButton(modifier: Modifier = Modifier) {
    PlayPauseButton(state, modifier)
}

/** [BigPlayButton] against the scope's own [PlayerControlsScope.state]. Behaviour is identical. */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.BigPlayButton(modifier: Modifier = Modifier) {
    BigPlayButton(state, modifier)
}

/**
 * [Scrubber] against the scope's own [PlayerControlsScope.state]. Behaviour is identical — including
 * emitting nothing for live or unresolved-duration media.
 */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.Scrubber(modifier: Modifier = Modifier) {
    Scrubber(state, modifier)
}

/** [TimeLabel] against the scope's own [PlayerControlsScope.state]. Behaviour is identical. */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.TimeLabel(modifier: Modifier = Modifier, showDuration: Boolean = true) {
    TimeLabel(state, modifier, showDuration)
}

/**
 * [BufferingIndicator] against the scope's own [PlayerControlsScope.state]. Behaviour is identical —
 * including drawing nothing while merely paused.
 */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.BufferingIndicator(modifier: Modifier = Modifier) {
    BufferingIndicator(state, modifier)
}

/** [MuteButton] against the scope's own [PlayerControlsScope.state]. Behaviour is identical. */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.MuteButton(modifier: Modifier = Modifier) {
    MuteButton(state, modifier)
}

/**
 * [PlaybackSpeedButton] against the scope's own [PlayerControlsScope.state], with the default rate
 * list. Pass the free function a `speeds` list to offer different rates.
 */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.PlaybackSpeedButton(modifier: Modifier = Modifier) {
    PlaybackSpeedButton(state, modifier)
}
