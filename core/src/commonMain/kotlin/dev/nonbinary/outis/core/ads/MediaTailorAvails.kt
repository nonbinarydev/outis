/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.ads

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/** MediaTailor reports every time in floating-point seconds; the SDK's model is in milliseconds. */
private const val MS_PER_SECOND = 1000

/** Used to expand the `M` component of an ISO-8601 `PT#M#S` duration. */
private const val SECONDS_PER_MINUTE = 60

/** Lenient: the tracking schema carries many fields the SDK deliberately does not model. */
private val mediaTailorJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Parse a MediaTailor avails/tracking JSON document into the ad breaks an [AdController] consumes.
 *
 * Handles an AWS Elemental MediaTailor (or compatible) **server-side ad insertion** tracking
 * response — the JSON a client GETs from the session's tracking URL — mapping it onto the
 * engine-neutral [AdBreak]/[Ad] model. Pure data mapping (no HTTP): the app fetches the JSON
 * (Ktor, a CORS-safe proxy on web) and feeds the string in here.
 *
 * Field shapes follow the documented MediaTailor tracking schema: the root is `{ avails: [...] }`;
 * times are **seconds** (floating point), so they're scaled to ms here. Unknown fields (adSystem,
 * mediaFiles, companionAds, trackingEvents, …) are ignored.
 */
fun parseMediaTailorAvails(json: String): List<AdBreak> =
    mediaTailorJson.decodeFromString(AvailsResponseDto.serializer(), json).avails.map { avail ->
        AdBreak(
            id = avail.availId,
            startMs = (avail.startTimeInSeconds * MS_PER_SECOND).roundToLong(),
            ads = avail.ads.map { ad ->
                Ad(
                    id = ad.adId,
                    durationMs = (ad.durationInSeconds * MS_PER_SECOND).roundToLong(),
                    title = ad.adTitle,
                    skipOffsetMs = ad.skipOffset?.let {
                        parseIso8601Seconds(
                            it
                        )?.let { s -> (s * MS_PER_SECOND).roundToLong() }
                    },
                    clickThroughUrl = ad.clickThroughUrl,
                )
            },
        )
    }

/** The stitched-manifest + tracking URLs a MediaTailor session-init POST returns (paths — prepend scheme+host). */
data class MediaTailorSession(
    /**
     * Path of the stitched HLS/DASH manifest to hand to the player. **Root-relative, not absolute** —
     * prepend the scheme and host you POSTed the session-init request to before loading it.
     */
    val manifestUrl: String,
    /**
     * Path of the per-session tracking endpoint whose JSON [parseMediaTailorAvails] consumes. Same
     * **root-relative** caveat as [manifestUrl]. Fetching it is the app's job; this module never does I/O.
     */
    val trackingUrl: String,
)

/** Parse the small JSON body returned by a MediaTailor session-init POST. */
fun parseMediaTailorSession(json: String): MediaTailorSession =
    mediaTailorJson.decodeFromString(SessionResponseDto.serializer(), json)
        .let { MediaTailorSession(manifestUrl = it.manifestUrl, trackingUrl = it.trackingUrl) }

/**
 * Parse an ISO-8601 duration of the `PT[nM][nS]` form (e.g. `"PT38.4S"`, `"PT1M5S"`) — MediaTailor's
 * skip-offset/duration string form — into seconds, or `null` if it isn't that shape. A bare number
 * (already seconds) is also accepted.
 */
internal fun parseIso8601Seconds(value: String): Double? {
    val trimmed = value.trim()
    trimmed.toDoubleOrNull()?.let { return it }
    val match = Regex("""PT(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?""").matchEntire(trimmed) ?: return null
    val minutes = match.groupValues[1].toDoubleOrNull() ?: 0.0
    val seconds = match.groupValues[2].toDoubleOrNull() ?: 0.0
    if (match.groupValues[1].isEmpty() && match.groupValues[2].isEmpty()) return null
    return minutes * SECONDS_PER_MINUTE + seconds
}

@Serializable
private data class AvailsResponseDto(val avails: List<AvailDto> = emptyList())

@Serializable
private data class AvailDto(
    val availId: String = "",
    val startTimeInSeconds: Double = 0.0,
    val durationInSeconds: Double = 0.0,
    val ads: List<AdDto> = emptyList(),
)

@Serializable
private data class AdDto(
    val adId: String = "",
    val adTitle: String? = null,
    val durationInSeconds: Double = 0.0,
    val startTimeInSeconds: Double = 0.0,
    // The wire format spells it with a lowercase 't' (`clickthroughUrl`); accept that exact key.
    @SerialName("clickthroughUrl") val clickThroughUrl: String? = null,
    val skipOffset: String? = null,
)

@Serializable
private data class SessionResponseDto(
    val manifestUrl: String = "",
    val trackingUrl: String = "",
)
