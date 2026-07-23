/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.window.PlayerWindow

/**
 * Icon button that asks the host to enter or leave full screen, via [PlayerWindow.onToggleFullscreen].
 *
 * **Self-hiding**: renders nothing when that callback is `null`, i.e. the host has not declared it can
 * go full screen. The SDK never changes window state itself — it only reports the requested value
 * (`!`[PlayerWindow.isFullscreen]) to the host, which owns the actual transition; the icon therefore
 * flips only once the host feeds the new [PlayerWindow.isFullscreen] back in.
 */
@ExperimentalPlayerUiApi
@Composable
fun FullscreenButton(state: PlayerControlsState, window: PlayerWindow, modifier: Modifier = Modifier) {
    val toggle = window.onToggleFullscreen ?: return // self-hide when the host can't go fullscreen
    IconButton(
        onClick = {
            toggle(!window.isFullscreen)
            state.notifyInteraction()
        },
        modifier = modifier.controlFocusRing(),
    ) {
        Icon(
            imageVector = if (window.isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            contentDescription = if (window.isFullscreen) "Exit full screen" else "Full screen",
        )
    }
}

/**
 * Icon button that asks the host to enter picture-in-picture, via [PlayerWindow.onEnterPip].
 *
 * **Self-hiding**: renders nothing unless [PlayerWindow.isPipSupported] is `true` **and** the callback
 * is non-`null` — both must hold, since support is a platform fact while the callback is the host
 * opting in. Entry only; there is no "leave PIP" affordance, because the system window owns that.
 * The callback's `Boolean` result (whether the host actually entered PIP) is ignored here.
 */
@ExperimentalPlayerUiApi
@Composable
fun PipButton(state: PlayerControlsState, window: PlayerWindow, modifier: Modifier = Modifier) {
    val enter = window.onEnterPip
    if (!window.isPipSupported || enter == null) return // self-hide when PIP is unavailable
    IconButton(
        onClick = {
            enter()
            state.notifyInteraction()
        },
        modifier = modifier.controlFocusRing(),
    ) {
        Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "Picture in picture")
    }
}

/** Scope-member form of [FullscreenButton], taking [state] and [window] from the controls lambda. */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.FullscreenButton(modifier: Modifier = Modifier) {
    FullscreenButton(state, window, modifier)
}

/** Scope-member form of [PipButton], taking [state] and [window] from the controls lambda. */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.PipButton(modifier: Modifier = Modifier) {
    PipButton(state, window, modifier)
}
