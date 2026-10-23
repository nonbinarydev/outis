/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.ads

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The [AdController.events] stream. It is replay-free, so every test subscribes before the first position. */
class AdControllerEventsTest {

    private val tenSeconds = Ad("a", durationMs = 10_000, skipOffsetMs = 5_000)

    // --- quartiles ---------------------------------------------------------------------------------

    @Test
    fun quartilesFireInAscendingOrderAsTheAdPlays() = runTest {
        val ads = AdController(listOf(AdBreak("b", 0, listOf(tenSeconds))))
        val seen = recording(ads)

        listOf(0L, 3_000, 6_000, 8_000, 9_000).forEach(ads::onPosition)

        assertEquals(
            listOf("BreakStarted b", "AdStarted a 1/1", "Quartile a FIRST", "Quartile a MIDPOINT", "Quartile a THIRD"),
            seen.labels(),
        )
    }

    @Test
    fun quartileThresholdsAreInclusive() = runTest {
        val ads = AdController(listOf(AdBreak("b", 0, listOf(tenSeconds))))
        val seen = recording(ads)

        ads.onPosition(2_499)
        assertTrue(seen.quartiles().isEmpty())
        ads.onPosition(2_500)
        ads.onPosition(5_000)
        ads.onPosition(7_500)

        assertEquals(listOf("Quartile a FIRST", "Quartile a MIDPOINT", "Quartile a THIRD"), seen.quartiles())
    }

    @Test
    fun eachQuartileFiresAtMostOncePerAd() = runTest {
        val ads = AdController(listOf(AdBreak("b", 0, listOf(tenSeconds))))
        val seen = recording(ads)

        repeat(3) { ads.onPosition(6_000) }

        assertEquals(listOf("Quartile a FIRST", "Quartile a MIDPOINT"), seen.quartiles())
    }

    @Test
    fun aForwardJumpPastEveryThresholdEmitsAllThreeInOrder() = runTest {
        val ads = AdController(listOf(AdBreak("b", 0, listOf(tenSeconds))))
        val seen = recording(ads)

        ads.onPosition(8_000)

        assertEquals(listOf("Quartile a FIRST", "Quartile a MIDPOINT", "Quartile a THIRD"), seen.quartiles())
    }

    @Test
    fun aZeroLengthAdIsNeverCurrentSoEmitsNothing() = runTest {
        val ads = AdController(listOf(AdBreak("b", 0, listOf(Ad("zero", 0), tenSeconds))))
        val seen = recording(ads)

        listOf(0L, 5_000, 9_000).forEach(ads::onPosition)

        assertTrue(seen.labels().none { "zero" in it }, seen.labels().toString())
    }

    @Test
    fun theSecondAdInAPodEmitsItsOwnQuartiles() = runTest {
        val pod = AdBreak("b", 0, listOf(Ad("a", 10_000), Ad("c", 10_000)))
        val ads = AdController(listOf(pod))
        val seen = recording(ads)

        listOf(0L, 8_000, 10_000, 18_000).forEach(ads::onPosition)

        assertEquals(
            listOf(
                "BreakStarted b", "AdStarted a 1/2", "Quartile a FIRST", "Quartile a MIDPOINT", "Quartile a THIRD",
                "AdCompleted a", "AdStarted c 2/2", "Quartile c FIRST", "Quartile c MIDPOINT", "Quartile c THIRD",
            ),
            seen.labels(),
        )
    }

    // --- leaving and re-entering a break ------------------------------------------------------------

    @Test
    fun seekingBackOutOfABreakCompletesTheAdButNotTheBreak() = runTest {
        val ads = AdController(listOf(AdBreak("b", 10_000, listOf(tenSeconds))))
        val seen = recording(ads)

        ads.onPosition(16_000)
        ads.onPosition(5_000)

        assertEquals(
            listOf("BreakStarted b", "AdStarted a 1/1", "Quartile a FIRST", "Quartile a MIDPOINT", "AdCompleted a"),
            seen.labels(),
        )
        assertEquals(10_000, ads.resolveSeek(5_000, 30_000)) // still unwatched
    }

    @Test
    fun reEnteringAnUnwatchedBreakEmitsItsEventsAgain() = runTest {
        val ads = AdController(listOf(AdBreak("b", 10_000, listOf(tenSeconds))))
        val seen = recording(ads)

        ads.onPosition(16_000)
        ads.onPosition(5_000)
        seen.clear()
        ads.onPosition(16_000)

        assertEquals(
            listOf("BreakStarted b", "AdStarted a 1/1", "Quartile a FIRST", "Quartile a MIDPOINT"),
            seen.labels(),
        )
    }

    @Test
    fun aJumpFromOneBreakIntoAnotherCompletesTheAdItWasOn() = runTest {
        val ads = AdController(
            listOf(AdBreak("b1", 0, listOf(Ad("a", 10_000))), AdBreak("b2", 20_000, listOf(Ad("c", 10_000)))),
        )
        val seen = recording(ads)

        ads.onPosition(1_000)
        ads.onPosition(21_000)
        ads.onPosition(31_000)

        assertEquals(
            listOf(
                "BreakStarted b1", "AdStarted a 1/1", "AdCompleted a", "BreakStarted b2", "AdStarted c 1/1",
                "BreakCompleted b1", "AdCompleted c", "BreakCompleted b2", "AllAdsCompleted",
            ),
            seen.labels(),
        )
    }

    @Test
    fun aWatchedBreakStaysSilent() = runTest {
        val ads = AdController(listOf(AdBreak("b", 0, listOf(tenSeconds))))
        val seen = recording(ads)

        ads.onPosition(0)
        ads.onPosition(10_000)
        seen.clear()
        ads.onPosition(5_000)

        assertTrue(seen.isEmpty(), seen.labels().toString())
    }

    // --- completing breaks -----------------------------------------------------------------------------

    @Test
    fun playingThroughABreakCompletesTheAdThenTheBreak() = runTest {
        val ads = AdController(listOf(AdBreak("b", 0, listOf(tenSeconds))))
        val seen = recording(ads)

        ads.onPosition(0)
        ads.onPosition(10_000)

        assertEquals(
            listOf("BreakStarted b", "AdStarted a 1/1", "AdCompleted a", "BreakCompleted b", "AllAdsCompleted"),
            seen.labels(),
        )
    }

    @Test
    fun jumpingOverABreakNeverEnteredCompletesItWithNoAdEvents() = runTest {
        val ads = AdController(listOf(AdBreak("b", 10_000, listOf(Ad("a", 5_000)))))
        val seen = recording(ads)

        ads.onPosition(0)
        ads.onPosition(20_000)

        assertEquals(listOf("BreakCompleted b", "AllAdsCompleted"), seen.labels())
    }

    @Test
    fun jumpingOverSeveralBreaksCompletesEachInTimelineOrder() = runTest {
        val ads = AdController(
            listOf(
                AdBreak("b3", 30_000, listOf(Ad("x", 1_000))),
                AdBreak("b1", 10_000, listOf(Ad("y", 1_000))),
                AdBreak("b2", 20_000, listOf(Ad("z", 1_000))),
            ),
        )
        val seen = recording(ads)

        ads.onPosition(40_000)

        assertEquals(
            listOf("BreakCompleted b1", "BreakCompleted b2", "BreakCompleted b3", "AllAdsCompleted"),
            seen.labels(),
        )
    }

    @Test
    fun allAdsCompletedFiresExactlyOnce() = runTest {
        val ads = AdController(listOf(AdBreak("b", 0, listOf(tenSeconds))))
        val seen = recording(ads)

        listOf(0L, 10_000, 20_000, 30_000).forEach(ads::onPosition)
        ads.onEnded()

        assertEquals(1, seen.labels().count { it == "AllAdsCompleted" })
    }

    @Test
    fun aDuplicatedBreakIdCompletesOnce() = runTest {
        val ads = AdController(
            listOf(AdBreak("dup", 10_000, listOf(Ad("x", 1_000))), AdBreak("dup", 20_000, listOf(Ad("y", 1_000)))),
        )
        val seen = recording(ads)

        ads.onPosition(40_000)

        assertEquals(listOf("BreakCompleted dup", "AllAdsCompleted"), seen.labels())
    }

    @Test
    fun onEndedCompletesAPostRollTheLastPollNeverReached() = runTest {
        val ads = AdController(listOf(AdBreak("post", 100_000, listOf(tenSeconds))))
        val seen = recording(ads)

        ads.onPosition(95_000)
        ads.onEnded()

        assertEquals(listOf("BreakCompleted post", "AllAdsCompleted"), seen.labels())
    }

    @Test
    fun skippingCompletesTheAdTheBreakAndAllAds() = runTest {
        val ads = AdController(listOf(AdBreak("b", 0, listOf(tenSeconds))))
        val seen = recording(ads)

        ads.onPosition(6_000)
        seen.clear()

        assertEquals(10_000, ads.skipCurrentBreak())
        assertEquals(listOf("AdCompleted a", "BreakCompleted b", "AllAdsCompleted"), seen.labels())
    }

    @Test
    fun aBreakAddedLaterFiresAllAdsCompletedAgain() = runTest {
        val ads = AdController(listOf(AdBreak("b1", 0, listOf(Ad("x", 1_000)))))
        val seen = recording(ads)

        ads.onPosition(5_000)
        ads.addBreak(AdBreak("b2", 10_000, listOf(Ad("y", 1_000))))
        ads.onPosition(20_000)

        assertEquals(
            listOf("BreakCompleted b1", "AllAdsCompleted", "BreakCompleted b2", "AllAdsCompleted"),
            seen.labels(),
        )
    }

    private fun TestScope.recording(ads: AdController): MutableList<AdEvent> {
        val seen = mutableListOf<AdEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { ads.events.collect { seen += it } }
        return seen
    }

    private fun List<AdEvent>.labels(): List<String> = map {
        when (it) {
            is AdEvent.BreakStarted -> "BreakStarted ${it.adBreak.id}"
            is AdEvent.AdStarted -> "AdStarted ${it.ad.id} ${it.indexInBreak + 1}/${it.countInBreak}"
            is AdEvent.Quartile -> "Quartile ${it.ad.id} ${it.quartile}"
            is AdEvent.AdCompleted -> "AdCompleted ${it.ad.id}"
            is AdEvent.BreakCompleted -> "BreakCompleted ${it.adBreak.id}"
            AdEvent.AllAdsCompleted -> "AllAdsCompleted"
        }
    }

    private fun List<AdEvent>.quartiles(): List<String> = labels().filter { it.startsWith("Quartile") }
}
