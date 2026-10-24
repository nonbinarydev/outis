/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

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
