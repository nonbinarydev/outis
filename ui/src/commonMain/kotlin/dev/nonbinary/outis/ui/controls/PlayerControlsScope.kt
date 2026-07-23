/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
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

/**
 * [window] is held as [State] rather than a value so this scope keeps a stable identity across
 * recomposition while still reporting the current window.
 *
 * [PlayerWindow] is a `data class` carrying three function-typed properties, and hosts build it with
 * fresh capturing lambdas on every composition pass, so it is essentially never `equals` to its
 * predecessor. Constructing the scope with `remember(state, window, …)` therefore reallocated it
 * constantly, and — worse — any effect keyed on the same instance restarted with it.
 *
 * Reading `window` inside a composable subscribes to the snapshot, so controls still recompose when the
 * host reports a new fullscreen or PIP state. That is what keeps this honest as a [Stable] type: the
 * property changes, but never without notifying the composition.
 */
internal class PlayerControlsScopeImpl(
    override val state: PlayerControlsState,
    private val windowState: State<PlayerWindow>,
    override val focusRequester: FocusRequester,
) : PlayerControlsScope {
    override val player: VideoPlayer get() = state.player
    override val window: PlayerWindow get() = windowState.value
}
