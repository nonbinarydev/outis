/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.runtime.Composable
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.ui.window.PlayerWindow

/**
 * The host's fullscreen and picture-in-picture hooks.
 *
 * `PlayerView` takes a [PlayerWindow] and defaults to one with every hook `null`, because going
 * fullscreen is an Activity, window-manager or browser concern that a playback SDK has no business
 * owning. The controls respond to that: `FullscreenButton` returns early when `onToggleFullscreen` is
 * `null`, so the button is simply absent rather than present-and-broken.
 *
 * Supplying a real implementation here is what makes the button appear, which is the point worth
 * showing — the seam is the API, not the button.
 */
@Composable
expect fun rememberSampleWindow(player: VideoPlayer): PlayerWindow
