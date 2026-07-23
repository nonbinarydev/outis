/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.nonbinary.outis.core.VideoPlayer

/**
 * The video rendering surface — the lowest-level public building block.
 *
 * It is deliberately public (not just an internal piece of [PlayerView]) so you can place video
 * anywhere in your own layout: an overlay shell, a side-by-side rail, a mini-player, etc. It draws
 * **only** the video (and, on platforms that support it, subtitle cues); controls are entirely
 * separate. Bind it to a [VideoPlayer]; it reacts to the player being (re)created internally.
 */
@ExperimentalPlayerUiApi
@Composable
expect fun PlayerSurface(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    surfaceType: SurfaceType = SurfaceType.SurfaceView,
    showSubtitles: Boolean = true,
)
