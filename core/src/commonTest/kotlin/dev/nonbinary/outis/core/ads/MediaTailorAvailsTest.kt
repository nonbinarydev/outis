/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaTailorAvailsTest {

    @Test
    fun parsesAvailsIntoBreaks_secondsToMs() {
        // Shape mirrors a MediaTailor avails document: seconds as floats, plus extra fields
        // (adSystem, trackingEvents) that must be ignored.
        val json = """
            {"avails":[
              {"availId":"avail-1","startTimeInSeconds":0.0,"durationInSeconds":18.0,
               "ads":[
                 {"adId":"ad-1","adTitle":"Pre A","durationInSeconds":10.01,"startTimeInSeconds":0.0,"adSystem":"GDFP","clickthroughUrl":"https://example/click","skipOffset":null,"trackingEvents":[{"eventType":"impression","beaconUrls":["https://b/1"]}]},
                 {"adId":"ad-2","adTitle":"Pre B","durationInSeconds":7.99,"startTimeInSeconds":10.01,"skipOffset":"PT5S"}
               ]},
              {"availId":"avail-2","startTimeInSeconds":120.5,"durationInSeconds":30.0,
               "ads":[{"adId":"ad-3","durationInSeconds":30.0,"startTimeInSeconds":120.5}]}
            ]}
        """.trimIndent()

        val breaks = parseMediaTailorAvails(json)

        assertEquals(2, breaks.size)
        assertEquals("avail-1", breaks[0].id)
        assertEquals(0L, breaks[0].startMs)
        assertEquals(2, breaks[0].ads.size)
        assertEquals(10_010L, breaks[0].ads[0].durationMs) // 10.01s → ms
        assertEquals("Pre A", breaks[0].ads[0].title)
        assertEquals("https://example/click", breaks[0].ads[0].clickThroughUrl)
        assertNull(breaks[0].ads[0].skipOffsetMs)
        assertEquals(5_000L, breaks[0].ads[1].skipOffsetMs) // PT5S → ms
        assertEquals(120_500L, breaks[1].startMs) // 120.5s → ms
        assertEquals(30_000L, breaks[1].ads[0].durationMs)
    }

    @Test
    fun toleratesEmptyAndUnknownFields() {
        assertTrue(parseMediaTailorAvails("""{"avails":[],"nextToken":"abc"}""").isEmpty())
        val breaks = parseMediaTailorAvails(
            """{"avails":[{"availId":"a","startTimeInSeconds":1,"durationInSeconds":""" +
                """2,"adMarkerDuration":"x","ads":[]}]}""",
        )
        assertEquals(1, breaks.size)
        assertEquals(1_000L, breaks[0].startMs)
    }

    @Test
    fun parsesSessionResponse() {
        val session = parseMediaTailorSession(
            """{"manifestUrl":"/v1/m.m3u8?aws.sessionId=xyz","trackingUrl":"/v1/tracking?session=xyz","extra":1}""",
        )
        assertEquals("/v1/m.m3u8?aws.sessionId=xyz", session.manifestUrl)
        assertEquals("/v1/tracking?session=xyz", session.trackingUrl)
    }

    @Test
    fun parsesIso8601Seconds() {
        assertEquals(5.0, parseIso8601Seconds("PT5S"))
        assertEquals(65.0, parseIso8601Seconds("PT1M5S"))
        assertEquals(38.4, parseIso8601Seconds("PT38.4S"))
        assertEquals(7.0, parseIso8601Seconds("7"))
        assertNull(parseIso8601Seconds("not-a-duration"))
    }
}
