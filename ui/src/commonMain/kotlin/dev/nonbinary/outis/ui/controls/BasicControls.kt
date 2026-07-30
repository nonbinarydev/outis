/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import dev.nonbinary.outis.core.thumbnails.ThumbnailCue
import dev.nonbinary.outis.core.thumbnails.thumbnailAt
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.formatTime
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.roundToInt

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
@OptIn(ExperimentalMaterial3Api::class)
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
    val colors = MaterialTheme.colorScheme
    val played = (state.scrubPositionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (state.bufferedPositionMs.toFloat() / duration).coerceIn(0f, 1f)
    // Track, buffered layer, played layer and the dot are all drawn in one canvas, so they share a
    // coordinate space and can't drift apart vertically. The Slider on top is invisible — it carries
    // only the drag, keyboard and accessibility behaviour.
    BoxWithConstraints(modifier.height(ScrubberTouchHeight)) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val r = ScrubberThumbSize.toPx() / 2f
            val startX = r
            val span = (size.width - r * 2f).coerceAtLeast(0f)
            val stroke = ScrubberTrackHeight.toPx()
            fun line(fraction: Float, color: Color) = drawLine(
                color = color,
                start = Offset(startX, cy),
                end = Offset(startX + span * fraction, cy),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            line(1f, colors.onSurface.copy(alpha = 0.2f)) // base
            if (buffered > 0f) line(buffered, colors.onSurface.copy(alpha = 0.45f)) // buffered-ahead
            if (played > 0f) line(played, colors.primary) // played
            // Chapter ticks — a dot at each chapter start; skip 0:00 (it sits under the track's left cap).
            val markerRadius = ScrubberTrackHeight.toPx()
            state.chapters.forEach { chapter ->
                if (chapter.startMs <= 0L) return@forEach
                val fraction = (chapter.startMs.toFloat() / duration).coerceIn(0f, 1f)
                drawCircle(colors.onSurface, radius = markerRadius, center = Offset(startX + span * fraction, cy))
            }
            drawCircle(colors.primary, radius = r, center = Offset(startX + span * played, cy))
        }
        Slider(
            value = state.scrubPositionMs.coerceIn(0L, duration).toFloat(),
            onValueChange = { state.onScrubMove(it.toLong()) },
            onValueChangeFinished = state::onScrubCommit,
            valueRange = 0f..duration.toFloat(),
            enabled = state.isSeekable,
            // Invisible: the canvas above is the visual. The ScrubberThumbSize thumb keeps the value→x
            // mapping matched to the drawn dot; the full-height track box makes the whole ScrubberTouchHeight
            // band draggable, so the thin visual line isn't a pixel-precise target (was hard to grab).
            thumb = { Box(Modifier.size(ScrubberThumbSize)) },
            track = { Box(Modifier.fillMaxWidth().height(ScrubberTouchHeight)) },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Seek bar"
                stateDescription = "${formatTime(state.scrubPositionMs)} of ${formatTime(duration)}"
            },
        )
        // Trickplay preview: a cropped sprite tile floats above the thumb while scrubbing.
        val cue = state.thumbnails.thumbnailAt(state.scrubPositionMs)
        if (state.isScrubbing && cue != null) {
            ThumbnailPreview(cue, state.thumbnails, fraction = played, trackWidth = maxWidth)
        }
    }
}

/**
 * A single trickplay tile, cropped out of its sprite sheet and floated above the scrubber thumb.
 *
 * The crop is drawn by hand in a [Canvas]: the whole sheet is painted at a scale that makes the cue's
 * ([ThumbnailCue.width]×[ThumbnailCue.height]) tile fill the box, then [translate]d by the tile's origin so
 * only that tile shows through the box's clip. Everything is explicit — no [ContentScale]/alignment and no
 * reliance on the async painter's `intrinsicSize` (which is unspecified until the bitmap loads and then
 * platform-dependent; letting the paint layer choose the scale/anchor is what made the crop snap to the
 * sheet centre). [sheetSize] is the sheet's true *padded* pixel extent, taken across **all** cues so the
 * last sheet — only partially filled — is still drawn at full size. Coil caches the sheet, so scrubbing
 * across tiles of one sheet is a single download.
 */
@Composable
private fun ThumbnailPreview(
    cue: ThumbnailCue,
    all: ImmutableList<ThumbnailCue>,
    fraction: Float,
    trackWidth: Dp,
) {
    val density = LocalDensity.current
    val previewWidth = 160.dp
    val previewHeight = previewWidth * (cue.height.toFloat() / cue.width.toFloat())
    // The sheet's full pixel size. Every sheet in a set is padded to the same grid, so the max over ALL
    // cues gives the true image size; a partially-filled final sheet would under-report on its own cues.
    val sheetSize = remember(all) {
        IntSize(all.maxOf { it.x + it.width }, all.maxOf { it.y + it.height })
    }
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(LocalPlatformContext.current).data(cue.url).size(coil3.size.Size.ORIGINAL).build(),
    )
    val leftPx = with(density) {
        val w = previewWidth.toPx()
        (trackWidth.toPx() * fraction - w / 2f).coerceIn(0f, (trackWidth.toPx() - w).coerceAtLeast(0f))
    }
    val liftPx = with(density) { (previewHeight + 10.dp).toPx() }
    Box(
        Modifier
            .offset { IntOffset(leftPx.roundToInt(), -liftPx.roundToInt()) }
            // requiredSize, NOT size: the scrubber this floats over is only ScrubberThumbSize (12.dp) tall,
            // and size() coerces to the parent's constraints — which would flatten the preview to 12.dp.
            // requiredSize ignores them so the box keeps its true height and overflows the track upward.
            .requiredSize(previewWidth, previewHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black)
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (cue.width <= 0 || cue.height <= 0) return@Canvas
            val sx = size.width / cue.width // canvas-px per sheet-px so the tile fills the box
            val sy = size.height / cue.height
            clipRect {
                translate(left = -cue.x * sx, top = -cue.y * sy) {
                    with(painter) { draw(Size(sheetSize.width * sx, sheetSize.height * sy)) }
                }
            }
        }
    }
}

private val ScrubberThumbSize = 12.dp
private val ScrubberTrackHeight = 4.dp
// The whole scrubber row is this tall so the drag target is a comfortable ~40.dp band; the thin track and
// dot are drawn centred within it. 12.dp (the old height) made the seek bar fiddly to grab, esp. on Android.
private val ScrubberTouchHeight = 40.dp

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
            imageVector = if (state.isMuted) {
                Icons.AutoMirrored.Filled.VolumeOff
            } else {
                Icons.AutoMirrored.Filled.VolumeUp
            },
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
                                contentDescription = null,
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
