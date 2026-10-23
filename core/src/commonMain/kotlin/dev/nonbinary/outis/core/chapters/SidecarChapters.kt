/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

import dev.nonbinary.outis.core.net.HttpResult
import dev.nonbinary.outis.core.net.httpGet

/** Load + parse a WebVTT chapters sidecar; empty on any failure. */
internal suspend fun loadSidecarChapters(url: String): List<Chapter> {
    val sidecar = httpGet(url) as? HttpResult.Ok ?: return emptyList()
    return runCatching { VttChapters.parse(sidecar.body) }.getOrDefault(emptyList())
}
