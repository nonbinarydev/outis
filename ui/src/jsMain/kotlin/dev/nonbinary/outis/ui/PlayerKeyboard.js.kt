/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.nonbinary.outis.ui.controls.PlayerControlsState
import dev.nonbinary.outis.ui.window.PlayerWindow
import kotlinx.browser.document
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

@Composable
internal actual fun PlatformPlayerKeyboard(
    state: PlayerControlsState,
    window: PlayerWindow,
    isActive: () -> Boolean,
) {
    // The listener is registered once and outlives recomposition, so it must not capture `isActive`
    // directly — a caller passing a fresh lambda would keep being gated by the stale one.
    val currentIsActive by rememberUpdatedState(isActive)
    DisposableEffect(state, window) {
        val handler: (Event) -> Unit = handler@{ event ->
            if (!currentIsActive()) return@handler
            val ke = event as? KeyboardEvent ?: return@handler
            // Leave browser shortcuts (Cmd/Ctrl/Alt combos) alone.
            if (ke.altKey || ke.ctrlKey || ke.metaKey) return@handler
            val handled = when (ke.key.lowercase()) {
                " ", "spacebar", "k" -> {
                    state.playPause()
                    true
                }
                "m" -> {
                    state.toggleMute()
                    true
                }
                "f" -> window.onToggleFullscreen?.let {
                    it(!window.isFullscreen)
                    true
                } ?: false
                else -> false
            }
            if (handled) {
                event.preventDefault() // e.g. stop Space scrolling the page
                state.showControls()
            }
        }
        document.addEventListener("keydown", handler)
        onDispose { document.removeEventListener("keydown", handler) }
    }
}
