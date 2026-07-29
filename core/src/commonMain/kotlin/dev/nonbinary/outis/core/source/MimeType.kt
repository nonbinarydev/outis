/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

/**
 * Container / streaming type, supplied when it can't be inferred from the URL. [WEBM] is progressive
 * (VP9/AV1 in WebM) — web and Android play it via the media element / ExoPlayer; AVPlayer can't decode
 * it, so a WEBM item surfaces a clean codec error on iOS.
 */
enum class MimeType { MP4, HLS, DASH, WEBM }
