/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

/** One cue of a sidecar subtitle file: [text] shows from [startMs] until, not including, [endMs]. */
internal class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * The cues of [webVtt], in start order, as plain text: markup such as `<i>` or `<v Speaker>` is dropped and
 * the common character references decoded. Cue settings, regions and styles are ignored, and a block whose
 * timing does not parse is skipped.
 */
internal fun parseWebVttCues(webVtt: String): List<SubtitleCue> =
    webVtt.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        .split(blankLines)
        .drop(1) // the WEBVTT header block
        .mapNotNull { cueOf(it.lines()) }
        .sortedBy { it.startMs }

/** The text of each cue in [cues] showing at [positionMs], in start order. */
internal fun cueTextAt(cues: List<SubtitleCue>, positionMs: Long): List<String> =
    cues.filter { positionMs >= it.startMs && positionMs < it.endMs }.map { it.text }

private val blankLines = Regex("""\n\s*\n""")
private val timingLine = Regex("""^\s*(\S+)\s+-->\s+(\S+)""")
private val timestamp = Regex("""(?:(\d+):)?([0-5]\d):([0-5]\d\.\d{3})""")
private val markup = Regex("<[^>]*>")

/** A cue from a block's [lines]: an optional identifier, the timing line, then the text. */
private fun cueOf(lines: List<String>): SubtitleCue? {
    val timingAt = lines.indexOfFirst { "-->" in it }.takeIf { it in 0..1 } ?: return null
    val timing = timingLine.find(lines[timingAt]) ?: return null
    val start = millisOf(timing.groupValues[1]) ?: return null
    val end = millisOf(timing.groupValues[2]) ?: return null
    val text = lines.drop(timingAt + 1).joinToString("\n") { it.replace(markup, "") }.decodeReferences().trim()
    return SubtitleCue(start, end, text).takeIf { end > start && text.isNotEmpty() }
}

/** `hh:mm:ss.ttt`, or `mm:ss.ttt`, in milliseconds. */
private fun millisOf(stamp: String): Long? {
    val (hours, minutes, seconds) = timestamp.matchEntire(stamp)?.destructured ?: return null
    val wholeSeconds = (hours.toLongOrNull() ?: 0) * SECONDS_PER_HOUR + minutes.toLong() * SECONDS_PER_MINUTE +
        seconds.substringBefore('.').toLong()
    return wholeSeconds * MILLIS_PER_SECOND + seconds.substringAfter('.').toLong()
}

private fun String.decodeReferences(): String = replace("&lt;", "<").replace("&gt;", ">")
    .replace("&nbsp;", " ").replace("&lrm;", "‎").replace("&rlm;", "‏")
    .replace("&amp;", "&") // last, so an escaped reference such as `&amp;lt;` stays literal

private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_MINUTE = 60
private const val MILLIS_PER_SECOND = 1000
