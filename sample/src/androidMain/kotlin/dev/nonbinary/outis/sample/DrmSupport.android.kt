/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import dev.nonbinary.outis.core.source.MimeType

/** ExoPlayer handles every container the catalogue uses — DASH, HLS, progressive MP4 and WebM/VP9. */
actual fun containerCaveat(mimeType: MimeType): String? = when (mimeType) {
    MimeType.MP4, MimeType.HLS, MimeType.DASH, MimeType.WEBM -> null
}
