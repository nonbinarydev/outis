/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals

class DeclaredChaptersTest {
    private val source = MediaSource.Url("https://example.com/master.m3u8")

    @Test
    fun declaredChaptersAreSortedByStart() {
        val item = MediaItem(source, chapters = listOf(Chapter(122_400, "Two"), Chapter(0, "One")))

        assertEquals(listOf("One", "Two"), item.declaredChapters.map { it.title })
    }

    @Test
    fun anItemThatDeclaresNoneHasNone() {
        assertEquals(emptyList(), MediaItem(source).declaredChapters)
        assertEquals(emptyList(), MediaItem(source, chapters = emptyList()).declaredChapters)
    }
}
