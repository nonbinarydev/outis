/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.runtime.Composable
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.ui.window.PlayerWindow
import dev.nonbinary.outis.ui.window.rememberPlayerWindow

/**
 * `:ui` already ships the Android implementation: [rememberPlayerWindow] finds the hosting Activity,
 * tracks picture-in-picture through the lifecycle, and reports whether PiP is currently permitted.
 *
 * Fullscreen itself stays the application's decision — it usually means hiding system bars and locking
 * orientation, which depends on the app's own chrome — so no `onToggleFullscreen` is supplied here and
 * the button hides itself, exactly as it would in an application that has not implemented it.
 */
@Composable
actual fun rememberSampleWindow(player: VideoPlayer): PlayerWindow = rememberPlayerWindow(player)
