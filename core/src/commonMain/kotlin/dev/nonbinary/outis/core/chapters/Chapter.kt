/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

/**
 * A chapter marker embedded in the media container — MP4/M4V (QuickTime `chap` text-track or Nero `chpl`)
 * or Matroska `Chapters`. Surfaced on [dev.nonbinary.outis.core.PlayerState.chapters], sorted by [startMs].
 *
 * [endMs] is the next chapter's [startMs] (or the media duration for the last chapter), filled in when the
 * boundaries are known; `null` when only start points were available.
 */
data class Chapter(
    /** Start of the chapter on the content timeline, in milliseconds. */
    val startMs: Long,
    /** Chapter title, if the container carried one. */
    val title: String? = null,
    /** Exclusive end on the timeline, in ms — derived (next start / duration), or `null` if unknown. */
    val endMs: Long? = null,
    /**
     * Raw bytes of the chapter's preview image (usually JPEG) from an MP4 chapter **image** track — the
     * artwork tools like Subler write alongside the titles. `null` unless thumbnail extraction was requested
     * ([dev.nonbinary.outis.core.source.MediaItem.chapterThumbnails]) **and** the file carries one; Matroska
     * and title-only MP4s always leave this `null`. The UI decodes the bytes (no common image type).
     */
    val thumbnail: ByteArray? = null,
) {
    // ByteArray needs content-based equality/hash so a data class holding one behaves sanely in state/lists.
    /**
     * Value equality over all four properties, comparing [thumbnail] by **content** rather than by
     * reference — without this, two chapters decoded from the same file would compare unequal and
     * every state emission would look like a change.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chapter) return false
        if (startMs != other.startMs || title != other.title || endMs != other.endMs) return false
        return when {
            thumbnail == null -> other.thumbnail == null
            other.thumbnail == null -> false
            else -> thumbnail.contentEquals(other.thumbnail)
        }
    }

    /** Consistent with [equals]: hashes [thumbnail] by content, so it is **O(size of the image)**. */
    override fun hashCode(): Int {
        var result = startMs.hashCode()
        result = 31 * result + (title?.hashCode() ?: 0)
        result = 31 * result + (endMs?.hashCode() ?: 0)
        result = 31 * result + (thumbnail?.contentHashCode() ?: 0)
        return result
    }
}
