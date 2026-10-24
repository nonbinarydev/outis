/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.net.HttpResult
import dev.nonbinary.outis.core.net.httpGet
import dev.nonbinary.outis.core.source.CaptionsDefaultMode
import dev.nonbinary.outis.core.source.EMPTY_WEBVTT
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.SubtitleFile
import dev.nonbinary.outis.core.source.SubtitleFormat
import dev.nonbinary.outis.core.source.resolvedFormat
import dev.nonbinary.outis.core.source.subtitleAsWebVtt
import dev.nonbinary.outis.core.track.MediaTrack
import dev.nonbinary.outis.core.track.TrackType
import dev.nonbinary.outis.core.track.initialTextTrack
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.khronos.webgl.Uint8Array
import org.w3c.dom.DISABLED
import org.w3c.dom.HTMLTrackElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.SHOWING
import org.w3c.dom.TextTrackMode
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.Promise

/**
 * [url], a file in [format], fetched with [headers] and as WebVTT. One that cannot be fetched, or is not a
 * subtitle file at all, reads as a file with no cues: Shaka would stop playback over it, and it must cost
 * its own cues only.
 */
private suspend fun fetchWebVtt(url: String, format: SubtitleFormat, headers: Map<String, String>): String =
    (httpGet(url, headers) as? HttpResult.Ok)?.body?.let { subtitleAsWebVtt(it, format) } ?: EMPTY_WEBVTT

private const val SIDECAR_SCHEME = "outis-sidecar"

// The scheme is registered for every Player on the page at once, so its fetches outlive any one engine.
private val sidecarScope = MainScope()
private var sidecarSchemeRegistered = false

/**
 * Adds [files] to the stream [this] has loaded, resolving to the Shaka id of the first it took. Each is named
 * by a URI of the sidecar scheme, so Shaka fetches it only when it is shown, and through [sidecarPlugin]
 * rather than on its own. One Shaka will not take, as on a live stream, is left out.
 */
internal fun shaka.Player.addSidecarSubtitles(files: List<SubtitleFile>): Promise<Any?> {
    if (!sidecarSchemeRegistered) {
        shaka.net.NetworkingEngine.registerScheme(SIDECAR_SCHEME, ::sidecarPlugin)
        sidecarSchemeRegistered = true
    }
    return Promise.all(
        files.map { file ->
            val uri = "$SIDECAR_SCHEME:${file.resolvedFormat.name}:${encodeURIComponent(file.url)}"
            addTextTrackAsync(uri, file.language ?: "und", "subtitles", "text/vtt", null, file.label).catch { null }
        }.toTypedArray(),
    ).then { added -> added.firstOrNull { it != null }?.asDynamic()?.id as Any? }
}

/**
 * Shaka's plugin for the sidecar scheme: the file the URI names, as [fetchWebVtt] reads it, sent with the
 * request's headers, which carry the item's own.
 */
private fun sidecarPlugin(uri: String, request: dynamic): dynamic {
    val (format, url) = uri.removePrefix("$SIDECAR_SCHEME:").split(':', limit = 2)
    val response = sidecarScope.promise {
        val vtt = fetchWebVtt(decodeURIComponent(url), SubtitleFormat.valueOf(format), jsHeadersToMap(request.headers))
        val reply: dynamic = js("({})")
        reply.uri = uri
        reply.originalUri = uri
        reply.data = TextEncoder().encode(vtt)
        reply.headers = js("({})")
        reply.originalRequest = request
        reply
    }
    return shaka.util.AbortableOperation.notAbortable(response)
}

/**
 * Sidecar subtitles on a `<video>` that plays a file itself, without Shaka. Each joins the text tracks at
 * once, and is fetched the first time it is shown, into a `<track>` whose source is a blob URL of the text:
 * only the subtitle host then needs CORS, for the fetch, where the element itself would need
 * `crossorigin`, and with it CORS from the video host too.
 */
internal class NativeSubtitles(private val video: HTMLVideoElement, private val scope: CoroutineScope) {
    private class Sidecar(val track: MediaTrack, val file: SubtitleFile) {
        var blobUrl: String? = null
        var element: HTMLTrackElement? = null
        var fetch: Job? = null
    }

    private var sidecars = emptyList<Sidecar>()
    private var shown: String? = null

    /** Lists [item]'s sidecars and shows the one it starts with; returns them as text tracks, that one selected. */
    fun attach(item: MediaItem): List<MediaTrack> {
        val tracks = item.subtitles.mapIndexed { index, file ->
            // The first is the default, as Android flags it.
            MediaTrack("SIDECAR:$index", TrackType.TEXT, file.label, file.language, isDefault = index == 0)
        }
        sidecars = tracks.zip(item.subtitles, ::Sidecar)
        val captionsOn = item.captionsDefault == CaptionsDefaultMode.ON
        val initial = initialTextTrack(tracks, captionsOn, item.preferredTextLanguage)
        show(initial?.id)
        return tracks.map { it.copy(isSelected = it.id == initial?.id) }
    }

    /** Whether [id] names one of the sidecars. */
    fun has(id: String): Boolean = sidecars.any { it.track.id == id }

    /** Shows the sidecar named [id], fetching it if it never was, or none for `null`. */
    fun show(id: String?) {
        shown = id
        sidecars.forEach { it.element?.track?.mode = TextTrackMode.DISABLED }
        val sidecar = sidecars.firstOrNull { it.track.id == id } ?: return
        when {
            sidecar.blobUrl != null -> display(sidecar)
            sidecar.fetch == null -> sidecar.fetch = scope.launch {
                val vtt = fetchWebVtt(sidecar.file.url, sidecar.file.resolvedFormat, emptyMap())
                sidecar.blobUrl = URL.createObjectURL(Blob(arrayOf(vtt), BlobPropertyBag(type = "text/vtt")))
                if (shown == sidecar.track.id) display(sidecar)
            }
        }
    }

    /**
     * Shows [sidecar], adding its `<track>` first if this is the first time. A `<track>` joins only as it is
     * shown: one added hidden, while the `<video>` shows text, would be switched on by the browser itself.
     */
    private fun display(sidecar: Sidecar) {
        val element = sidecar.element ?: (document.createElement("track") as HTMLTrackElement).also { element ->
            element.kind = "subtitles"
            element.src = checkNotNull(sidecar.blobUrl)
            sidecar.file.language?.let { element.srclang = it }
            element.label = sidecar.file.label ?: sidecar.file.language.orEmpty()
            video.appendChild(element)
            sidecar.element = element
        }
        element.track.mode = TextTrackMode.SHOWING
    }

    /** Removes every sidecar, before the next item or on release. */
    fun clear() {
        sidecars.forEach { sidecar ->
            sidecar.fetch?.cancel()
            // Hidden before removal, or the `<video>` still counts its text as on and shows the next track.
            sidecar.element?.let {
                it.track.mode = TextTrackMode.DISABLED
                it.remove()
            }
            sidecar.blobUrl?.let(URL::revokeObjectURL)
        }
        sidecars = emptyList()
        shown = null
    }
}

private external fun encodeURIComponent(value: String): String

private external fun decodeURIComponent(value: String): String

private external class TextEncoder {
    fun encode(input: String): Uint8Array
}
