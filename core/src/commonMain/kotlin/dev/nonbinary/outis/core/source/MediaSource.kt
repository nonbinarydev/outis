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
sealed interface MediaSource {
    /** A remote URL: progressive mp4, HLS (`.m3u8`) or DASH (`.mpd`). */
    data class Url(
        /**
         * Absolute URL of the manifest (HLS/DASH) or media file. When [MediaItem.mimeType] is `null` the
         * format is guessed from this URL's extension, so **extension-less or signed URLs need an explicit
         * mime type**. A `file://` URL is accepted and recognised as a local file.
         */
        val url: String,
    ) : MediaSource

    /** A local file produced by the (future) download manager. Reserved for offline. */
    data class LocalFile(
        /**
         * Absolute filesystem path — **not** a URL, so no `file://` prefix (use [Url] for that). On Web a
         * local file is always played progressively, never through the adaptive (Shaka) pipeline.
         */
        val path: String,
    ) : MediaSource
}
