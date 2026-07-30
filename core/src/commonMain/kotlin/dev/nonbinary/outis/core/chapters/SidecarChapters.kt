/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

/**
 * Chapters from a **WebVTT sidecar** ([dev.nonbinary.outis.core.source.MediaItem.chaptersUrl]) — the
 * stream-friendly path, since a `.vtt` works for HLS/DASH/progressive alike and on every engine, unlike
 * the embedded-container parsing ([ChapterExtractor]) which needs a local file. Each cue becomes a
 * [Chapter]: its start/end are the cue timings and its title is the cue payload (falling back to the cue
 * identifier). Any parse failure yields an empty list — chapters must never break playback.
 */
object VttChapters {
    fun parse(vtt: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        // Cues are blank-line-separated blocks; the first block is the "WEBVTT" header (no timing line).
        for (block in vtt.replace("\r\n", "\n").replace("\r", "\n").split(Regex("\n[ \t]*\n"))) {
            val lines = block.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val timingIdx = lines.indexOfFirst { "-->" in it }
            if (timingIdx < 0) continue // header, NOTE, or STYLE block — no cue timing
            val ends = lines[timingIdx].split("-->")
            val start = parseTimestamp(ends.getOrNull(0)) ?: continue
            val end = parseTimestamp(ends.getOrNull(1)?.trim()?.substringBefore(' '))
            // Payload lines are the title; if there are none, the cue identifier line above the timing is.
            val title = lines.drop(timingIdx + 1).joinToString(" ").ifBlank { null }
                ?: lines.getOrNull(timingIdx - 1)?.takeIf { "-->" !in it }
            chapters += Chapter(startMs = start, title = title, endMs = end)
        }
        return chapters.sortedBy { it.startMs }
    }

    /** `HH:MM:SS.mmm` or `MM:SS.mmm` → milliseconds; null if it isn't a WebVTT timestamp. */
    private fun parseTimestamp(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val parts = s.split(":")
        return try {
            val hours: Long
            val minutes: Long
            val secondsField: String
            when (parts.size) {
                3 -> { hours = parts[0].toLong(); minutes = parts[1].toLong(); secondsField = parts[2] }
                2 -> { hours = 0; minutes = parts[0].toLong(); secondsField = parts[1] }
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

    private const val MILLIS_DIGITS = 3
    private const val MS_PER_SEC = 1000L
    private const val SECS_PER_MIN = 60L
    private const val SECS_PER_HOUR = 3600L
}

/** Fetch a small text resource (the WebVTT sidecar). Returns null on any failure — chapters are optional. */
internal expect suspend fun httpGetText(url: String): String?

/** Load + parse a WebVTT chapters sidecar; empty on any failure. */
internal suspend fun loadSidecarChapters(url: String): List<Chapter> =
    httpGetText(url)?.let { runCatching { VttChapters.parse(it) }.getOrDefault(emptyList()) } ?: emptyList()
