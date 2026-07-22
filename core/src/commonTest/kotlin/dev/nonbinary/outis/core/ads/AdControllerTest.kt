/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdControllerTest {

    private fun ad(id: String, durationMs: Long, skipOffsetMs: Long? = null) =
        Ad(id = id, durationMs = durationMs, skipOffsetMs = skipOffsetMs)

    @Test
    fun preRoll_isDetected_andCountsDown() {
        val ads = AdController(listOf(AdBreak("pre", startMs = 0, ads = listOf(ad("a", 5_000)))))

        ads.onPosition(0)
        assertTrue(ads.state.value.isInAdBreak)
        assertEquals(5_000, ads.state.value.adRemainingMs)

        ads.onPosition(2_000)
        assertEquals(3_000, ads.state.value.adRemainingMs)
        assertEquals("pre", ads.state.value.currentBreak?.id)
    }

    @Test
    fun multiAdBreak_reportsIndexAndCount() {
        val ads = AdController(listOf(AdBreak("pre", 0, listOf(ad("a", 5_000), ad("b", 5_000)))))

        ads.onPosition(1_000)
        assertEquals(0, ads.state.value.adIndexInBreak)
        assertEquals(2, ads.state.value.adCountInBreak)
        assertEquals("a", ads.state.value.currentAd?.id)

        ads.onPosition(6_000)
        assertEquals(1, ads.state.value.adIndexInBreak)
        assertEquals("b", ads.state.value.currentAd?.id)
    }

    @Test
    fun skip_becomesAvailableAtOffset() {
        val ads = AdController(listOf(AdBreak("pre", 0, listOf(ad("a", 10_000, skipOffsetMs = 5_000)))))

        ads.onPosition(2_000)
        assertFalse(ads.state.value.canSkip)
        ads.onPosition(6_000)
        assertTrue(ads.state.value.canSkip)
    }

    @Test
    fun seek_cannotSkipUnwatchedBreak() {
        val ads = AdController(listOf(AdBreak("mid", startMs = 10_000, ads = listOf(ad("a", 5_000)))))
        // Jumping from 5s to 30s would skip the mid-roll at 10s → snap to the break start.
        assertEquals(10_000, ads.resolveSeek(fromMs = 5_000, targetMs = 30_000))
    }

    @Test
    fun seek_isAllowedOnceBreakWatched() {
        val ads = AdController(listOf(AdBreak("mid", startMs = 10_000, ads = listOf(ad("a", 5_000)))))
        // Play through the break, then past its end so it's marked watched.
        ads.onPosition(10_000)
        ads.onPosition(12_000)
        ads.onPosition(15_001)
        assertFalse(ads.state.value.isInAdBreak)
        assertEquals(30_000, ads.resolveSeek(fromMs = 16_000, targetMs = 30_000))
    }

    @Test
    fun backwardSeek_passesThrough() {
        val ads = AdController(listOf(AdBreak("mid", startMs = 10_000, ads = listOf(ad("a", 5_000)))))
        assertEquals(2_000, ads.resolveSeek(fromMs = 30_000, targetMs = 2_000))
    }

    @Test
    fun playingThroughBreak_marksItWatchedAndExitsAdState() {
        val ads = AdController(listOf(AdBreak("pre", 0, listOf(ad("a", 4_000)))))
        ads.onPosition(0) // enter break
        ads.onPosition(3_500) // mid-ad
        assertTrue(ads.state.value.isInAdBreak)
        ads.onPosition(4_001) // past the end → watched, exit ad state
        assertFalse(ads.state.value.isInAdBreak)
        // Once watched, a forward seek over its position is no longer clamped.
        assertEquals(10_000, ads.resolveSeek(fromMs = 5_000, targetMs = 10_000))
    }

    @Test
    fun coarsePollJumpingOverBreak_marksItWatched() {
        val ads = AdController(listOf(AdBreak("mid", startMs = 10_000, ads = listOf(ad("a", 5_000)))))
        // A coarse poll (or a seek that landed past it) jumps from before the break to well past its end.
        ads.onPosition(8_000)
        ads.onPosition(41_000)
        assertFalse(ads.state.value.isInAdBreak)
        // It's now watched, so a later forward seek across its position is no longer clamped back into it.
        assertEquals(50_000, ads.resolveSeek(fromMs = 42_000, targetMs = 50_000))
    }

    @Test
    fun forwardSeek_fromInsideBreak_isPinned() {
        val ads = AdController(listOf(AdBreak("mid", startMs = 10_000, ads = listOf(ad("a", 8_000)))))
        ads.onPosition(12_000) // inside the break
        assertTrue(ads.state.value.isInAdBreak)
        // Can't seek forward out of the current ad.
        assertEquals(12_000, ads.resolveSeek(fromMs = 12_000, targetMs = 40_000))
    }

    @Test
    fun onEnded_finalizesUnwatchedPostRoll() {
        val ads = AdController(listOf(AdBreak("post", startMs = 600_000, ads = listOf(ad("a", 5_000)))))
        // Never reached the post-roll by polling; ending should still mark it watched.
        ads.onPosition(120_000)
        ads.onEnded()
        assertEquals(700_000, ads.resolveSeek(fromMs = 100_000, targetMs = 700_000))
    }

    @Test
    fun markWatchedUpTo_skipsResumedPastBreaks() {
        val ads = AdController(listOf(AdBreak("mid", startMs = 10_000, ads = listOf(ad("a", 5_000)))))
        // Resuming at 20s should treat the already-passed mid-roll as watched.
        ads.markWatchedUpTo(20_000)
        assertEquals(30_000, ads.resolveSeek(fromMs = 5_000, targetMs = 30_000))
    }
}
