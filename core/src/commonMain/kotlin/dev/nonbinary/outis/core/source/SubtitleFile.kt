/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import dev.nonbinary.outis.core.net.urlPath

/**
 * A subtitle file that ships beside the media rather than inside it, listed in [MediaItem.subtitles]. It
 * joins [dev.nonbinary.outis.core.PlayerState.textTracks] like a track the media carries, so it is
 * selected, switched off and pre-selected by [MediaItem.preferredTextLanguage] and
 * [MediaItem.captionsDefault] the same way.
 */
public data class SubtitleFile(
    /** Absolute URL of the file: `https://…`, or `file://…` for one on the device. */
    val url: String,
    /** BCP-47 language tag, e.g. `"en"`, which [MediaItem.preferredTextLanguage] is matched against. */
    val language: String? = null,
    /** What a track picker shows, e.g. `"English (SDH)"`; `null` leaves the picker to name it by [language]. */
    val label: String? = null,
    /** `null` => from [url]'s extension: `.srt` is [SubtitleFormat.SRT], anything else WebVTT. */
    val format: SubtitleFormat? = null,
)

/** The subtitle formats a [SubtitleFile] can be in. */
public enum class SubtitleFormat {
    /** WebVTT, `.vtt`. */
    WEBVTT,

    /** SubRip, `.srt`. */
    SRT,
}

/** [SubtitleFile.format], or the format [SubtitleFile.url]'s extension implies. */
internal val SubtitleFile.resolvedFormat: SubtitleFormat
    get() = format ?: when {
        urlPath(url).endsWith(".srt", ignoreCase = true) -> SubtitleFormat.SRT
        else -> SubtitleFormat.WEBVTT
    }

/** A WebVTT document with no cues: what a sidecar that cannot be read is shown as. */
internal const val EMPTY_WEBVTT = "WEBVTT\n"

/** Whether [text] opens with the `WEBVTT` line every WebVTT parser demands, as an error page does not. */
internal fun hasWebVttHeader(text: String): Boolean {
    val body = text.removePrefix("\uFEFF")
    return body.startsWith("WEBVTT") && body.getOrNull("WEBVTT".length).let { it == null || it in " \t\r\n" }
}

/**
 * [text], a file in [format], as WebVTT; `null` when it is not such a file: WebVTT without its header, or
 * SubRip without a single cue timing, as an error page served in a subtitle's place is.
 */
internal fun subtitleAsWebVtt(text: String, format: SubtitleFormat): String? = when (format) {
    SubtitleFormat.WEBVTT -> text.takeIf(::hasWebVttHeader)
    SubtitleFormat.SRT -> text.takeIf { it.lineSequence().any { line -> srtTiming.matches(line.trim()) } }
        ?.let(::srtToWebVtt)
}

private val srtTiming = Regex("""^\d+:\d{2}:\d{2}[,.]\d{3}\s*-->\s*\d+:\d{2}:\d{2}[,.]\d{3}.*$""")
private val srtTimestamp = Regex("""(\d+):(\d{2}:\d{2})[,.](\d{3})""")

/**
 * [srt] as WebVTT, for a player that reads only WebVTT. Cue numbers and text pass through; the timing line
 * gets WebVTT's decimal point and two-digit hours, and loses anything after the end time (SubRip's `X1:`
 * box coordinates), which WebVTT would misread as cue settings. A line of spaces between cues becomes
 * empty, because to WebVTT it would be cue text.
 */
internal fun srtToWebVtt(srt: String): String {
    val cues = srt.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim().lines()
        .joinToString("\n") { line ->
            when {
                srtTiming.matches(line.trim()) ->
                    srtTimestamp.findAll(line).take(2).joinToString(" --> ") { it.toVttTime() }

                line.isBlank() -> ""

                else -> line
            }
        }
    return "WEBVTT\n\n$cues\n"
}

private fun MatchResult.toVttTime(): String {
    val (hours, minutesAndSeconds, millis) = destructured
    return "${hours.padStart(2, '0')}:$minutesAndSeconds.$millis"
}
