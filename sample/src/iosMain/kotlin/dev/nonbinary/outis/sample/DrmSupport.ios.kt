/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import dev.nonbinary.outis.core.source.MimeType

/** AVFoundation plays HLS + progressive MP4 only — no DASH, and no WebM/VP9. */
actual fun containerCaveat(mimeType: MimeType): String? = when (mimeType) {
    MimeType.DASH -> "AVPlayer has no DASH — this won't play on iOS (the HLS/progressive entries do)."
    MimeType.WEBM -> "AVPlayer can't decode WebM/VP9 — this won't play on iOS."
    MimeType.MP4, MimeType.HLS -> null
}
