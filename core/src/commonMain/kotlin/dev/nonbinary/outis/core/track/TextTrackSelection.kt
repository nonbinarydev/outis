/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.track

/**
 * Whether BCP-47 tags [tag] and [want] name the same language, ignoring case and tolerating a region or
 * variant on either side: `en` matches `en-GB`, and `en-GB` matches `en`.
 */
internal fun languageTagMatches(tag: String, want: String): Boolean {
    val a = tag.lowercase().replace('_', '-')
    val b = want.lowercase().replace('_', '-')
    return a == b || a.startsWith("$b-") || b.startsWith("$a-")
}

/**
 * The text track to show when an item loads, for an engine that picks it itself: none unless captions
 * start on, then the first in [preferredLanguage], else the first track.
 */
internal fun initialTextTrack(tracks: List<MediaTrack>, captionsOn: Boolean, preferredLanguage: String?): MediaTrack? {
    if (!captionsOn) return null
    val preferred = preferredLanguage?.let { want ->
        tracks.firstOrNull { track -> track.language?.let { languageTagMatches(it, want) } == true }
    }
    return preferred ?: tracks.firstOrNull()
}
