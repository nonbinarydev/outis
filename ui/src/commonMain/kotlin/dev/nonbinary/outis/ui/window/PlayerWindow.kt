/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.window

import androidx.compose.runtime.Immutable

/**
 * Host-owned window state for fullscreen + Picture-in-Picture. The player library never owns the
 * Activity, so it can't toggle these itself — it shows the buttons and delegates the action to the
 * host's callbacks. The host is always the source of truth (the OS can change [isInPip] out of band).
 *
 * A button self-hides when its capability is absent: [onToggleFullscreen] `== null` hides the
 * fullscreen button; `!isPipSupported || onEnterPip == null` hides the PIP button.
 *
 * On Android, `rememberPlayerWindow` (androidMain, so not linkable from here) wires all of this to the
 * Activity for you. On iOS/web the host implements the same callbacks
 * (`AVPictureInPictureController` / `requestPictureInPicture()` + `requestFullscreen()`).
 */
@Immutable
data class PlayerWindow(
    /**
     * Whether the host currently presents the player fullscreen. Purely the **host's** report — the
     * player never sets it; it only picks the expand/collapse icon from it.
     */
    val isFullscreen: Boolean = false,
    /**
     * Whether the player is currently in Picture-in-Picture. The OS can enter or leave PIP without the
     * app asking (home gesture, user dismissing the PIP window), so the host must keep this in sync
     * from platform callbacks rather than only from [onEnterPip] / [onExitPip].
     */
    val isInPip: Boolean = false,
    /** True only when the platform supports PIP **and** the app is currently permitted to use it. */
    val isPipSupported: Boolean = false,
    /** Called with the desired next fullscreen state. */
    val onToggleFullscreen: ((Boolean) -> Unit)? = null,
    /** Enter PIP; returns whether it actually started (denied/unsupported ⇒ `false`). */
    val onEnterPip: (() -> Boolean)? = null,
    /** Optional programmatic expand-from-PIP. */
    val onExitPip: (() -> Unit)? = null,
)
