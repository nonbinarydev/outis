/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.track

import dev.nonbinary.outis.core.net.DEFAULT_HTTP_TIMEOUT_MS
import dev.nonbinary.outis.core.net.HttpFetch
import dev.nonbinary.outis.core.net.HttpResult
import dev.nonbinary.outis.core.net.httpGet
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.SubtitleCue
import dev.nonbinary.outis.core.source.SubtitleFile
import dev.nonbinary.outis.core.source.cueTextAt
import dev.nonbinary.outis.core.source.parseWebVttCues
import dev.nonbinary.outis.core.source.resolvedFormat
import dev.nonbinary.outis.core.source.subtitleAsWebVtt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** [MediaItem.subtitles] as text tracks, in order and none selected; the first is the default. */
internal fun MediaItem.sidecarTracks(): List<MediaTrack> = subtitles.mapIndexed { index, file ->
    MediaTrack("SIDECAR:$index", TrackType.TEXT, label = file.label, language = file.language, isDefault = index == 0)
}

/**
 * Sidecar subtitles an engine shows through [dev.nonbinary.outis.core.PlayerState.sidecarCues], for a
 * platform player that takes no subtitle file itself: AVPlayer has no way to add one to an item. Each is
 * listed at once and fetched the first time it is shown, with the item's headers; one that cannot be
 * fetched or read has no cues. [onLoaded] runs when the cues of the sidecar showing arrive.
 */
internal class SidecarCaptions(
    private val scope: CoroutineScope,
    private val fetch: HttpFetch = ::httpGet,
    private val onLoaded: () -> Unit,
) {
    private class Sidecar(val track: MediaTrack, val file: SubtitleFile) {
        var cues: List<SubtitleCue>? = null
        var loading: Job? = null
    }

    private var sidecars = emptyList<Sidecar>()
    private var headers = emptyMap<String, String>()

    /** The id of the sidecar showing, or `null`. */
    var shownId: String? = null
        private set

    /** The sidecars as text tracks, the one showing selected. */
    val tracks: List<MediaTrack> get() = sidecars.map { it.track.copy(isSelected = it.track.id == shownId) }

    /** Takes [item]'s sidecars in place of the last item's, `null` for none; none shows. */
    fun attach(item: MediaItem?) {
        sidecars.forEach { it.loading?.cancel() }
        headers = item?.headers.orEmpty()
        sidecars = item?.let { it.sidecarTracks().zip(it.subtitles, ::Sidecar) }.orEmpty()
        shownId = null
    }

    /** Whether [id] names one of the sidecars. */
    fun has(id: String): Boolean = sidecars.any { it.track.id == id }

    /** Shows the sidecar named [id], fetching it if it never was, or none for `null`. */
    fun show(id: String?) {
        val sidecar = sidecars.firstOrNull { it.track.id == id }
        shownId = sidecar?.track?.id
        if (sidecar == null || sidecar.cues != null || sidecar.loading != null) return
        sidecar.loading = scope.launch {
            val body = (fetch(sidecar.file.url, headers, DEFAULT_HTTP_TIMEOUT_MS) as? HttpResult.Ok)?.body
            sidecar.cues = body?.let { subtitleAsWebVtt(it, sidecar.file.resolvedFormat) }
                ?.let(::parseWebVttCues)
                .orEmpty()
            if (shownId == sidecar.track.id) onLoaded()
        }
    }

    /** The text of the showing sidecar's cues at [positionMs]: none while it loads, or when none shows. */
    fun cuesAt(positionMs: Long): List<String> =
        sidecars.firstOrNull { it.track.id == shownId }?.cues?.let { cueTextAt(it, positionMs) }.orEmpty()
}
