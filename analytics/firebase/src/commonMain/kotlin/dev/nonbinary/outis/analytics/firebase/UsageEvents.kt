/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.firebase

import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.PlayerState
import dev.nonbinary.outis.core.analytics.StreamType

private const val MAX_NAME_LENGTH = 40
private const val MAX_VALUE_LENGTH = 100
private const val MS_PER_SECOND = 1000L

/** An analytics event ready to log. */
internal data class UsageEvent(val name: String, val params: Map<String, Any>)

/**
 * [usageEventOf] with one start per play-through: playback resumes after every pause, stall and seek,
 * and each resume would otherwise count as a fresh view. A new item, or a replay after the end, starts
 * again.
 */
internal class UsageTracker {
    private var started = false

    fun eventFor(event: PlayerEvent, state: PlayerState): UsageEvent? {
        when (event) {
            is PlayerEvent.MediaItemTransition, is PlayerEvent.Ended -> started = false
            is PlayerEvent.IsPlayingChanged -> if (!event.isPlaying || started) return null else started = true
            else -> Unit
        }
        return usageEventOf(event, state)
    }
}

/** The usage event [event] amounts to, with [state] for context; `null` for the many that are not usage. */
internal fun usageEventOf(event: PlayerEvent, state: PlayerState): UsageEvent? {
    val name = when (event) {
        is PlayerEvent.IsPlayingChanged -> if (event.isPlaying) "video_start" else return null
        is PlayerEvent.Ended -> "video_complete"
        is PlayerEvent.FatalError -> "video_error"
        else -> return null
    }
    val params = videoParams(state, event.positionMs)
    if (event !is PlayerEvent.FatalError) return UsageEvent(name, params)
    val error = buildMap<String, Any> {
        put("error_category", event.error.category.name)
        event.error.code?.let { put("error_code", it) }
    }
    return UsageEvent(name, params + error)
}

private fun videoParams(state: PlayerState, positionMs: Long): Map<String, Any> = buildMap {
    val item = state.mediaItem
    val analytics = item?.analytics
    put("video_provider", "outis")
    (analytics?.title ?: item?.metadata?.title)?.let { put("video_title", it) }
    analytics?.videoId?.let { put("video_id", it) }
    analytics?.series?.let { put("video_series", it) }
    (analytics?.durationMs ?: state.durationMs)?.let { put("video_duration", it / MS_PER_SECOND) }
    put("video_current_time", positionMs / MS_PER_SECOND)
    val live = analytics?.streamType?.let { it == StreamType.LIVE } ?: state.isLive
    put("stream_type", if (live) "live" else "vod")
}

/** [name] as GA4 accepts it: ASCII letters, digits and underscores, starting with a letter, 40 at most. */
internal fun ga4Name(name: String): String {
    val cleaned = name.map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') it else '_' }.joinToString("")
    val lettered = if (cleaned.firstOrNull()?.isLetter() == true) cleaned else "e_$cleaned"
    return lettered.take(MAX_NAME_LENGTH)
}

/** [params] with GA4-safe names and string values cut to GA4's 100 characters. */
internal fun ga4Params(params: Map<String, Any>): Map<String, Any> =
    params.entries.associate { (key, value) ->
        ga4Name(key) to if (value is String) value.take(MAX_VALUE_LENGTH) else value
    }
