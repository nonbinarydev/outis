/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

// AVFoundation has no DASH.
internal actual val platformManifestFormats: List<MimeType> = listOf(MimeType.HLS)
