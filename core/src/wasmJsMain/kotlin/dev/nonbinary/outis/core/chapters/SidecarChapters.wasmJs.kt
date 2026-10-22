/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

// wasmJs has no playback engine yet (see PlayerFactory.wasmJs.kt), so nothing here loads a sidecar.
internal actual suspend fun httpGetText(url: String): String? = null
