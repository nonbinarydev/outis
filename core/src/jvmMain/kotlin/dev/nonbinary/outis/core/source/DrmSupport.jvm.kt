/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

// No playback engine on this target, so nothing to decrypt with.
actual suspend fun drmSupport(): DrmSupport = DrmSupport(emptySet(), widevineLevel = null)
