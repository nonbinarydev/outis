/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.ads

/** A SCTE-35-signalled ad avail parsed from an HLS media playlist. */
data class HlsAdCue(
    /**
     * The DATERANGE `ID` attribute when present, otherwise a synthesised `"daterange-n"` / `"cue-n"`
     * token. **Only stable across reloads for the DATERANGE form** — synthesised ids are positional, so
     * a live playlist that drops expired segments will renumber them.
     */
    val id: String,
    /**
     * Avail duration in ms, converted from the playlist's floating-point seconds
     * (`PLANNED-DURATION` or the `#EXT-X-CUE-OUT` value). `0` when the signalling carried no duration —
     * treat that as "unknown", not as a zero-length break.
     */
    val durationMs: Long,
)
