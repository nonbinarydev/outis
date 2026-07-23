/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

import androidx.compose.runtime.Composable
import dev.nonbinary.outis.ui.controls.PlayerControlsState
import dev.nonbinary.outis.ui.window.PlayerWindow

@Composable
internal actual fun PlatformPlayerKeyboard(
    state: PlayerControlsState,
    window: PlayerWindow,
    isActive: () -> Boolean,
) {
    // Deliberately empty: iOS has no hardware-keyboard shortcut wiring for the player.
}
