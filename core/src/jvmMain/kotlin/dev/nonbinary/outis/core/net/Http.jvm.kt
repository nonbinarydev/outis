/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.net

// JVM has no playback engine (see PlayerFactory.jvm.kt), so nothing here fetches.
internal actual suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult =
    HttpResult.Failed("No network on the JVM target")
