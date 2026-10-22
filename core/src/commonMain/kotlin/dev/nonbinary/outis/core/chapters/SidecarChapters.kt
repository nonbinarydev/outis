/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

/** Fetch a small text resource (the WebVTT sidecar). Returns null on any failure — chapters are optional. */
internal expect suspend fun httpGetText(url: String): String?

/** Load + parse a WebVTT chapters sidecar; empty on any failure. */
internal suspend fun loadSidecarChapters(url: String): List<Chapter> =
    httpGetText(url)?.let { runCatching { VttChapters.parse(it) }.getOrDefault(emptyList()) } ?: emptyList()
