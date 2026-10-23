/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

/**
 * Where the media comes from. Sealed and source-agnostic so offline downloads slot in later as
 * just another [MediaSource] (produced by a future download manager), not a player-state change.
 */
public sealed interface MediaSource {
    /** A remote URL: progressive mp4, HLS (`.m3u8`) or DASH (`.mpd`). */
    public data class Url(
        /**
         * Absolute URL of the manifest (HLS/DASH) or media file. When [MediaItem.mimeType] is `null` the
         * format is guessed from this URL's extension, so **extension-less or signed URLs need an explicit
         * mime type**. A `file://` URL is accepted and recognised as a local file.
         */
        val url: String,
    ) : MediaSource

    /**
     * A SMIL document listing the manifests one asset is published as — an HLS and a DASH over the same
     * content, say. It is fetched (with [MediaItem.headers]) and one manifest is chosen by
     * [dev.nonbinary.outis.core.PlayerConfig.smilSelector] before any engine sees the item, which then plays
     * as a [Url] with the chosen rendition's format. That costs one round trip before the first frame.
     *
     * A [Url] whose path ends in `.smil` is treated as one when [MediaItem.mimeType] is `null`; for a SMIL
     * source [MediaItem.mimeType] is not read at all.
     */
    public data class Smil(
        /** Absolute URL of the SMIL document. */
        val url: String,
    ) : MediaSource

    /** A local file produced by the (future) download manager. Reserved for offline. */
    public data class LocalFile(
        /**
         * Absolute filesystem path — **not** a URL, so no `file://` prefix (use [Url] for that). On Web a
         * local file is always played progressively, never through the adaptive (Shaka) pipeline.
         */
        val path: String,
    ) : MediaSource
}
