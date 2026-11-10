/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleFileTest {
    @Test
    fun theFormatComesFromTheExtensionUnlessGiven() {
        assertEquals(SubtitleFormat.SRT, SubtitleFile("https://cdn.example/en.srt").resolvedFormat)
        assertEquals(SubtitleFormat.SRT, SubtitleFile("https://cdn.example/EN.SRT?token=1#t").resolvedFormat)
        assertEquals(SubtitleFormat.WEBVTT, SubtitleFile("https://cdn.example/en.vtt").resolvedFormat)
        assertEquals(SubtitleFormat.WEBVTT, SubtitleFile("https://cdn.example/subtitles?id=7").resolvedFormat)
        assertEquals(
            SubtitleFormat.SRT,
            SubtitleFile("https://cdn.example/subtitles?id=7", format = SubtitleFormat.SRT).resolvedFormat,
        )
    }

    @Test
    fun subRipBecomesWebVtt() {
        val srt = "1\n00:00:01,000 --> 00:00:04,500\nHello\nthere\n\n2\n00:00:05,250 --> 00:00:07,000\nAgain\n"

        assertEquals(
            "WEBVTT\n\n1\n00:00:01.000 --> 00:00:04.500\nHello\nthere\n\n2\n00:00:05.250 --> 00:00:07.000\nAgain\n",
            srtToWebVtt(srt),
        )
    }

    @Test
    fun windowsLineEndingsAByteOrderMarkAndSpacedSeparatorsAreTolerated() {
        val srt = "﻿1\r\n00:00:01,000 --> 00:00:02,000\r\nOne\r\n   \r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nTwo\r\n"

        assertEquals(
            "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.000\nOne\n\n2\n00:00:03.000 --> 00:00:04.000\nTwo\n",
            srtToWebVtt(srt),
        )
    }

    @Test
    fun timingsGainTwoDigitHoursAndLoseBoxCoordinates() {
        val srt = "1\n0:01:02,003 --> 0:01:04.005  X1:40 X2:600 Y1:20 Y2:50\nPositioned\n"

        assertEquals("WEBVTT\n\n1\n00:01:02.003 --> 00:01:04.005\nPositioned\n", srtToWebVtt(srt))
    }

    @Test
    fun aWebVttFileOpensWithItsHeader() {
        assertTrue(hasWebVttHeader("WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n"))
        assertTrue(hasWebVttHeader("WEBVTT - Title\n"))
        assertTrue(hasWebVttHeader("﻿WEBVTT"))
        assertTrue(hasWebVttHeader(EMPTY_WEBVTT))
        assertFalse(hasWebVttHeader("WEBVTTX\n"))
        assertFalse(hasWebVttHeader("<!doctype html><title>Not found</title>"))
        assertFalse(hasWebVttHeader(""))
    }

    @Test
    fun onlyASubtitleFileIsReadAsOne() {
        val vtt = "WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n"
        val page = "<!doctype html>\n<p>Not found</p>\n"

        assertEquals(vtt, subtitleAsWebVtt(vtt, SubtitleFormat.WEBVTT))
        assertNull(subtitleAsWebVtt(page, SubtitleFormat.WEBVTT))
        assertEquals(
            "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.000\nHi\n",
            subtitleAsWebVtt("1\n00:00:01,000 --> 00:00:02,000\nHi\n", SubtitleFormat.SRT),
        )
        assertNull(subtitleAsWebVtt(page, SubtitleFormat.SRT))
    }
}
