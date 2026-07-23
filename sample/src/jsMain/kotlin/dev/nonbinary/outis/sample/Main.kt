/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.nonbinary.outis.core.AppContext

/**
 * Web entry point. `AppContext()` carries nothing here — it exists so the Android engine can reach a
 * `Context`, and is empty on every other platform.
 *
 * [ComposeViewport] creates its own `<canvas>` inside the named container and clears whatever was
 * there, so `index.html` provides an empty `<div>` rather than a canvas of its own. The web
 * `PlayerSurface` then keeps the engine's `<video>` underneath that canvas and punches a transparent
 * hole through it, so the same Compose controls composite over the video as they do on Android and iOS.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "outis") {
        App(AppContext())
    }
}
