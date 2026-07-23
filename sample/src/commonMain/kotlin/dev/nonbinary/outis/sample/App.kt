/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.PlaybackState
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.sample.generated.resources.Res
import dev.nonbinary.outis.sample.generated.resources.outis_lockup
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.PlayerView
import dev.nonbinary.outis.ui.controls.DefaultControls
import org.jetbrains.compose.resources.painterResource

/**
 * Big Buck Bunny as an adaptive HLS ladder. Chosen because its master playlist is all `avc1` — no
 * in-band parameter sets — so every engine can play it: Media3, AVPlayer and Shaka alike. No DRM, so
 * there is no CDM to negotiate and nothing platform-specific to go wrong.
 *
 * That makes it the right stream for a smoke test: if this does not play, the problem is the
 * integration, not the content.
 */
private const val STREAM_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

/** Widescreen. The surface is letterboxed within it rather than cropped, matching ContentScale.Fit. */
private const val ASPECT_16_9 = 16f / 9f

/** Tall enough to read the descriptor under the wordmark, short enough not to crowd the player. */
private val LOCKUP_HEIGHT = 120.dp

/** Beyond this the player stops growing, so a wide desktop window doesn't get a wall of video. */
private val PLAYER_MAX_WIDTH = 960.dp

/** Breathing room between the window edge and anything drawn in it. */
private val EDGE_PADDING = 16.dp

/** Gap between the lockup's baseline and the top of the player. */
private val LOCKUP_GAP = 24.dp

/** Gap between the bottom of the player and the top of the status line. */
private val STATUS_GAP = 16.dp

/**
 * The whole sample: construct a player, load one item, render it.
 *
 * Deliberately minimal. There is no catalogue, no navigation and no custom chrome — `PlayerView`'s
 * own `DefaultControls` are what a consumer gets out of the box, and showing them unmodified is the
 * point.
 */
@OptIn(ExperimentalPlayerUiApi::class)
@Composable
fun App(appContext: AppContext, modifier: Modifier = Modifier) {
    // Constructed once and tied to this composition. Engines are main-thread-affine, which is where a
    // composable body runs, and `release()` is idempotent so the DisposableEffect is safe.
    val player = remember { VideoPlayer(appContext) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player) {
        player.setMediaItem(
            MediaItem(
                MediaSource.Url(STREAM_URL),
                mimeType = MimeType.HLS,
                // Browsers block unmuted autoplay from a non-interactive context, and a visitor who
                // has never interacted with this origin is exactly that. Without this the demo shows a
                // black rectangle that never starts. The controls carry a mute toggle to turn it up.
                startMuted = true,
            ),
            autoPlay = true,
        )
    }

    val state by player.state.collectAsState()

    // Hoisted, because the layout has to react to it. `PlayerWindow.isFullscreen` is purely the host's
    // report — the SDK only picks the expand/collapse icon from it and never changes layout itself, so
    // filling the screen is this application's job. Requesting browser fullscreen without also doing
    // this leaves the page filling the display and the player still a small box in the middle of it.
    val window = rememberSampleWindow(player)

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Picture-in-picture resizes the *host's own window* down to the PiP tile — it does not
                // hand the player a separate surface. So the same rule applies as for fullscreen: fill
                // what we are given. Laying out the windowed arrangement inside a PiP tile puts a
                // centred 16:9 player inside a 16:9 tile with the margins still applied, which is what
                // a black border all the way around actually is.
                val fullscreen = window.isFullscreen || window.isInPip
                val playerSize = if (fullscreen) {
                    DpSize(maxWidth, maxHeight)
                } else {
                    windowedPlayerSize(maxWidth - EDGE_PADDING * 2, maxHeight - EDGE_PADDING * 2)
                }
                val halfPlayerHeight = playerSize.height / 2

                // ONE PlayerView, sized differently — not one per branch. Two call sites either side of
                // an `if` are two different composables to Compose, so toggling fullscreen would dispose
                // one and create the other. On web that is not cosmetic: PlayerSurface removes the
                // <video> from the DOM on dispose, which drops it out of picture-in-picture and
                // restarts buffering. Keeping it first among the Box's children also keeps its slot
                // stable as the sibling content below appears and disappears.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(playerSize)
                        .background(Color.Black),
                ) {
                    PlayerView(
                        player,
                        modifier = Modifier.fillMaxSize(),
                        // Supplying this is what makes the fullscreen and PiP buttons appear at all.
                        window = window,
                        // No chrome in the PiP tile: it is too small to hit, the system draws its own
                        // controls over it, and touches go to the system rather than to us anyway.
                        controls = { if (!window.isInPip) DefaultControls() },
                    )
                }

                if (!fullscreen) {
                    // Both are anchored to TopCenter and pushed down with padding, rather than laid out
                    // in a Column, because a Column centres the *whole stack* — which is what put the
                    // player below the middle of the window by half the height of the logo and status.
                    // Anchoring from the top also lets the status line grow downwards at a large font
                    // scale instead of being clipped to a fixed band.
                    val centreY = maxHeight / 2
                    val spaceAbovePlayer = centreY - halfPlayerHeight

                    if (spaceAbovePlayer >= LOCKUP_HEIGHT + LOCKUP_GAP + EDGE_PADDING) {
                        // The dark-ground lockup: the sample runs a dark colour scheme, and this variant
                        // is the one drawn with light ink on a transparent background. Dropped entirely
                        // rather than shrunk when the window is too short — an illegible 30dp lockup is
                        // worse than none, and the player must not move to make room for it.
                        //
                        // The guard above is also what keeps this padding non-negative (which would
                        // throw): it is exactly spaceAbovePlayer - LOCKUP_GAP - LOCKUP_HEIGHT, so the
                        // condition leaves at least EDGE_PADDING.
                        Image(
                            painter = painterResource(Res.drawable.outis_lockup),
                            contentDescription = "Outis — a Kotlin Multiplatform video player",
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = spaceAbovePlayer - LOCKUP_GAP - LOCKUP_HEIGHT)
                                .height(LOCKUP_HEIGHT),
                        )
                    }

                    Text(
                        text = statusLine(state.playbackState, state.error?.category?.name),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(
                                top = centreY + halfPlayerHeight + STATUS_GAP,
                                start = EDGE_PADDING,
                                end = EDGE_PADDING,
                            ),
                    )
                }
            }
        }
    }
}

/**
 * The player's size in the windowed layout: as wide as it is allowed to get, then 16:9, then shrunk to
 * fit if that would be taller than the window.
 *
 * The height clamp is what stops a tall-and-narrow window (a phone in portrait, a half-width browser)
 * producing a player taller than the space it has. `aspectRatio` alone does not do this — it satisfies
 * the ratio against the incoming width and lets the result overflow vertically.
 */
private fun windowedPlayerSize(availableWidth: Dp, availableHeight: Dp): DpSize {
    val width = minOf(availableWidth, PLAYER_MAX_WIDTH)
    val height = width / ASPECT_16_9
    return if (height <= availableHeight) {
        DpSize(width, height)
    } else {
        DpSize(availableHeight * ASPECT_16_9, availableHeight)
    }
}

/**
 * A one-line readout of what the player is doing. This is the actual point of the smoke test: it
 * distinguishes "nothing rendered" from "rendered but never became ready", which look identical on a
 * black surface.
 */
private fun statusLine(playbackState: PlaybackState, errorCategory: String?): String = when {
    errorCategory != null -> "Failed — $errorCategory. See the browser console or logcat for the engine's own code."
    playbackState == PlaybackState.IDLE -> "Idle — no media loaded yet."
    playbackState == PlaybackState.BUFFERING -> "Buffering…"
    playbackState == PlaybackState.READY -> "Playing Big Buck Bunny over HLS — muted, so autoplay is not blocked."
    playbackState == PlaybackState.ENDED -> "Ended."
    else -> playbackState.name
}
