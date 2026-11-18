/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.track

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextTrackSelectionTest {
    private val english = MediaTrack("en", TrackType.TEXT, language = "en-GB")
    private val spanish = MediaTrack("es", TrackType.TEXT, language = "es")
    private val unlabelled = MediaTrack("x", TrackType.TEXT)

    @Test
    fun languageTagsMatchAcrossRegionsAndCase() {
        assertTrue(languageTagMatches("en-GB", "en"))
        assertTrue(languageTagMatches("en", "en-GB"))
        assertTrue(languageTagMatches("PT_br", "pt-BR"))
        assertFalse(languageTagMatches("en", "es"))
        assertFalse(languageTagMatches("eng", "en"))
    }

    @Test
    fun nothingShowsUnlessCaptionsStartOn() {
        assertNull(initialTextTrack(listOf(english, spanish), captionsOn = false, preferredLanguage = "es"))
    }

    @Test
    fun captionsOnPickThePreferredLanguageElseTheFirstTrack() {
        assertEquals(spanish, initialTextTrack(listOf(english, spanish), captionsOn = true, preferredLanguage = "es"))
        assertEquals(english, initialTextTrack(listOf(english, spanish), captionsOn = true, preferredLanguage = "en"))
        assertEquals(
            unlabelled,
            initialTextTrack(listOf(unlabelled, spanish), captionsOn = true, preferredLanguage = "de"),
        )
        assertEquals(english, initialTextTrack(listOf(english, spanish), captionsOn = true, preferredLanguage = null))
        assertNull(initialTextTrack(emptyList(), captionsOn = true, preferredLanguage = "en"))
    }
}
