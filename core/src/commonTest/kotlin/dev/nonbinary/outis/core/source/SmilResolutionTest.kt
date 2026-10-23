/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import dev.nonbinary.outis.core.PlayerError
import dev.nonbinary.outis.core.net.HttpFetch
import dev.nonbinary.outis.core.net.HttpResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SmilResolutionTest {

    private val smilUrl = "https://origin.example.com/v/a.smil"
    private val smil = """
        <smil><body><switch>
          <video src="https://cdn.example.net/a/master.m3u8"/>
          <video src="dash/a.mpd"/>
        </switch></body></smil>
    """.trimIndent()
    private val item = MediaItem(
        MediaSource.Smil(smilUrl),
        headers = mapOf("Authorization" to "Bearer t"),
        startPositionMs = 5_000,
    )
    private val pickDash = SmilSelector { renditions, _ -> renditions.firstOrNull { it.format == MimeType.DASH } }
    private val pickHls = SmilSelector { renditions, _ -> renditions.firstOrNull { it.format == MimeType.HLS } }

    @Test
    fun aRenditionOnTheDocumentsOriginKeepsTheHeaders() = runTest {
        val sent = mutableListOf<Map<String, String>>()

        val resolved = resolved(resolveSmil(item, smilUrl, pickDash, TIMEOUT_MS, serving(smil, sent = sent)))

        assertEquals(MediaSource.Url("https://origin.example.com/v/dash/a.mpd"), resolved.source)
        assertEquals(MimeType.DASH, resolved.mimeType)
        assertEquals(item.headers, resolved.headers)
        assertEquals(5_000, resolved.startPositionMs)
        assertEquals(listOf(item.headers), sent) // the document itself is fetched with them
    }

    @Test
    fun aRenditionOnAnotherOriginDropsTheHeaders() = runTest {
        val resolved = resolved(resolveSmil(item, smilUrl, pickHls, TIMEOUT_MS, serving(smil)))

        assertEquals(MediaSource.Url("https://cdn.example.net/a/master.m3u8"), resolved.source)
        assertEquals(MimeType.HLS, resolved.mimeType)
        assertTrue(resolved.headers.isEmpty())
    }

    @Test
    fun aRedirectedDocumentResolvesAgainstWhereItWasServed() = runTest {
        val fetch = serving(smil, finalUrl = "https://edge.example.org/x/a.smil")

        val resolved = resolved(resolveSmil(item, smilUrl, pickDash, TIMEOUT_MS, fetch))

        assertEquals(MediaSource.Url("https://edge.example.org/x/dash/a.mpd"), resolved.source)
        assertTrue(resolved.headers.isEmpty())
    }

    @Test
    fun anHttpErrorStatusIsASourceError() = runTest {
        val error = failed(resolveSmil(item, smilUrl, pickDash, TIMEOUT_MS) { _, _, _ -> HttpResult.Status(404) })

        assertEquals(PlayerError.Category.SOURCE, error.category)
        assertEquals("HTTP 404", error.code)
    }

    @Test
    fun noResponseIsANetworkError() = runTest {
        val timedOut: HttpFetch = { _, _, _ -> HttpResult.Failed("timed out") }

        val error = failed(resolveSmil(item, smilUrl, pickDash, TIMEOUT_MS, timedOut))

        assertEquals(PlayerError.Category.NETWORK, error.category)
        assertTrue(error.message.orEmpty().contains("timed out"))
    }

    @Test
    fun aDocumentThatIsNotSmilIsASourceError() = runTest {
        val error = failed(resolveSmil(item, smilUrl, pickDash, TIMEOUT_MS, serving("<html><body/></html>")))

        assertEquals(PlayerError.Category.SOURCE, error.category)
    }

    @Test
    fun noChosenRenditionIsASourceError() = runTest {
        val error = failed(resolveSmil(item, smilUrl, { _, _ -> null }, TIMEOUT_MS, serving(smil)))

        assertEquals(PlayerError.Category.SOURCE, error.category)
    }

    @Test
    fun theTimeoutReachesTheFetch() = runTest {
        var timeout = 0
        resolveSmil(item, smilUrl, pickDash, TIMEOUT_MS) { _, _, ms ->
            timeout = ms
            HttpResult.Status(500)
        }

        assertEquals(TIMEOUT_MS, timeout)
    }

    private fun serving(
        body: String,
        finalUrl: String = smilUrl,
        sent: MutableList<Map<String, String>>? = null,
    ): HttpFetch = { _, headers, _ ->
        sent?.add(headers)
        HttpResult.Ok(body, finalUrl)
    }

    private fun resolved(outcome: SmilOutcome): MediaItem = assertIs<SmilOutcome.Resolved>(outcome).item

    private fun failed(outcome: SmilOutcome): PlayerError = assertIs<SmilOutcome.Failed>(outcome).error

    private companion object {
        const val TIMEOUT_MS = 1_234
    }
}
