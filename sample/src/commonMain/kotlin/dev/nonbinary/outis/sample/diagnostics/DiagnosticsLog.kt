/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.diagnostics

import dev.nonbinary.outis.core.PlaybackState
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.RecoveryReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Severity, so the panel can colour a stall or a failure differently from routine lifecycle. */
enum class DiagnosticLevel { Info, Good, Warn, Error }

/**
 * One line of the timeline. [atMs] is relative to the first entry, so a reader sees "T+12.4s" rather
 * than an absolute clock — what matters for "it stopped after a while" is the *interval* between
 * events, not the time of day.
 */
data class DiagnosticEntry(
    val atMs: Long,
    val level: DiagnosticLevel,
    val label: String,
    val detail: String? = null,
)

/**
 * A rolling timeline of what the player did, for diagnosing a stream that fails to load or stops
 * part-way. It only observes — every line comes from the SDK's own `player.events`, so nothing here
 * changes playback.
 *
 * The two failure modes it is meant to separate:
 * - **Shonky stream** — repeated [PlayerEvent.BufferingStarted] with no matching [PlayerEvent.BufferingEnded]
 *   or [PlayerEvent.PlaybackRecovered]: the source stalled and never refilled.
 * - **SDK issue** — an unexpected [PlayerEvent.FatalError] category, or a lifecycle that wedges in a
 *   state it should not.
 *
 * Also mirrored to the platform console (`println` → browser console, logcat, Xcode) so a developer
 * with a device attached gets it for free, while the in-app panel serves a deployed demo where the
 * console is out of reach.
 */
class DiagnosticsLog(private val capacity: Int = MAX_ENTRIES) {

    private var originMs: Long? = null
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    /** A free-text line not tied to an event — e.g. which stream was selected. Stamped on receipt. */
    fun note(label: String, detail: String? = null, level: DiagnosticLevel = DiagnosticLevel.Info) {
        append(level, label, detail, stampMs = null)
    }

    /** Records one SDK event, using the event's own monotonic stamp so ordering matches the engine's. */
    fun record(event: PlayerEvent) {
        val (level, label, detail) = describe(event)
        append(level, label, detail, stampMs = event.elapsedRealtimeMs)
    }

    fun clear() {
        originMs = null
        _entries.value = emptyList()
    }

    /** The whole timeline as text, for pasting into a bug report. */
    fun asText(): String = _entries.value.joinToString("\n") { e ->
        val t = "T+" + (e.atMs / MILLIS_PER_SECOND.toDouble()).formatOneDp() + "s"
        val d = e.detail?.let { " — $it" } ?: ""
        "$t  [${e.level}] ${e.label}$d"
    }

    private fun append(level: DiagnosticLevel, label: String, detail: String?, stampMs: Long?) {
        // The first entry defines T+0. Notes (no stamp) that arrive before any event fall back to 0
        // rather than seeding the origin, so the origin is always an engine timestamp.
        val origin = originMs
        val atMs = when {
            stampMs == null -> if (origin == null) 0L else 0L.coerceAtLeast(lastAt())
            origin == null -> {
                originMs = stampMs
                0L
            }
            else -> stampMs - origin
        }
        val entry = DiagnosticEntry(atMs, level, label, detail)
        _entries.update { (it + entry).takeLast(capacity) }
        println("[outis] ${entry.asConsoleLine()}")
    }

    private fun lastAt(): Long = _entries.value.lastOrNull()?.atMs ?: 0L

    private companion object {
        const val MAX_ENTRIES = 300
        const val MILLIS_PER_SECOND = 1000
    }
}

private fun DiagnosticEntry.asConsoleLine(): String {
    val d = detail?.let { " — $it" } ?: ""
    return "$label$d"
}

private const val DECI = 10
private const val BITS_PER_KBIT = 1000

private fun Double.formatOneDp(): String {
    val scaled = (this * DECI).toLong()
    return "${scaled / DECI}.${scaled % DECI}"
}

/** Maps an event to (level, label, detail). Kept exhaustive so a new event type forces a decision here. */
@Suppress("CyclomaticComplexMethod")
private fun describe(event: PlayerEvent): Triple<DiagnosticLevel, String, String?> = when (event) {
    is PlayerEvent.BufferingStarted ->
        Triple(DiagnosticLevel.Warn, "Buffering started", "at ${event.positionMs}ms")
    is PlayerEvent.BufferingEnded ->
        Triple(DiagnosticLevel.Good, "Buffering ended", "resumed at ${event.positionMs}ms")
    is PlayerEvent.FirstFrameRendered ->
        Triple(DiagnosticLevel.Good, "First frame", "startup complete")
    is PlayerEvent.PlaybackStateChanged ->
        Triple(levelFor(event.state), "State → ${event.state}", null)
    is PlayerEvent.IsPlayingChanged ->
        Triple(DiagnosticLevel.Info, "isPlaying = ${event.isPlaying}", null)
    is PlayerEvent.PlaybackRecovered ->
        Triple(DiagnosticLevel.Warn, "Recovered", recoveryDetail(event.reason))
    is PlayerEvent.FatalError ->
        Triple(
            DiagnosticLevel.Error,
            "FATAL: ${event.error.category}",
            listOfNotNull(event.error.message, event.error.code?.let { "code=$it" })
                .joinToString(" · ").ifEmpty { "no detail" },
        )
    is PlayerEvent.Ended ->
        Triple(DiagnosticLevel.Info, "Ended", "reached end of item")
    is PlayerEvent.MediaItemTransition ->
        Triple(DiagnosticLevel.Info, "Item transition", event.item?.let { "new item" } ?: "cleared")
    is PlayerEvent.SeekStarted ->
        Triple(DiagnosticLevel.Info, "Seek → ${event.targetMs}ms", "from ${event.positionMs}ms")
    is PlayerEvent.SeekCompleted ->
        Triple(DiagnosticLevel.Info, "Seek landed", "at ${event.positionMs}ms")
    is PlayerEvent.BitrateChanged ->
        Triple(DiagnosticLevel.Info, "Rendition switch", bitrateDetail(event))
    is PlayerEvent.BandwidthSample ->
        Triple(DiagnosticLevel.Info, "Bandwidth", "${event.bitsPerSecond / BITS_PER_KBIT} kbps")
    is PlayerEvent.DroppedFrames ->
        Triple(DiagnosticLevel.Warn, "Dropped frames", "${event.count} since last")
    is PlayerEvent.NativePlayerAttached ->
        Triple(DiagnosticLevel.Info, "Native player attached", if (event.handle == null) "detached" else null)
    is PlayerEvent.TracksChanged ->
        Triple(
            DiagnosticLevel.Info,
            "Tracks changed",
            "${event.audioTracks.size} audio, ${event.textTracks.size} text",
        )
}

private fun levelFor(state: PlaybackState): DiagnosticLevel = when (state) {
    PlaybackState.READY -> DiagnosticLevel.Good
    PlaybackState.BUFFERING -> DiagnosticLevel.Warn
    else -> DiagnosticLevel.Info
}

private fun recoveryDetail(reason: RecoveryReason): String = when (reason) {
    RecoveryReason.BEHIND_LIVE_WINDOW -> "fell behind live window, re-snapped to the edge"
    RecoveryReason.STALL -> "stalled too long, nudged back to life"
}

private fun bitrateDetail(event: PlayerEvent.BitrateChanged): String {
    val f = event.format
    val res = if (f.width != null && f.height != null) "${f.width}x${f.height}" else "?"
    val rate = f.bitrate?.let { "${it / BITS_PER_KBIT} kbps" } ?: "? kbps"
    return "$res · $rate"
}
