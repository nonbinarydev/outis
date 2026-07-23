/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.PlaybackState
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.PlayerView

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

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 960.dp)
                        .fillMaxWidth()
                        .aspectRatio(ASPECT_16_9)
                        .background(Color.Black),
                ) {
                    PlayerView(player, modifier = Modifier.fillMaxSize())
                }

                Text(
                    text = statusLine(state.playbackState, state.error?.category?.name),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
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
