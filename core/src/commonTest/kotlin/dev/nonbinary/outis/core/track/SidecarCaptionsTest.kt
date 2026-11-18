/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.track

import dev.nonbinary.outis.core.net.HttpFetch
import dev.nonbinary.outis.core.net.HttpResult
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.SubtitleFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SidecarCaptionsTest {
    private val english = "WEBVTT\n\n00:01.000 --> 00:02.000\nHello\n"
    private val spanish = "1\n00:00:01,000 --> 00:00:02,000\nHola\n"
    private val item = MediaItem(
        MediaSource.Url("https://cdn.example/film.mp4"),
        headers = mapOf("Authorization" to "Bearer t"),
        subtitles = listOf(
            SubtitleFile("https://cdn.example/en.vtt", language = "en", label = "English"),
            SubtitleFile("https://cdn.example/es.srt", language = "es"),
        ),
    )

    /** A network that answers each URL when the test completes it, and records what was asked. */
    private class FakeNetwork {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val responses = mutableMapOf<String, CompletableDeferred<HttpResult>>()
        val fetch: HttpFetch = { url, headers, _ ->
            requests += url to headers
            responses.getOrPut(url) { CompletableDeferred() }.await()
        }

        fun answer(url: String, body: String) {
            responses.getOrPut(url) { CompletableDeferred() }.complete(HttpResult.Ok(body, url))
        }
    }

    @Test
    fun theSidecarsAreListedInOrderTheFirstTheDefaultAndNothingIsFetched() = runTest {
        val network = FakeNetwork()
        val captions = SidecarCaptions(this, network.fetch) {}

        captions.attach(item)

        assertEquals(
            listOf(
                MediaTrack("SIDECAR:0", TrackType.TEXT, "English", "en", isDefault = true),
                MediaTrack("SIDECAR:1", TrackType.TEXT, null, "es"),
            ),
            captions.tracks,
        )
        assertNull(captions.shownId)
        assertEquals(emptyList(), network.requests)
    }

    @Test
    fun aSidecarIsFetchedOnceWithTheItemsHeadersWhenFirstShown() = runTest {
        val network = FakeNetwork()
        var loaded = 0
        val captions = SidecarCaptions(this, network.fetch) { loaded++ }
        captions.attach(item)

        captions.show("SIDECAR:1")
        runCurrent()
        assertEquals(emptyList(), captions.cuesAt(1500), "no cues while it loads")
        network.answer("https://cdn.example/es.srt", spanish)
        runCurrent()

        assertEquals(listOf("https://cdn.example/es.srt" to mapOf("Authorization" to "Bearer t")), network.requests)
        assertEquals(1, loaded)
        assertEquals(listOf("Hola"), captions.cuesAt(1500))
        assertEquals(listOf(false, true), captions.tracks.map { it.isSelected })

        captions.show(null)
        assertEquals(emptyList(), captions.cuesAt(1500))
        captions.show("SIDECAR:1")
        runCurrent()
        assertEquals(1, network.requests.size, "fetched once")
        assertEquals(listOf("Hola"), captions.cuesAt(1500))
    }

    @Test
    fun aSidecarThatCannotBeFetchedHasNoCues() = runTest {
        val failing: HttpFetch = { _, _, _ -> HttpResult.Status(404) }
        var loaded = 0
        val captions = SidecarCaptions(this, failing) { loaded++ }
        captions.attach(item)

        captions.show("SIDECAR:0")
        runCurrent()

        assertEquals(1, loaded)
        assertEquals(emptyList(), captions.cuesAt(1500))
        assertEquals("SIDECAR:0", captions.shownId)
    }

    @Test
    fun onlyTheSidecarStillShowingReportsItsCues() = runTest {
        val network = FakeNetwork()
        var loaded = 0
        val captions = SidecarCaptions(this, network.fetch) { loaded++ }
        captions.attach(item)

        captions.show("SIDECAR:0")
        runCurrent()
        captions.show("SIDECAR:1")
        runCurrent()
        network.answer("https://cdn.example/en.vtt", english)
        runCurrent()
        assertEquals(0, loaded)
        assertEquals(emptyList(), captions.cuesAt(1500))

        network.answer("https://cdn.example/es.srt", spanish)
        runCurrent()
        assertEquals(1, loaded)
        assertEquals(listOf("Hola"), captions.cuesAt(1500))
    }

    @Test
    fun aNewItemDropsTheLastOnesSidecarsAndTheirFetches() = runTest {
        val network = FakeNetwork()
        var loaded = 0
        val captions = SidecarCaptions(this, network.fetch) { loaded++ }
        captions.attach(item)
        captions.show("SIDECAR:0")
        runCurrent()

        captions.attach(MediaItem(MediaSource.Url("https://cdn.example/other.mp4")))
        network.answer("https://cdn.example/en.vtt", english)
        runCurrent()

        assertEquals(0, loaded)
        assertEquals(emptyList(), captions.tracks)
        assertNull(captions.shownId)
        captions.show("SIDECAR:0")
        assertNull(captions.shownId, "not a track of the new item")
    }
}
