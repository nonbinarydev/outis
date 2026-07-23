/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.catalogue

import dev.nonbinary.outis.core.ads.AdConfig
import dev.nonbinary.outis.core.source.DrmConfig
import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
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
)

@Serializable
data class CatalogueDrm(
    val scheme: DrmScheme,
    val licenseServerUrl: String,
    /** FairPlay only, and mandatory there — without the FPS application certificate no key session starts. */
    val certificateUrl: String? = null,
)

@Serializable
data class CatalogueAds(val type: String, val adTagUri: String? = null)

/**
 * Translates a catalogue entry into the SDK's own model.
 *
 * Only client-side ads are mapped. Server-side entries carry no `adTagUri` and are stitched by the
 * origin, so there is nothing for [AdConfig] to describe.
 */
fun CatalogueItem.toMediaItem(startMuted: Boolean = true): MediaItem = MediaItem(
    MediaSource.Url(url),
    mimeType = mimeType,
    startMuted = startMuted,
    drmConfig = drm?.let {
        DrmConfig(
            scheme = it.scheme,
            licenseServerUrl = it.licenseServerUrl,
            certificateUrl = it.certificateUrl,
        )
    },
    adConfig = ads?.takeIf { it.type == "clientSide" }?.adTagUri?.let { AdConfig.ClientSide(adTagUri = it) },
)
