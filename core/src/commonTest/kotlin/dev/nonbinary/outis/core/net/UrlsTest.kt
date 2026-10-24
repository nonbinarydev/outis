/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class UrlsTest {

    // RFC 3986 §5.4's base and examples, verbatim.
    private val base = "http://a/b/c/d;p?q"

    @Test
    fun resolvesTheRfcNormalExamples() {
        mapOf(
            "g:h" to "g:h",
            "g" to "http://a/b/c/g",
            "./g" to "http://a/b/c/g",
            "g/" to "http://a/b/c/g/",
            "/g" to "http://a/g",
            "//g" to "http://g",
            "?y" to "http://a/b/c/d;p?y",
            "g?y" to "http://a/b/c/g?y",
            "#s" to "http://a/b/c/d;p?q#s",
            "g#s" to "http://a/b/c/g#s",
            "g?y#s" to "http://a/b/c/g?y#s",
            ";x" to "http://a/b/c/;x",
            "g;x" to "http://a/b/c/g;x",
            "g;x?y#s" to "http://a/b/c/g;x?y#s",
            "" to "http://a/b/c/d;p?q",
            "." to "http://a/b/c/",
            "./" to "http://a/b/c/",
            ".." to "http://a/b/",
            "../" to "http://a/b/",
            "../g" to "http://a/b/g",
            "../.." to "http://a/",
            "../../" to "http://a/",
            "../../g" to "http://a/g",
        ).forEach { (reference, expected) -> assertEquals(expected, resolveUrl(base, reference), "\"$reference\"") }
    }

    @Test
    fun resolvesTheRfcAbnormalExamples() {
        mapOf(
            "../../../g" to "http://a/g",
            "../../../../g" to "http://a/g",
            "/./g" to "http://a/g",
            "/../g" to "http://a/g",
            "g." to "http://a/b/c/g.",
            ".g" to "http://a/b/c/.g",
            "g.." to "http://a/b/c/g..",
            "..g" to "http://a/b/c/..g",
            "./../g" to "http://a/b/g",
            "./g/." to "http://a/b/c/g/",
            "g/./h" to "http://a/b/c/g/h",
            "g/../h" to "http://a/b/c/h",
            "g;x=1/./y" to "http://a/b/c/g;x=1/y",
            "g;x=1/../y" to "http://a/b/c/y",
            "g?y/./x" to "http://a/b/c/g?y/./x",
            "g?y/../x" to "http://a/b/c/g?y/../x",
            "g#s/./x" to "http://a/b/c/g#s/./x",
            "g#s/../x" to "http://a/b/c/g#s/../x",
            "http:g" to "http:g",
        ).forEach { (reference, expected) -> assertEquals(expected, resolveUrl(base, reference), "\"$reference\"") }
    }

    @Test
    fun trimsWhitespaceAroundAReference() {
        assertEquals(
            "https://cdn.example.com/a/master.m3u8",
            resolveUrl("https://cdn.example.com/a/x.smil", "\n  master.m3u8 "),
        )
    }

    @Test
    fun originIsSchemeHostAndPort() {
        assertEquals("https://cdn.example.com:443", originOf("https://CDN.Example.com/a/b.m3u8?x=1#y"))
        assertEquals(originOf("https://example.com/a"), originOf("https://example.com:443/b"))
        assertEquals(originOf("http://example.com/a"), originOf("HTTP://example.com:80/b"))
        assertNotEquals(originOf("https://example.com/a"), originOf("http://example.com/a"))
        assertNotEquals(originOf("https://example.com/a"), originOf("https://example.com:8443/a"))
        assertNotEquals(originOf("https://example.com/a"), originOf("https://cdn.example.com/a"))
        assertEquals("https://[::1]:8443", originOf("https://user:secret@[::1]:8443/x"))
        assertEquals("https://[::1]:443", originOf("https://[::1]/x"))
    }

    @Test
    fun aUrlWithoutAnAuthorityHasNoOrigin() {
        assertNull(originOf("/relative/path.m3u8"))
        assertNull(originOf("mp4:sample.mp4"))
    }

    @Test
    fun urlPathDropsTheQueryAndFragment() {
        assertEquals("https://e/a.smil", urlPath("https://e/a.smil?token=b.mp4#t=1"))
        assertEquals("https://e/a.mp4", urlPath("https://e/a.mp4#x?y"))
    }
}
