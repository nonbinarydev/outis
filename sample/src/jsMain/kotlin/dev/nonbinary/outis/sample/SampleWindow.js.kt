/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.ui.window.PlayerWindow
import kotlinx.browser.document
import org.w3c.dom.events.Event

/**
 * Browser fullscreen, via the Fullscreen API on the document element — so the Compose canvas and the
 * engine's `<video>` underneath it go fullscreen together, which is what makes the shared overlay keep
 * compositing over the video rather than being left behind.
 *
 * Accessed through `asDynamic()` because Kotlin/JS's DOM externs do not declare the Fullscreen API.
 *
 * The browser can leave fullscreen without being asked — the Escape key, or the user swiping away — so
 * the state is read back from a `fullscreenchange` listener rather than assumed from the last call.
 * Assuming would leave the button showing "exit" over a windowed player.
 */
@Composable
actual fun rememberSampleWindow(player: VideoPlayer): PlayerWindow {
    var isFullscreen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val onChange: (Event) -> Unit = {
            isFullscreen = document.asDynamic().fullscreenElement != null
        }
        document.addEventListener("fullscreenchange", onChange)
        onDispose { document.removeEventListener("fullscreenchange", onChange) }
    }

    return PlayerWindow(
        isFullscreen = isFullscreen,
        onToggleFullscreen = { wantFullscreen ->
            if (wantFullscreen) {
                document.documentElement?.asDynamic()?.requestFullscreen()
            } else {
                document.asDynamic().exitFullscreen()
            }
            Unit
        },
    )
}
