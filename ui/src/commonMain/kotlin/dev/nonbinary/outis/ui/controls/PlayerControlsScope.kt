/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.runtime.Stable
import androidx.compose.ui.focus.FocusRequester
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.ui.window.PlayerWindow

/**
 * Receiver of the `controls` lambda. It carries everything a control needs: the [state] spine, the
 * [player], the host [window] (fullscreen/PIP), and a [focusRequester] for grabbing D-pad focus when
 * the overlay appears on TV.
 *
 * Every building block is available two ways: as a zero-argument member-style call inside this scope
 * (e.g. `PlayPauseButton()`), and as a free function taking [state]/[window] for use outside any
 * overlay (the "controls beside the video" pattern). See the building-block functions.
 */
@Stable
interface PlayerControlsScope {
    /** The snapshot every control reads from — position, buffering, tracks, visibility and scrub state. */
    val state: PlayerControlsState

    /** The player itself, for controls that need to issue transport calls beyond what [state] exposes. */
    val player: VideoPlayer

    /**
     * Host-provided fullscreen and picture-in-picture hooks. These are Activity/window concerns the SDK
     * cannot own, so an app that supplies no implementation gets a no-op window rather than a crash.
     */
    val window: PlayerWindow

    /**
     * Attach this to the control that should receive initial D-pad focus when the overlay appears.
     * Only meaningful on TV; harmless elsewhere. Point it at an *enabled* control — a disabled one
     * silently swallows focus and strands the user.
     */
    val focusRequester: FocusRequester
}

internal class PlayerControlsScopeImpl(
    override val state: PlayerControlsState,
    override val window: PlayerWindow,
    override val focusRequester: FocusRequester,
) : PlayerControlsScope {
    override val player: VideoPlayer get() = state.player
}
