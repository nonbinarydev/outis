/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.thumbnails

import kotlin.test.Test
import kotlin.test.assertEquals

class VttThumbnailsTest {

    @Test
    fun spriteUrlsResolveAgainstTheSidecarLikeLinks() {
        val vtt = """
            WEBVTT

            00:00:00.000 --> 00:00:05.000
            sprite.jpg#xywh=0,0,160,90

            00:00:05.000 --> 00:00:10.000
            /img/sprite2.jpg#xywh=160,0,160,90

            00:00:10.000 --> 00:00:15.000
            ../shared/sprite3.jpg#xywh=0,90,160,90

            00:00:15.000 --> 00:00:20.000
            https://cdn.example.net/s4.jpg#xywh=0,0,160,90
        """.trimIndent()

        val cues = VttThumbnails.parse(vtt, "https://e.example.com/v/42/thumbs.vtt?sig=x")

        assertEquals(
            listOf(
                "https://e.example.com/v/42/sprite.jpg",
                "https://e.example.com/img/sprite2.jpg",
                "https://e.example.com/v/shared/sprite3.jpg",
                "https://cdn.example.net/s4.jpg",
            ),
            cues.map { it.url },
        )
        assertEquals(listOf(0, 160, 0, 0), cues.map { it.x })
        assertEquals(listOf(0L, 5_000L, 10_000L, 15_000L), cues.map { it.startMs })
    }
}
