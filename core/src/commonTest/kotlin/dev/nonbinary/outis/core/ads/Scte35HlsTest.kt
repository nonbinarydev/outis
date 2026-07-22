/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Scte35HlsTest {

    @Test
    fun parsesDateRangeAvails_ignoringPairedCueOut() {
        // Mirrors the Unified Streaming reference stream: each avail carries BOTH an EXT-X-DATERANGE
        // (with a stable ID + PLANNED-DURATION + SCTE35-OUT) and an EXT-X-CUE-OUT — must not double-count.
        val playlist =
            """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-TARGETDURATION:8
            #EXT-X-MEDIA-SEQUENCE:100
            #EXTINF:6.0,
            seg100.ts
            #EXT-X-DATERANGE:ID="avail-A",START-DATE="2026-06-18T11:29:28.320000Z",PLANNED-DURATION=38.4,SCTE35-OUT=0xFC302000
            #EXT-X-CUE-OUT:38.4
            #EXTINF:6.0,
            seg101.ts
            #EXT-X-CUE-IN
            #EXTINF:6.0,
            seg102.ts
            #EXT-X-DATERANGE:ID="avail-B",START-DATE="2026-06-18T11:31:28.320000Z",PLANNED-DURATION=30,SCTE35-OUT=0xFC302000
            """.trimIndent()

        val cues = parseHlsAdCues(playlist)

        assertEquals(2, cues.size)
        assertEquals("avail-A", cues[0].id)
        assertEquals(38_400L, cues[0].durationMs)
        assertEquals("avail-B", cues[1].id)
        assertEquals(30_000L, cues[1].durationMs)
    }

    @Test
    fun parsesCueOutBracket_whenNoDateRange() {
        val playlist = "#EXTM3U\n#EXT-X-CUE-OUT:15.0\n#EXTINF:6,\ns.ts\n#EXT-X-CUE-IN\n" +
            "#EXT-X-CUE-OUT:DURATION=20\n#EXTINF:6,\nt.ts"
        val cues = parseHlsAdCues(playlist)
        assertEquals(2, cues.size)
        assertEquals(15_000L, cues[0].durationMs)
        assertEquals(20_000L, cues[1].durationMs)
    }

    @Test
    fun adFreePlaylist_hasNoCues() {
        assertTrue(parseHlsAdCues("#EXTM3U\n#EXTINF:6,\ns.ts").isEmpty())
    }

    @Test
    fun dynamicLiveCue_isDetectedAndDeduped() {
        val ads = AdController(emptyList())
        ads.onPosition(5_000)
        assertTrue(!ads.state.value.isInAdBreak)

        ads.addBreak(AdBreak("live-1", startMs = 5_000, ads = listOf(Ad("a", 10_000))))
        ads.onPosition(5_000)
        assertTrue(ads.state.value.isInAdBreak)
        assertEquals("live-1", ads.state.value.currentBreak?.id)

        // Re-adding the same id is a no-op.
        ads.addBreak(AdBreak("live-1", startMs = 5_000, ads = listOf(Ad("a", 10_000))))
        assertEquals(1, ads.state.value.cuePoints.size)
    }
}
