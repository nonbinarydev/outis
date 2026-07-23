/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

/**
 * The web engine (Shaka) ships on the JS(IR) target in v1. Kotlin/Wasm ↔ Shaka JS interop is a
 * follow-up; `wasmJs` compiles the engine-agnostic core but has no playback engine yet.
 */
internal actual fun createPlatformPlayer(context: AppContext, config: PlayerConfig): VideoPlayer =
    throw UnsupportedOperationException("Web playback runs on the JS target in v1; wasmJs engine is a follow-up.")
