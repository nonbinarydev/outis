/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import dev.nonbinary.outis.core.source.MimeType
import kotlinx.browser.window

/** WebKit, which has no VP9 — sniffed, since a container caveat is shown synchronously during composition. */
private val isWebKit: Boolean by lazy {
    val ua = window.navigator.userAgent
    ua.contains("Safari", ignoreCase = true) &&
        !ua.contains("Chrome", ignoreCase = true) &&
        !ua.contains("Chromium", ignoreCase = true)
}

/** Shaka plays DASH/HLS/MP4 on every engine via MSE; WebM/VP9 is the gap — WebKit (Safari) has no VP9. */
actual fun containerCaveat(mimeType: MimeType): String? = when (mimeType) {
    MimeType.WEBM -> if (isWebKit) "Safari has no VP9/WebM decoder — this is not expected to play here." else null
    MimeType.MP4, MimeType.HLS, MimeType.DASH -> null
}
