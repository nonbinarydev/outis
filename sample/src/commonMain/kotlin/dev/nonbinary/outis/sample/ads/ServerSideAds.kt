/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.ads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.ads.Ad
import dev.nonbinary.outis.core.ads.AdBreak
import dev.nonbinary.outis.core.ads.AdController
import dev.nonbinary.outis.core.ads.HlsAdCue
import dev.nonbinary.outis.core.ads.parseHlsAdCues
import dev.nonbinary.outis.core.source.serverSideBreaks
import dev.nonbinary.outis.sample.catalogue.CatalogueItem
import dev.nonbinary.outis.sample.catalogue.toMediaItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.seconds

/** About one HLS segment: often enough to catch an avail before it plays out. */
private val SCTE35_POLL_INTERVAL = 6.seconds

/**
 * The [AdController] for [item]'s server-side ads, or `null` when it has none: the breaks the catalogue
 * declares, plus each avail a live SCTE-35 entry's playlist announces. The playhead drives it here; the
 * player's overlay only reads it.
 */
@Composable
fun rememberServerSideAds(player: VideoPlayer, item: CatalogueItem?): AdController? {
    val client = koinInject<HttpClient>()
    val mediaItem = remember(item) { item?.toMediaItem() }
    val ads = remember(item) {
        val breaks = mediaItem?.serverSideBreaks().orEmpty()
        if (breaks.isNotEmpty() || item?.scte35MasterUrl != null) AdController(breaks) else null
    }
    if (ads != null && mediaItem != null) {
        LaunchedEffect(ads) {
            // Until setMediaItem lands, positions belong to the previous item, and would mark this one's
            // pre-roll as already passed.
            launch {
                player.state
                    .filter { it.mediaItem?.source == mediaItem.source }
                    .map { it.positionMs }
                    .distinctUntilChanged()
                    .collect(ads::onPosition)
            }
            launch {
                player.events.collect { if (it is PlayerEvent.Ended) ads.onEnded() }
            }
            item?.scte35MasterUrl?.let { url -> launch { pollScte35(client, url, player, ads) } }
        }
    }
    return ads
}

/**
 * Adds each SCTE-35 avail that [masterUrl]'s live playlist announces to [ads], as a break starting where
 * the playhead is when the avail first appears. That runs early by the player's distance from the live
 * edge, which a demo can live with.
 */
private suspend fun pollScte35(client: HttpClient, masterUrl: String, player: VideoPlayer, ads: AdController) {
    val avails = Scte35Avails(client, masterUrl)
    while (true) {
        avails.fresh().forEach { cue ->
            val ad = Ad(id = cue.id, durationMs = cue.durationMs, title = "SCTE-35 avail")
            ads.addBreak(AdBreak(id = cue.id, startMs = player.state.value.positionMs, ads = listOf(ad)))
        }
        delay(SCTE35_POLL_INTERVAL)
    }
}

/** A live playlist's SCTE-35 avails, as they appear. */
private class Scte35Avails(private val client: HttpClient, private val masterUrl: String) {
    private var mediaUrl: String? = null
    private var seen: MutableSet<String>? = null

    /**
     * Avails listed since the last read. The first read returns none: what is already listed has played.
     * A failed read also returns none, and the next one tries again.
     */
    suspend fun fresh(): List<HlsAdCue> = try {
        val playlistUrl = mediaUrl ?: firstVariant(client, masterUrl).also { mediaUrl = it }
        val cues = parseHlsAdCues(client.get(playlistUrl).bodyAsText()).filter { it.durationMs > 0 }
        val known = seen
        if (known == null) {
            seen = cues.mapTo(mutableSetOf()) { it.id }
            emptyList()
        } else {
            cues.filter { known.add(it.id) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Ktor surfaces a different exception type per engine; any of them means "try again later".
        emptyList()
    }
}

/** The first variant a master playlist lists, resolved against it; a media playlist is its own variant. */
private suspend fun firstVariant(client: HttpClient, masterUrl: String): String {
    val lines = client.get(masterUrl).bodyAsText().lines().map { it.trim() }
    val streamInf = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
    if (streamInf < 0) return masterUrl
    val uri = lines.drop(streamInf + 1).first { it.isNotEmpty() && !it.startsWith("#") }
    return when {
        "://" in uri -> uri
        uri.startsWith("/") -> masterUrl.substringBefore("://") + "://" +
            masterUrl.substringAfter("://").substringBefore('/') + uri
        else -> masterUrl.substringBefore('?').substringBeforeLast('/') + "/" + uri
    }
}
