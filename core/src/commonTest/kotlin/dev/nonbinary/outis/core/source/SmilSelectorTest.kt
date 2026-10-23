/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SmilSelectorTest {

    private val item = MediaItem(MediaSource.Smil("https://e/a.smil"))
    private val hls = rendition("https://e/a.m3u8", MimeType.HLS)
    private val dash = rendition("https://e/a.mpd", MimeType.DASH)
    private val dashAndHls = listOf(MimeType.DASH, MimeType.HLS)

    @Test
    fun followsTheEnginesOrder() {
        assertSame(dash, selectManifest(listOf(hls, dash), item, dashAndHls))
        assertSame(hls, selectManifest(listOf(dash, hls), item, listOf(MimeType.HLS)))
    }

    @Test
    fun theDrmSchemesFormatGoesFirst() {
        val widevine = item.copy(drmConfig = DrmConfig(DrmScheme.WIDEVINE, licenseServerUrl = "https://l"))
        val fairPlay = item.copy(drmConfig = DrmConfig(DrmScheme.FAIRPLAY, certificateUrl = "https://c"))
        val clearKey = item.copy(drmConfig = DrmConfig(DrmScheme.CLEARKEY, clearKeys = mapOf("k" to "v")))

        assertSame(dash, selectManifest(listOf(hls, dash), widevine, listOf(MimeType.HLS, MimeType.DASH)))
        assertSame(hls, selectManifest(listOf(dash, hls), fairPlay, dashAndHls))
        assertSame(dash, selectManifest(listOf(hls, dash), clearKey, dashAndHls))
        // A pairing the engine cannot play leaves its order alone.
        assertSame(hls, selectManifest(listOf(dash, hls), widevine, listOf(MimeType.HLS)))
    }

    @Test
    fun neverChoosesProgressiveOrUnknownFormats() {
        val progressive = listOf(rendition("https://e/a.mp4", MimeType.MP4), rendition("https://e/x", null))

        assertNull(selectManifest(progressive, item, dashAndHls))
        assertNull(selectManifest(emptyList(), item, dashAndHls))
    }

    @Test
    fun prefersVideoElementsToAudioOnes() {
        val description = rendition("https://e/described.m3u8", MimeType.HLS, element = "audio")

        assertSame(hls, selectManifest(listOf(description, hls), item, listOf(MimeType.HLS)))
        assertSame(description, selectManifest(listOf(description, dash), item, listOf(MimeType.HLS)))
    }

    @Test
    fun aSmilItemIsDeclaredOrAnUntypedSmilPath() {
        assertEquals("https://e/a", MediaItem(MediaSource.Smil("https://e/a")).smilUrl())
        assertEquals("https://e/a.SMIL?t=1", MediaItem(MediaSource.Url("https://e/a.SMIL?t=1")).smilUrl())
        assertNull(MediaItem(MediaSource.Url("https://e/a.smil"), mimeType = MimeType.HLS).smilUrl())
        assertNull(MediaItem(MediaSource.Url("https://e/vod/smil:a.smil/playlist.m3u8")).smilUrl())
        assertNull(MediaItem(MediaSource.Url("https://e/a.m3u8?x=.smil")).smilUrl())
        assertNull(MediaItem(MediaSource.LocalFile("/sdcard/a.smil")).smilUrl())
    }

    private fun rendition(url: String, format: MimeType?, element: String = "video") =
        SmilRendition(url, format, element, attributes = mapOf("src" to url), params = emptyMap())
}
