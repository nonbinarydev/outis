/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import dev.nonbinary.outis.core.PlayerError
import dev.nonbinary.outis.core.net.HttpFetch
import dev.nonbinary.outis.core.net.HttpResult
import dev.nonbinary.outis.core.net.originOf

/** What a SMIL item resolved to: the item to load in its place, or the error to fail it with. */
internal sealed interface SmilOutcome {
    class Resolved(val item: MediaItem) : SmilOutcome

    class Failed(val error: PlayerError) : SmilOutcome
}

/** A document [parseSmil] cannot read: not SMIL, or unterminated markup. */
internal class SmilFormatException(message: String) : IllegalArgumentException(message)

/**
 * Fetch the SMIL document at [smilUrl] with [item]'s headers, parse it, and let [selector] choose. The item to
 * load in [item]'s place is the chosen rendition as a [MediaSource.Url] with its format, keeping
 * [MediaItem.headers] only when the rendition shares the document's origin — they were given for that origin.
 */
internal suspend fun resolveSmil(
    item: MediaItem,
    smilUrl: String,
    selector: SmilSelector,
    timeoutMs: Int,
    fetch: HttpFetch,
): SmilOutcome {
    val document = when (val response = fetch(smilUrl, item.headers, timeoutMs)) {
        is HttpResult.Ok -> response
        is HttpResult.Status -> return failed(
            PlayerError.Category.SOURCE,
            "The SMIL document answered HTTP ${response.code}",
            code = "HTTP ${response.code}",
        )
        is HttpResult.Failed -> return failed(
            PlayerError.Category.NETWORK,
            "The SMIL document could not be fetched: ${response.message}",
            nativeCause = response.nativeCause,
        )
    }
    val renditions = try {
        parseSmil(document.body, document.url)
    } catch (e: SmilFormatException) {
        return failed(PlayerError.Category.SOURCE, e.message)
    }
    val chosen = selector.select(renditions, item)
        ?: return failed(PlayerError.Category.SOURCE, "None of the ${renditions.size} SMIL renditions is playable here")
    val sameOrigin = originOf(chosen.url)?.let { it == originOf(smilUrl) } == true
    return SmilOutcome.Resolved(
        item.copy(
            source = MediaSource.Url(chosen.url),
            mimeType = chosen.format,
            headers = if (sameOrigin) item.headers else emptyMap(),
        ),
    )
}

private fun failed(
    category: PlayerError.Category,
    message: String?,
    code: String? = null,
    nativeCause: Any? = null,
): SmilOutcome = SmilOutcome.Failed(PlayerError(category, code, message, nativeCause))
