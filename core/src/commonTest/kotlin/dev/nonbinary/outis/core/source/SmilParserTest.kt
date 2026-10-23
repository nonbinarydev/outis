/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmilParserTest {

    private val documentUrl = "https://origin.example.com/assets/42/index.smil?sig=abc"

    @Test
    fun readsEveryMediaElementInDocumentOrder() {
        val smil = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- packaged 2026-10-01 -->
            <smil xmlns="http://www.w3.org/ns/SMIL" version="3.0">
              <head>
                <meta name="title" content="Sintel"/>
              </head>
              <body>
                <switch>
                  <video src="dash/manifest.mpd" type="application/dash+xml" system-bitrate="5000000">
                    <param name="drm" value="widevine" valuetype="data"/>
                  </video>
                  <video src='https://cdn.example.com/hls/master.m3u8?token=a&amp;b=c' title="HLS"/>
                  <video src="mp4/720.mp4"/>
                  <textstream src="subs/en.vtt"/>
                  <video src="opaque/stream" />
                  <video title="no source"/>
                </switch>
              </body>
            </smil>
        """.trimIndent()

        val renditions = parseSmil(smil, documentUrl)

        assertEquals(
            listOf(
                "https://origin.example.com/assets/42/dash/manifest.mpd",
                "https://cdn.example.com/hls/master.m3u8?token=a&b=c",
                "https://origin.example.com/assets/42/mp4/720.mp4",
                "https://origin.example.com/assets/42/opaque/stream",
            ),
            renditions.map { it.url },
        )
        assertEquals(listOf(MimeType.DASH, MimeType.HLS, MimeType.MP4, null), renditions.map { it.format })
        assertEquals("video", renditions[0].element)
        assertEquals("5000000", renditions[0].attributes["system-bitrate"])
        assertEquals("dash/manifest.mpd", renditions[0].attributes["src"])
        assertEquals(mapOf("drm" to "widevine"), renditions[0].params)
        assertEquals("HLS", renditions[1].attributes["title"])
        assertTrue(renditions[1].params.isEmpty())
    }

    @Test
    fun aMetaBaseSetsTheDocumentBase() {
        val platform = """<smil><head><meta base="https://cdn.example.net/vod/"/></head>
            <body><seq><ref src="a/master.m3u8" type="application/vnd.apple.mpegurl"/></seq></body></smil>"""
        val wowza = """<smil><head><meta name="httpBase" content="https://wowza.example.com/vod/"/></head>
            <body><switch><video src="sample.mpd"/></switch></body></smil>"""
        val smil10 = """<smil><head><meta name="base" content="/other/"/></head>
            <body><video src="b.m3u8"/></body></smil>"""

        assertEquals("https://cdn.example.net/vod/a/master.m3u8", parseSmil(platform, documentUrl).single().url)
        assertEquals("ref", parseSmil(platform, documentUrl).single().element)
        assertEquals("https://wowza.example.com/vod/sample.mpd", parseSmil(wowza, documentUrl).single().url)
        assertEquals("https://origin.example.com/other/b.m3u8", parseSmil(smil10, documentUrl).single().url)
    }

    @Test
    fun xmlBaseNestsPerElement() {
        val smil = """
            <smil xml:base="https://a.example.com/root/">
              <body>
                <par xml:base="sub/"><video src="x.m3u8"/></par>
                <video src="y.mpd"/>
              </body>
            </smil>
        """.trimIndent()

        assertEquals(
            listOf("https://a.example.com/root/sub/x.m3u8", "https://a.example.com/root/y.mpd"),
            parseSmil(smil, documentUrl).map { it.url },
        )
    }

    @Test
    fun aDoctypeIsSkippedAndItsEntitiesNeverExpanded() {
        val smil = """
            <?xml version="1.0"?>
            <!DOCTYPE smil [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
              <!ENTITY host "evil.example.com">
              <!-- a ] bracket and a ' quote, inside a comment -->
            ]>
            <smil><body><video src="https://cdn.example.com/&host;/&xxe;.m3u8"/></body></smil>
        """.trimIndent()

        assertEquals("https://cdn.example.com/&host;/&xxe;.m3u8", parseSmil(smil, documentUrl).single().url)
    }

    @Test
    fun expandsPredefinedEntitiesAndCharacterReferencesOnly() {
        val src = "https://e/caf&#xE9;/&#233;t&#233;&#x1F3AC;.m3u8?a=1&amp;b=&lt;&#0;&#xD800;"
        val smil = """<smil><body><video src="$src"/></body></smil>"""

        assertEquals("https://e/café/été🎬.m3u8?a=1&b=<&#0;&#xD800;", parseSmil(smil, documentUrl).single().url)
    }

    @Test
    fun commentsAndCdataHideWhatTheyContain() {
        val smil = """<smil><!-- <video src="commented.m3u8"/> --><![CDATA[<video src="cdata.m3u8"/>]]>
            <body><video src="real.m3u8"/></body></smil>"""

        assertEquals("https://origin.example.com/assets/42/real.m3u8", parseSmil(smil, documentUrl).single().url)
    }

    @Test
    fun readsPrefixedElementsByLocalName() {
        val smil = """<s:smil xmlns:s="http://www.w3.org/ns/SMIL"><s:body><s:video src="a.mpd"/></s:body></s:smil>"""

        assertEquals("video", parseSmil(smil, documentUrl).single().element)
    }

    @Test
    fun formatComesFromTheTypeAttributeBeforeTheExtension() {
        val smil = """<smil><body>
            <video src="https://e/master" type="Application/X-MpegURL; charset=utf-8"/>
            <video src="https://e/manifest.mpd?x=.m3u8" type="application/octet-stream"/>
            <video src="https://e/clip.WEBM"/>
            </body></smil>"""

        assertEquals(listOf(MimeType.HLS, MimeType.DASH, MimeType.WEBM), parseSmil(smil, documentUrl).map { it.format })
    }

    @Test
    fun anEmptyDocumentHasNoRenditions() {
        assertTrue(parseSmil("<smil/>", documentUrl).isEmpty())
        assertTrue(parseSmil("<smil><head/><body></body></smil>", documentUrl).isEmpty())
    }

    @Test
    fun rejectsWhatIsNotSmil() {
        assertFailsWith<SmilFormatException> {
            parseSmil("""<html><body><video src="a.mp4"></video></body></html>""", documentUrl)
        }
        assertFailsWith<SmilFormatException> { parseSmil("""{"renditions":[]}""", documentUrl) }
        assertFailsWith<SmilFormatException> { parseSmil("", documentUrl) }
    }

    @Test
    fun rejectsUnterminatedMarkup() {
        assertFailsWith<SmilFormatException> { parseSmil("""<smil><body><video src="a.m3u8""", documentUrl) }
        assertFailsWith<SmilFormatException> { parseSmil("""<smil><!-- never closed""", documentUrl) }
        assertFailsWith<SmilFormatException> { parseSmil("""<!DOCTYPE smil [ <smil/>""", documentUrl) }
    }

    @Test
    fun toleratesStrayEndTagsAndUnquotedValues() {
        val smil = """<smil><body></par><video src=plain.m3u8 /></switch></body></smil>"""

        val rendition = parseSmil(smil, documentUrl).single()
        assertEquals("https://origin.example.com/assets/42/plain.m3u8", rendition.url)
        assertNull(rendition.attributes["type"])
    }
}
