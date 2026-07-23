/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.catalogue

import dev.nonbinary.outis.core.source.MimeType
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/** Published by the Pages workflow. Served with `application/json` and `access-control-allow-origin: *`. */
private const val CATALOGUE_URL = "https://nonbinarydev.github.io/outis/catalogue.json"

/**
 * Big Buck Bunny as an adaptive HLS ladder: all `avc1`, no in-band parameter sets, no DRM, so every
 * engine can play it. If this does not play, the problem is the integration rather than the content.
 */
private const val FALLBACK_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

/** What the UI renders, so a fetch failure is a state to show rather than an exception to handle. */
sealed interface CatalogueState {
    data object Loading : CatalogueState
    data class Ready(val catalogue: Catalogue, val fromFallback: Boolean = false) : CatalogueState
}

class CatalogueRepository(private val client: HttpClient) {

    /**
     * Never throws. Any failure — offline, DNS, a malformed body, an unsupported schema version —
     * degrades to [fallback] so the demo still plays something. A blank screen would say nothing about
     * whether the *player* works, which is the only thing this app exists to show.
     */
    suspend fun load(): CatalogueState.Ready = try {
        val catalogue: Catalogue = client.get(CATALOGUE_URL).body()
        if (catalogue.version == Catalogue.SUPPORTED_VERSION && catalogue.rails.isNotEmpty()) {
            CatalogueState.Ready(catalogue)
        } else {
            CatalogueState.Ready(fallback(), fromFallback = true)
        }
    } catch (_: Exception) {
        // Deliberately broad: Ktor surfaces a different exception type per engine, and every one of them
        // means the same thing here.
        CatalogueState.Ready(fallback(), fromFallback = true)
    }

    private fun fallback() = Catalogue(
        version = Catalogue.SUPPORTED_VERSION,
        rails = listOf(
            Rail(
                id = "fallback",
                title = "Built-in stream",
                note = "The published catalogue could not be loaded.",
                items = listOf(
                    CatalogueItem(
                        id = "fallback-bbb-hls",
                        title = "Big Buck Bunny",
                        label = "HLS · adaptive ladder · no DRM",
                        url = FALLBACK_URL,
                        mimeType = MimeType.HLS,
                        tint = "#2E3A59",
                        tags = listOf("VOD", "HLS"),
                    ),
                ),
            ),
        ),
    )
}
