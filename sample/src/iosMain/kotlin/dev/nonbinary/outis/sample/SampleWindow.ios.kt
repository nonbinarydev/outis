/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.ui.window.PlayerWindow

/**
 * iOS has no OS notion of "full-screening" an embedded view — the video surface is an
 * `AVPlayerViewController` hosted inside our own Compose UI, with its controls off. So fullscreen here is
 * a **layout** toggle: [PlayerWindow.isFullscreen] drives the app to expand the player edge-to-edge and
 * hide its own chrome (see `AppContent`), the same visual result the web host gets from the Fullscreen
 * API. The state lives here — the host owns it — and the fullscreen button reads it straight back.
 *
 * Picture-in-picture stays unwired: `AVPlayerViewController` with controls disabled exposes no public way
 * to start PIP from a custom button, so [PlayerWindow.isPipSupported] is left false and the button hides.
 */
/**
 * Bridges the shared UI's fullscreen state to the SwiftUI host (`ContentView`). Hiding the iOS status bar
 * in a scene-based SwiftUI app is done with SwiftUI's `.statusBar(hidden:)` — not reachable from Kotlin —
 * so the host reads [isFullscreen] whenever [onChange] fires. The callback is arg-less on purpose: a
 * `(Boolean) -> Unit` would arrive in Swift as a boxed `KotlinBoolean`; a plain read of [isFullscreen]
 * (a `Bool` property) avoids that.
 */
object FullscreenBridge {
    var isFullscreen: Boolean = false
        private set
    var onChange: (() -> Unit)? = null

    internal fun update(value: Boolean) {
        isFullscreen = value
        onChange?.invoke()
    }
}

@Composable
actual fun rememberSampleWindow(player: VideoPlayer): PlayerWindow {
    var fullscreen by remember { mutableStateOf(false) }
    return PlayerWindow(
        isFullscreen = fullscreen,
        onToggleFullscreen = {
            fullscreen = it
            FullscreenBridge.update(it)
        },
    )
}
