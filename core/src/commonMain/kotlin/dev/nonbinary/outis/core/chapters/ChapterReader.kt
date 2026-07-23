/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

/**
 * Random-access byte source over a local media file, consumed by [ChapterExtractor]. Implemented per
 * platform that needs it (Android wraps a `RandomAccessFile`); tests back it with a `ByteArray`. Lets the
 * parser do small ranged reads (seek to box/element offsets) instead of loading whole multi-GB files.
 */
interface ChapterReader {
    /** Total file length in bytes. */
    val size: Long

    /**
     * Read up to [length] bytes starting at [offset]. Returns fewer bytes only at EOF (and an empty array
     * past EOF); never throws for an out-of-range read so the parser can probe defensively.
     */
    fun readAt(offset: Long, length: Int): ByteArray
}
