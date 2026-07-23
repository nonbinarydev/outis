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
 * No hooks: iOS fullscreen and picture-in-picture belong to the presenting `UIViewController`, and this
 * repository ships no iOS host application to own one. The controls hide both buttons accordingly.
 */
@Composable
actual fun rememberSampleWindow(player: VideoPlayer): PlayerWindow = PlayerWindow()
