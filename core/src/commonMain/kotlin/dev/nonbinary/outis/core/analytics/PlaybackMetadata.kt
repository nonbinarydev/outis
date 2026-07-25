/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.analytics

import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * Vendor-neutral QoS/analytics metadata for a piece of content.
 *
 * Defined once here so an analytics adapter (`outis-analytics-mux`, and others) maps these fields to
 * its vendor's vocabulary, and swapping vendors never touches app code. It carries **no** display
 * concern — [dev.nonbinary.outis.core.source.MediaMetadata] is title/artwork for chrome; this is what a
 * QoS backend wants to slice sessions by.
 *
 * Attached per item as [dev.nonbinary.outis.core.source.MediaItem.analytics], so the metadata rides
 * with the source it describes and the app cannot desync two objects. Every field is optional: an
 * adapter sends what it has, and viewer identity is the *adapter's* concern (session-scoped), not the
 * item's.
 */
data class PlaybackMetadata(
    /** Stable id for this title across sessions — the primary key a QoS backend groups views by. */
    val videoId: String? = null,
    /**
     * Human title for reports. Optional override: an adapter should fall back to the display
     * [dev.nonbinary.outis.core.source.MediaMetadata.title] when this is `null`, so a title set for
     * chrome is not repeated here.
     */
    val title: String? = null,
    /** Series/show this belongs to, for grouping episodes. */
    val series: String? = null,
    /** VOD or live. When `null`, an adapter may infer it from the player's own live signal. */
    val streamType: StreamType? = null,
    /** Content duration in ms, where known ahead of playback. */
    val durationMs: Long? = null,
    /** The CDN or delivery origin serving this stream, for slicing QoS by edge. */
    val cdn: String? = null,
    /** Vendor-specific custom dimensions, passed through untouched. */
    val custom: ImmutableMap<String, String> = persistentMapOf(),
)

/** Whether content is on-demand or live — the one distinction every QoS backend treats specially. */
enum class StreamType { VOD, LIVE }
