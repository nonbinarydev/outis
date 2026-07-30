/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.thumbnails

import dev.nonbinary.outis.core.chapters.httpGetText

/**
 * One trickplay (seek-preview) thumbnail: the time span it covers and the crop rectangle within a sprite
 * sheet at [url]. Populated on [dev.nonbinary.outis.core.PlayerState.thumbnails] from a WebVTT sidecar
 * ([dev.nonbinary.outis.core.source.MediaItem.thumbnailsUrl]); the UI draws [url] cropped to
 * ([x], [y], [width], [height]) as a preview while scrubbing.
 */
data class ThumbnailCue(
    val startMs: Long,
    val endMs: Long,
    val url: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Parses a **WebVTT thumbnails** sidecar (the de-facto trickplay format: each cue's payload is
 * `sprite.jpg#xywh=x,y,w,h`). Sprite URLs are resolved relative to the sidecar's own [baseUrl]. Any parse
 * failure yields an empty list — thumbnails are optional and must never break playback.
 */
object VttThumbnails {
    fun parse(vtt: String, baseUrl: String): List<ThumbnailCue> {
        val cues = mutableListOf<ThumbnailCue>()
        for (block in vtt.replace("\r\n", "\n").replace("\r", "\n").split(Regex("\n[ \t]*\n"))) {
            val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val timingIdx = lines.indexOfFirst { "-->" in it }
            if (timingIdx < 0) continue // header / NOTE block
            val ends = lines[timingIdx].split("-->")
            val start = parseTimestamp(ends.getOrNull(0)) ?: continue
            val end = parseTimestamp(ends.getOrNull(1)?.trim()?.substringBefore(' ')) ?: continue
            val payload = lines.getOrNull(timingIdx + 1) ?: continue
            val spritePath = payload.substringBefore('#')
            val xywh = payload.substringAfter("#xywh=", "").split(",")
            if (spritePath.isEmpty() || xywh.size != XYWH_PARTS) continue
            val x = xywh[0].trim().toIntOrNull() ?: continue
            val y = xywh[1].trim().toIntOrNull() ?: continue
            val w = xywh[2].trim().toIntOrNull() ?: continue
            val h = xywh[3].trim().toIntOrNull() ?: continue
            cues += ThumbnailCue(start, end, resolveUrl(baseUrl, spritePath), x, y, w, h)
        }
        return cues.sortedBy { it.startMs }
    }

    private fun resolveUrl(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref
        else base.substringBeforeLast('/', "") + "/" + ref

    private fun parseTimestamp(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts = s.split(":")
        return try {
            val hours: Long
            val minutes: Long
            val secondsField: String
            when (parts.size) {
                THREE_PART -> { hours = parts[0].toLong(); minutes = parts[1].toLong(); secondsField = parts[2] }
                TWO_PART -> { hours = 0; minutes = parts[0].toLong(); secondsField = parts[1] }
                else -> return null
            }
            val seconds = secondsField.substringBefore('.').toLong()
            val millis = secondsField.substringAfter('.', "").padEnd(MILLIS_DIGITS, '0').take(MILLIS_DIGITS)
                .ifEmpty { "0" }.toLong()
            ((hours * SECS_PER_HOUR + minutes * SECS_PER_MIN + seconds) * MS_PER_SEC) + millis
        } catch (_: NumberFormatException) {
            null
        }
    }

    private const val XYWH_PARTS = 4
    private const val TWO_PART = 2
    private const val THREE_PART = 3
    private const val MILLIS_DIGITS = 3
    private const val MS_PER_SEC = 1000L
    private const val SECS_PER_MIN = 60L
    private const val SECS_PER_HOUR = 3600L
}

/** The cue whose span contains [positionMs] (or the nearest earlier one), or null when there are none. */
fun List<ThumbnailCue>.thumbnailAt(positionMs: Long): ThumbnailCue? =
    lastOrNull { positionMs >= it.startMs } ?: firstOrNull()

/** Load + parse a WebVTT thumbnails sidecar; empty on any failure. */
internal suspend fun loadThumbnails(url: String): List<ThumbnailCue> =
    httpGetText(url)?.let { runCatching { VttThumbnails.parse(it, url) }.getOrDefault(emptyList()) } ?: emptyList()
