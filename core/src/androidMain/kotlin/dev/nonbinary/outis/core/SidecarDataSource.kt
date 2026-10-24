/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import dev.nonbinary.outis.core.source.EMPTY_WEBVTT
import dev.nonbinary.outis.core.source.SubtitleFile
import dev.nonbinary.outis.core.source.SubtitleFormat
import dev.nonbinary.outis.core.source.hasWebVttHeader
import dev.nonbinary.outis.core.source.resolvedFormat
import java.io.IOException

/**
 * Opens an item's sidecar subtitles whole, and serves one that cannot be fetched, or is not WebVTT when it
 * should be, as a file with no cues. Media3 stops playback when a text track it is showing fails to load
 * or parse, and a broken subtitle file must cost its own cues, never the playback. Every other request
 * passes straight through to [upstream].
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class SidecarDataSource private constructor(
    private val upstream: DataSource,
    private val sidecars: Map<String, SubtitleFormat>,
) : DataSource {
    private var document: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val format = sidecars[dataSpec.uri.toString()] ?: return upstream.open(dataSpec)
        val bytes = try {
            upstream.open(dataSpec)
            DataSourceUtil.readToEnd(upstream).takeIf { format != SubtitleFormat.WEBVTT || it.hasWebVttHeader() }
        } catch (ignored: IOException) {
            null
        } finally {
            upstream.close()
        }
        val served = bytes ?: emptyDocument(format)
        return ByteArrayDataSource { served }.also { document = it }.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (document ?: upstream).read(buffer, offset, length)

    override fun getUri(): Uri? = (document ?: upstream).uri

    override fun getResponseHeaders(): Map<String, List<String>> = (document ?: upstream).responseHeaders

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun close() {
        document?.close()
        document = null
        upstream.close()
    }

    /** [upstream]'s sources, each serving [subtitles] as [SidecarDataSource] does. */
    class Factory(private val upstream: DataSource.Factory, subtitles: List<SubtitleFile>) : DataSource.Factory {
        // Keyed as Media3 requests them: the sidecar's Uri, read back as a string.
        private val sidecars = subtitles.associate { it.url.toUri().toString() to it.resolvedFormat }

        override fun createDataSource(): DataSource = SidecarDataSource(upstream.createDataSource(), sidecars)
    }
}

private fun ByteArray.hasWebVttHeader(): Boolean = hasWebVttHeader(decodeToString(0, minOf(size, HEADER_BYTES)))

private fun emptyDocument(format: SubtitleFormat): ByteArray = when (format) {
    SubtitleFormat.WEBVTT -> EMPTY_WEBVTT.encodeToByteArray()
    SubtitleFormat.SRT -> ByteArray(0)
}

/** Enough of a file to hold a byte-order mark, `WEBVTT` and the character after it. */
private const val HEADER_BYTES = 16
