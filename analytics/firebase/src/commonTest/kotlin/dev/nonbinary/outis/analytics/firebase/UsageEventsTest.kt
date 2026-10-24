/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.firebase

import dev.nonbinary.outis.core.PlayerError
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.PlayerState
import dev.nonbinary.outis.core.analytics.PlaybackMetadata
import dev.nonbinary.outis.core.analytics.StreamType
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageEventsTest {
    private val item = MediaItem(
        MediaSource.Url("https://example.com/master.m3u8"),
        analytics = PlaybackMetadata(
            videoId = "bbb",
            title = "Big Buck Bunny",
            series = "Blender",
            durationMs = 634_566,
        ),
    )
    private val state = PlayerState(mediaItem = item)

    private val playing = PlayerEvent.IsPlayingChanged(isPlaying = true, positionMs = 0, elapsedRealtimeMs = 0)
    private val paused = PlayerEvent.IsPlayingChanged(isPlaying = false, positionMs = 0, elapsedRealtimeMs = 0)

    @Test
    fun playbackStartingIsVideoStartWithTheItemsMetadata() {
        val event = usageEventOf(playing, state)

        assertEquals("video_start", event?.name)
        assertEquals(
            mapOf(
                "video_provider" to "outis",
                "video_title" to "Big Buck Bunny",
                "video_id" to "bbb",
                "video_series" to "Blender",
                "video_duration" to 634L,
                "video_current_time" to 0L,
                "stream_type" to "vod",
            ),
            event?.params,
        )
    }

    @Test
    fun theEndIsVideoComplete() {
        val event = usageEventOf(PlayerEvent.Ended(positionMs = 634_566, elapsedRealtimeMs = 0), state)

        assertEquals("video_complete", event?.name)
        assertEquals(634L, event?.params?.get("video_current_time"))
    }

    @Test
    fun aFatalErrorCarriesItsCategoryAndCode() {
        val error = PlayerError(PlayerError.Category.NETWORK, code = "1002")
        val event = usageEventOf(PlayerEvent.FatalError(error, positionMs = 12_000, elapsedRealtimeMs = 0), state)

        assertEquals("video_error", event?.name)
        assertEquals("NETWORK", event?.params?.get("error_category"))
        assertEquals("1002", event?.params?.get("error_code"))
    }

    @Test
    fun liveComesFromTheMetadataBeforeThePlayer() {
        val live = state.copy(mediaItem = item.copy(analytics = item.analytics?.copy(streamType = StreamType.LIVE)))
        val event = usageEventOf(playing, live)

        assertEquals("live", event?.params?.get("stream_type"))
    }

    @Test
    fun pausesAndQualityOfServiceEventsAreNotUsage() {
        assertNull(usageEventOf(paused, state))
        assertNull(usageEventOf(PlayerEvent.FirstFrameRendered(positionMs = 0, elapsedRealtimeMs = 0), state))
        assertNull(usageEventOf(PlayerEvent.BufferingStarted(positionMs = 0, elapsedRealtimeMs = 0), state))
    }

    @Test
    fun aPlayThroughStartsOnceHoweverOftenPlaybackResumes() {
        val usage = UsageTracker()

        assertEquals("video_start", usage.eventFor(playing, state)?.name)
        assertNull(usage.eventFor(paused, state))
        assertNull(usage.eventFor(playing, state))

        usage.eventFor(PlayerEvent.MediaItemTransition(item, positionMs = 0, elapsedRealtimeMs = 0), state)
        assertEquals("video_start", usage.eventFor(playing, state)?.name)

        val ended = PlayerEvent.Ended(positionMs = 634_566, elapsedRealtimeMs = 0)
        assertEquals("video_complete", usage.eventFor(ended, state)?.name)
        assertEquals("video_start", usage.eventFor(playing, state)?.name)
    }

    @Test
    fun namesAndValuesAreCutToGa4Limits() {
        assertEquals("select_content", ga4Name("select_content"))
        assertEquals("item_selected_", ga4Name("item selected!"))
        assertEquals("e_3d_view", ga4Name("3d-view"))
        assertEquals(40, ga4Name("a".repeat(60)).length)
        assertEquals(100, (ga4Params(mapOf("title" to "x".repeat(150)))["title"] as String).length)
    }
}
