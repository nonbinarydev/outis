/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

import dev.nonbinary.outis.core.source.MediaItem

/** [MediaItem.chapters] in the order [dev.nonbinary.outis.core.PlayerState.chapters] promises; empty if none. */
internal val MediaItem.declaredChapters: List<Chapter>
    get() = chapters?.sortedBy { it.startMs }.orEmpty()
