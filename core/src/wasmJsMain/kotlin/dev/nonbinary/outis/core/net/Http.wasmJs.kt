/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.net

// wasmJs has no playback engine yet (see PlayerFactory.wasmJs.kt), so nothing here fetches.
internal actual suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult =
    HttpResult.Failed("No network on the wasmJs target")
