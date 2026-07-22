/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.ads

private val DATERANGE_ID = Regex("""ID="([^"]+)"""")
private val PLANNED_DURATION = Regex("""PLANNED-DURATION=([0-9.]+)""")
private val CUE_OUT_DURATION = Regex("""#EXT-X-CUE-OUT(?::(?:DURATION=)?([0-9.]+))?""")

/**
 * Parse SCTE-35 ad avails out of an HLS media playlist — shared, engine-neutral, the second cue *source*
 * for [AdController] (alongside MediaTailor avails JSON), proving the seam isn't tied to one provider.
 *
 * Recognises both signalling forms broadcasters emit:
 * - `#EXT-X-DATERANGE:ID="…",PLANNED-DURATION=38.4,SCTE35-OUT=0x…` (preferred — carries a stable id), and
 * - the `#EXT-X-CUE-OUT[:dur] … #EXT-X-CUE-IN` bracket (used when no DATERANGE is present).
 *
 * The Unified Streaming reference stream emits BOTH per avail, so when any DATERANGE is present the
 * CUE-OUT lines are ignored to avoid double-counting. Returns one [HlsAdCue] per avail, in playlist order.
 */
fun parseHlsAdCues(mediaPlaylist: String): List<HlsAdCue> {
    val preferDateRange = mediaPlaylist.contains("#EXT-X-DATERANGE") &&
        (mediaPlaylist.contains("SCTE35-OUT") || mediaPlaylist.contains("PLANNED-DURATION"))
    val cues = mutableListOf<HlsAdCue>()
    var fallbackIndex = 0
    for (raw in mediaPlaylist.lineSequence()) {
        val line = raw.trim()
        if (preferDateRange) {
            if (line.startsWith("#EXT-X-DATERANGE") &&
                (line.contains("SCTE35-OUT") || line.contains("PLANNED-DURATION"))
            ) {
                val id = DATERANGE_ID.find(line)?.groupValues?.get(1) ?: "daterange-${fallbackIndex++}"
                val durationSec = PLANNED_DURATION.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                cues += HlsAdCue(id, (durationSec * 1000).toLong())
            }
        } else if (line.startsWith("#EXT-X-CUE-OUT")) {
            val durationSec = CUE_OUT_DURATION.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            cues += HlsAdCue("cue-${fallbackIndex++}", (durationSec * 1000).toLong())
        }
    }
    return cues
}
