/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.catalogue

import dev.nonbinary.outis.core.ads.Ad
import dev.nonbinary.outis.core.ads.AdBreak
import dev.nonbinary.outis.core.ads.AdConfig
import dev.nonbinary.outis.core.analytics.PlaybackMetadata
import dev.nonbinary.outis.core.chapters.Chapter
import dev.nonbinary.outis.core.source.DrmConfig
import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.core.source.SubtitleFile
import dev.nonbinary.outis.core.source.SubtitleFormat
import dev.nonbinary.outis.sample.CUSTOM_STREAM_ID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The stream list fetched at runtime from GitHub Pages. Schema contract lives in `sample/README.md`;
 * [version] is the break signal — a catalogue declaring anything other than [SUPPORTED_VERSION] is
 * rejected rather than parsed on a hope.
 */
@Serializable
data class Catalogue(
    val version: Int,
    val updated: String? = null,
    /** Poster name to image URL. Items reference posters by name so several can share one image. */
    val posters: Map<String, String> = emptyMap(),
    val rails: List<Rail> = emptyList(),
) {
    companion object {
        const val SUPPORTED_VERSION = 1
    }
}

@Serializable
data class Rail(
    val id: String,
    val title: String,
    val note: String? = null,
    val items: List<CatalogueItem> = emptyList(),
)

@Serializable
data class CatalogueItem(
    val id: String,
    val title: String,
    /** Longer one-line description; [title] is the card caption. */
    val label: String? = null,
    val url: String,
    val mimeType: MimeType? = null,
    val poster: String? = null,
    /** Card background where there is no poster, as `#RRGGBB`. */
    val tint: String? = null,
    val tags: List<String> = emptyList(),
    val note: String? = null,
    val drm: CatalogueDrm? = null,
    val ads: CatalogueAds? = null,
    @SerialName("scte35MasterUrl") val scte35MasterUrl: String? = null,
    /** WebVTT chapters sidecar URL — populates chapter markers (works for streamed sources too). */
    val chaptersUrl: String? = null,
    val thumbnailsUrl: String? = null,
    /** Chapters declared inline. They win over [chaptersUrl], which older sample builds still read instead. */
    val chapters: List<CatalogueChapter>? = null,
    /** Subtitle files beside the stream; the first is the default. */
    val subtitles: List<CatalogueSubtitle> = emptyList(),
)

@Serializable
data class CatalogueChapter(val startMs: Long, val title: String? = null, val endMs: Long? = null)

@Serializable
data class CatalogueSubtitle(
    val url: String,
    val language: String? = null,
    val label: String? = null,
    /** `null` => from [url]'s extension. */
    val format: SubtitleFormat? = null,
)

@Serializable
data class CatalogueDrm(
    val scheme: DrmScheme,
    /** Null for CLEARKEY, which carries its keys inline in [keys] rather than fetching them from a server. */
    val licenseServerUrl: String? = null,
    /** FairPlay only, and mandatory there — without the FPS application certificate no key session starts. */
    val certificateUrl: String? = null,
    /** CLEARKEY only: content keys as hex `keyId` → hex `key`. */
    val keys: Map<String, String>? = null,
)

@Serializable
data class CatalogueAds(
    val type: String,
    val adTagUri: String? = null,
    /** Server-side only: the stitched breaks, at positions on the stream's own timeline. */
    val breaks: List<CatalogueAdBreak> = emptyList(),
)

@Serializable
data class CatalogueAdBreak(val id: String, val startMs: Long, val ads: List<CatalogueAd> = emptyList())

@Serializable
data class CatalogueAd(val id: String, val durationMs: Long, val title: String? = null, val skipOffsetMs: Long? = null)

/**
 * Translates a catalogue entry into the SDK's own model. [series] is the rail the item was chosen from,
 * carried into QoS metadata for grouping — the item itself does not know its rail.
 *
 * Server-side breaks ride along as [AdConfig.ServerSide], which no engine reads: the origin has already
 * stitched the ads, and the sample's own `AdController` takes the breaks back out.
 */
// Starts with sound; the web engine falls back to muted only if the browser blocks unmuted autoplay,
// and native players have no such restriction — so no platform is needlessly silenced.
fun CatalogueItem.toMediaItem(startMuted: Boolean = false, series: String? = null): MediaItem = MediaItem(
    MediaSource.Url(url),
    mimeType = mimeType,
    startMuted = startMuted,
    drmConfig = drm?.let {
        DrmConfig(
            scheme = it.scheme,
            licenseServerUrl = it.licenseServerUrl,
            certificateUrl = it.certificateUrl,
            clearKeys = it.keys.orEmpty(),
        )
    },
    chaptersUrl = chaptersUrl,
    chapters = chapters?.map { Chapter(startMs = it.startMs, title = it.title, endMs = it.endMs) },
    thumbnailsUrl = thumbnailsUrl,
    subtitles = subtitles.map { SubtitleFile(it.url, language = it.language, label = it.label, format = it.format) },
    adConfig = ads?.toAdConfig(),
    // The descriptive [label] is the human name (the terse [title] is just the card caption, e.g. "AVC").
    analytics = PlaybackMetadata(videoId = analyticsVideoId(), title = label ?: title, series = series),
)

private fun CatalogueAds.toAdConfig(): AdConfig? = when (type) {
    "clientSide" -> adTagUri?.let { AdConfig.ClientSide(adTagUri = it) }
    "serverSide" -> AdConfig.ServerSide(
        breaks.map { brk ->
            AdBreak(
                id = brk.id,
                startMs = brk.startMs,
                ads = brk.ads.map {
                    Ad(id = it.id, durationMs = it.durationMs, title = it.title, skipOffsetMs = it.skipOffsetMs)
                },
            )
        },
    )
    else -> null
}

/**
 * A stable QoS id for the stream. Catalogue items key on their own [id]; custom streams all share
 * [CUSTOM_STREAM_ID], which would collapse every ad-hoc URL into one Mux "video", so those key on the
 * URL instead — different pastes stay distinct without leaking the full (possibly signed) URL as the id.
 */
private fun CatalogueItem.analyticsVideoId(): String =
    if (id == CUSTOM_STREAM_ID) "custom-${url.trim().hashCode()}" else id
