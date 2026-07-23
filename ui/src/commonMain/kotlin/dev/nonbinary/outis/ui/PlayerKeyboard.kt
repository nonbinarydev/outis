/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

import androidx.compose.runtime.Composable
import dev.nonbinary.outis.ui.controls.PlayerControlsState
import dev.nonbinary.outis.ui.window.PlayerWindow

/**
 * Wires platform keyboard shortcuts for the player. Only **web** has an implementation: it attaches a
 * DOM `keydown` listener (Space/K = play-pause, M = mute, F = fullscreen) gated by [isActive], because
 * Compose/JS key routing through the focus system is unreliable on the skiko canvas. Android and iOS
 * no-op (TV uses the D-pad; touch has no keyboard).
 *
 * @param isActive returns whether shortcuts should currently fire (e.g. the pointer is over the player),
 *   so the page can keep its own key behaviour (Space-to-scroll) when the player isn't engaged.
 */
@Composable
internal expect fun PlatformPlayerKeyboard(
    state: PlayerControlsState,
    window: PlayerWindow,
    isActive: () -> Boolean,
)
