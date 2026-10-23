/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import dev.nonbinary.outis.core.ExperimentalPlayerApi
import dev.nonbinary.outis.core.net.urlPath

/**
 * One media element of a SMIL document — a manifest the asset is published as — as a [SmilSelector] sees
 * it. Vendors disagree on where a rendition's format is signalled, so [attributes] and [params] carry
 * everything the document said, understood or not.
 */
@ExperimentalPlayerApi
data class SmilRendition(
    /**
     * Absolute URL, from `src` resolved against the document's base: its own URL, or a `<meta>` base,
     * Wowza's `httpBase`, or an `xml:base` where the document sets one.
     */
    val url: String,
    /** The format, from the `type` attribute or else [url]'s extension; `null` when neither says. */
    val format: MimeType?,
    /** The element's local name: `video`, `audio`, `ref` or `media`. */
    val element: String,
    /** Every attribute on the element as written, `src` and `type` included. */
    val attributes: Map<String, String>,
    /** The element's `<param name="…" value="…">` children, by name. */
    val params: Map<String, String>,
)

/**
 * Chooses which rendition of a SMIL document to play. Set one as
 * [dev.nonbinary.outis.core.PlayerConfig.smilSelector]; the default is [Platform]. Called once per load of a
 * SMIL item, on the main thread, so keep it a quick, pure function of its arguments.
 */
fun interface SmilSelector {
    /**
     * The rendition to play from [renditions] (document order), or `null` when none will do — the load then
     * fails with [dev.nonbinary.outis.core.PlayerError.Category.SOURCE]. [item] is the item as it was set.
     */
    fun select(renditions: List<SmilRendition>, item: MediaItem): SmilRendition?

    companion object {
        /**
         * The engine's own preference: the first DASH or HLS rendition it can play — DASH before HLS on Android
         * and the web, HLS alone on iOS — with the format the item's DRM scheme pairs with tried first: HLS for
         * FairPlay, DASH for Widevine and PlayReady. `video`, `ref` and `media` elements win over `audio` ones.
         * Progressive renditions are never chosen: one rung of a ladder would lose adaptive bitrate.
         */
        val Platform: SmilSelector = SmilSelector { renditions, item ->
            selectManifest(renditions, item, platformManifestFormats)
        }
    }
}

/** The adaptive formats this platform's engine plays, in its order of preference. */
internal expect val platformManifestFormats: List<MimeType>

/** [SmilSelector.Platform]'s choice, given the formats the engine plays in its order of preference. */
internal fun selectManifest(
    renditions: List<SmilRendition>,
    item: MediaItem,
    playable: List<MimeType>,
): SmilRendition? {
    val paired = when (item.drmConfig?.scheme) {
        DrmScheme.FAIRPLAY -> MimeType.HLS
        DrmScheme.WIDEVINE, DrmScheme.PLAYREADY -> MimeType.DASH
        DrmScheme.CLEARKEY, null -> null
    }
    val order = if (paired != null && paired in playable) listOf(paired) + (playable - paired) else playable
    val candidates = renditions.sortedBy { it.element == "audio" }
    return order.firstNotNullOfOrNull { format -> candidates.firstOrNull { it.format == format } }
}

/**
 * The SMIL document's URL when this item is one — a [MediaSource.Smil], or a `.smil` [MediaSource.Url] with
 * no [MediaItem.mimeType] — else `null`.
 */
internal fun MediaItem.smilUrl(): String? = when (val s = source) {
    is MediaSource.Smil -> s.url
    is MediaSource.Url -> s.url.takeIf { mimeType == null && urlPath(it).endsWith(".smil", ignoreCase = true) }
    is MediaSource.LocalFile -> null
}
