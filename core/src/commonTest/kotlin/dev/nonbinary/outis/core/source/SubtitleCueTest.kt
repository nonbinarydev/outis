/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleCueTest {
    private fun List<SubtitleCue>.render() = map { "${it.startMs}-${it.endMs}:${it.text}" }

    @Test
    fun cuesAreReadWithOrWithoutHoursAndIdentifiers() {
        val vtt = "WEBVTT\n\nintro\n00:00:01.500 --> 00:00:03.000\nHello\n\n" +
            "00:04.000 --> 00:05.250 line:90% align:start\nTwo\nlines\n\n1:00:00.000 --> 1:00:01.000\nLate\n"

        assertEquals(
            listOf("1500-3000:Hello", "4000-5250:Two\nlines", "3600000-3601000:Late"),
            parseWebVttCues(vtt).render(),
        )
    }

    @Test
    fun markupIsDroppedAndReferencesDecoded() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000\n<v Roger><i>Fish</i> &amp; chips &lt;3</v>\n"

        assertEquals(listOf("1000-2000:Fish & chips <3"), parseWebVttCues(vtt).render())
    }

    @Test
    fun notesStylesAndBrokenBlocksAreSkippedAndCuesSorted() {
        val vtt = "﻿WEBVTT - title\r\n\r\nNOTE a comment\r\n\r\nSTYLE\r\n::cue { color: red }\r\n\r\n" +
            "00:05.000 --> 00:06.000\r\nSecond\r\n\r\n00:0x.000 --> 00:01.000\r\nBroken\r\n\r\n" +
            "00:03.000 --> 00:02.000\r\nBackwards\r\n\r\n00:01.000 --> 00:02.000\r\nFirst\r\n"

        assertEquals(listOf("1000-2000:First", "5000-6000:Second"), parseWebVttCues(vtt).render())
    }

    @Test
    fun subRipIsReadThroughItsWebVttForm() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nUno\n\n2\n00:00:03,000 --> 00:00:04,000\nDos\n"
        val vtt = subtitleAsWebVtt(srt, SubtitleFormat.SRT) ?: error("not read as SubRip")

        assertEquals(listOf("1000-2000:Uno", "3000-4000:Dos"), parseWebVttCues(vtt).render())
    }

    @Test
    fun aCueShowsFromItsStartUntilItsEnd() {
        val cues = parseWebVttCues(
            "WEBVTT\n\n00:01.000 --> 00:03.000\nA\n\n00:02.000 --> 00:04.000\nB\n",
        )

        assertEquals(emptyList(), cueTextAt(cues, 999))
        assertEquals(listOf("A"), cueTextAt(cues, 1000))
        assertEquals(listOf("A", "B"), cueTextAt(cues, 2500))
        assertEquals(listOf("B"), cueTextAt(cues, 3000))
        assertEquals(emptyList(), cueTextAt(cues, 4000))
    }
}
