/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [ChapterExtractor] against hand-built container fixtures (real-file behaviour still needs
 * on-device validation, but these pin the byte-level parsing of each format).
 */
class ChapterExtractorTest {

    private class BytesReader(private val data: ByteArray) : ChapterReader {
        override val size: Long get() = data.size.toLong()
        override fun readAt(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= data.size || length <= 0) return ByteArray(0)
            val end = minOf(offset + length, data.size.toLong()).toInt()
            return data.copyOfRange(offset.toInt(), end)
        }
    }

    // ---- byte builders ----

    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be64(v: Long) = ByteArray(8) { ((v ushr ((7 - it) * 8)) and 0xFF).toByte() }
    private fun bytes(vararg parts: ByteArray): ByteArray {
        var out = ByteArray(0)
        for (p in parts) out += p
        return out
    }

    /** ISO-BMFF box. */
    private fun box(type: String, content: ByteArray): ByteArray =
        be32(8 + content.size) + type.encodeToByteArray() + content

    /** EBML element: raw id bytes + size vint + content (sizes < 0x7F → 1-byte vint). */
    private fun ebml(id: ByteArray, content: ByteArray): ByteArray {
        val n = content.size
        val sizeVint = if (n < 0x7F) {
            byteArrayOf((0x80 or n).toByte())
        } else {
            byteArrayOf((0x40 or (n ushr 8)).toByte(), n.toByte())
        }
        return id + sizeVint + content
    }

    // ---- MP4: Nero chpl ----

    @Test
    fun parses_nero_chpl_chapters() {
        fun entry(ms: Long, title: String): ByteArray {
            val t = title.encodeToByteArray()
            return be64(ms * 10_000) + byteArrayOf(t.size.toByte()) + t // start in 100ns units
        }
        val chplContent = bytes(
            byteArrayOf(1, 0, 0, 0), // version=1, flags
            ByteArray(4), // version != 0 → 4 reserved bytes
            byteArrayOf(2), // chapter count
            entry(0, "Intro"),
            entry(5_000, "Part Two"),
        )
        val file = bytes(
            box("ftyp", "isom".encodeToByteArray() + be32(0) + "isom".encodeToByteArray()),
            box("moov", box("udta", box("chpl", chplContent))),
        )

        val chapters = ChapterExtractor.extract(BytesReader(file))

        assertEquals(2, chapters.size)
        assertEquals(0, chapters[0].startMs)
        assertEquals("Intro", chapters[0].title)
        assertEquals(5_000, chapters[1].startMs)
        assertEquals("Part Two", chapters[1].title)
        assertEquals(5_000, chapters[0].endMs) // derived from next start
    }

    // ---- MP4: QuickTime `chap` text-track ----

    @Test
    fun parses_quicktime_chap_text_track() {
        // Title samples live in an `mdat` placed BEFORE moov so the chunk offset is a fixed file position.
        val s0 = be16("Intro".length) + "Intro".encodeToByteArray()
        val s1 = be16("Chapter 2".length) + "Chapter 2".encodeToByteArray()
        val ftyp = box("ftyp", "isom".encodeToByteArray() + be32(0) + "isom".encodeToByteArray())
        val mdat = box("mdat", s0 + s1)
        val sampleBaseOffset = ftyp.size + 8 // mdat content start

        fun tkhd(trackId: Int) = box("tkhd", bytes(byteArrayOf(0, 0, 0, 7), ByteArray(8), be32(trackId), ByteArray(60)))
        val videoTrak = box("trak", tkhd(1) + box("tref", box("chap", be32(2))))

        val mdhd = box("mdhd", bytes(byteArrayOf(0, 0, 0, 0), ByteArray(8), be32(1000), be32(6000), ByteArray(4)))
        val stts = box(
            "stts",
            bytes(be32(0), be32(2), be32(1) + be32(5000), be32(1) + be32(1000))
        ) // starts: 0, 5000 (ts=1000)
        val stsz = box("stsz", bytes(be32(0), be32(0), be32(2), be32(s0.size), be32(s1.size)))
        val stsc = box("stsc", bytes(be32(0), be32(1), be32(1) + be32(2) + be32(1))) // chunk1: 2 samples
        val stco = box("stco", bytes(be32(0), be32(1), be32(sampleBaseOffset)))
        val stbl = box("stbl", stts + stsz + stsc + stco)
        val textTrak = box("trak", tkhd(2) + box("mdia", mdhd + box("minf", stbl)))

        val moov = box("moov", videoTrak + textTrak)
        val file = ftyp + mdat + moov

        val chapters = ChapterExtractor.extract(BytesReader(file))

        assertEquals(2, chapters.size)
        assertEquals(0, chapters[0].startMs)
        assertEquals("Intro", chapters[0].title)
        assertEquals(5_000, chapters[1].startMs)
        assertEquals("Chapter 2", chapters[1].title)
    }

    // ---- Matroska: Chapters ----

    @Test
    fun parses_matroska_chapters() {
        fun atom(ns: Long, title: String) = ebml(
            byteArrayOf(0xB6.toByte()), // ChapterAtom
            ebml(byteArrayOf(0x91.toByte()), be64(ns)) + // ChapterTimeStart (ns)
                // Display→String
                ebml(byteArrayOf(0x80.toByte()), ebml(byteArrayOf(0x85.toByte()), title.encodeToByteArray())),
        )
        val editionEntry = ebml(byteArrayOf(0x45, 0xB9.toByte()), atom(0, "Opening") + atom(5_000_000_000L, "Middle"))
        val chapters = ebml(byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70), editionEntry)
        val segment = ebml(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), chapters)
        val header = ebml(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), ByteArray(0))
        val file = header + segment

        val result = ChapterExtractor.extract(BytesReader(file))

        assertEquals(2, result.size)
        assertEquals(0, result[0].startMs)
        assertEquals("Opening", result[0].title)
        assertEquals(5_000, result[1].startMs) // 5e9 ns → 5000 ms
        assertEquals("Middle", result[1].title)
    }

    // ---- robustness: untrusted counts must be bounded ----

    @Test
    fun huge_stts_sample_count_is_bounded() {
        // A chapter text track whose stts declares ~4.29 billion samples must not OOM/hang — just cap.
        val ftyp = box("ftyp", "isom".encodeToByteArray() + be32(0) + "isom".encodeToByteArray())
        val mdat = box("mdat", be16("X".length) + "X".encodeToByteArray())
        val sampleBaseOffset = ftyp.size + 8

        fun tkhd(trackId: Int) = box("tkhd", bytes(byteArrayOf(0, 0, 0, 7), ByteArray(8), be32(trackId), ByteArray(60)))
        val videoTrak = box("trak", tkhd(1) + box("tref", box("chap", be32(2))))
        val mdhd = box("mdhd", bytes(byteArrayOf(0, 0, 0, 0), ByteArray(8), be32(1000), be32(6000), ByteArray(4)))
        val stts = box("stts", bytes(be32(0), be32(1), be32(-1) + be32(1))) // sampleCount=0xFFFFFFFF, delta=1
        val stsz = box("stsz", bytes(be32(0), be32(0), be32(1), be32(7)))
        val stsc = box("stsc", bytes(be32(0), be32(1), be32(1) + be32(1) + be32(1)))
        val stco = box("stco", bytes(be32(0), be32(1), be32(sampleBaseOffset)))
        val stbl = box("stbl", stts + stsz + stsc + stco)
        val textTrak = box("trak", tkhd(2) + box("mdia", mdhd + box("minf", stbl)))
        val file = ftyp + mdat + box("moov", videoTrak + textTrak)

        val chapters = ChapterExtractor.extract(BytesReader(file))

        assertTrue(chapters.size <= 10_000) // bounded by MAX_SAMPLES — completes without OOM/hang
    }

    @Test
    fun huge_stco_entry_count_does_not_allocate() {
        // stco declaring a gigantic entry_count must be clamped by the box length, never LongArray(huge).
        val ftyp = box("ftyp", "isom".encodeToByteArray() + be32(0) + "isom".encodeToByteArray())
        fun tkhd(trackId: Int) = box("tkhd", bytes(byteArrayOf(0, 0, 0, 7), ByteArray(8), be32(trackId), ByteArray(60)))
        val videoTrak = box("trak", tkhd(1) + box("tref", box("chap", be32(2))))
        val mdhd = box("mdhd", bytes(byteArrayOf(0, 0, 0, 0), ByteArray(8), be32(1000), be32(6000), ByteArray(4)))
        val stts = box("stts", bytes(be32(0), be32(1), be32(1) + be32(1)))
        val stsz = box("stsz", bytes(be32(0), be32(0), be32(1), be32(7)))
        val stsc = box("stsc", bytes(be32(0), be32(1), be32(1) + be32(1) + be32(1)))
        val stco = box("stco", bytes(be32(0), be32(0x40000000))) // claims ~1.07B entries, holds none
        val stbl = box("stbl", stts + stsz + stsc + stco)
        val textTrak = box("trak", tkhd(2) + box("mdia", mdhd + box("minf", stbl)))
        val file = ftyp + box("moov", videoTrak + textTrak)

        // Must return (no OOM) — clamped to the 0 entries the box actually contains.
        assertTrue(ChapterExtractor.extract(BytesReader(file)).size <= 1)
    }

    // ---- Matroska: editions are alternatives, present exactly one ----

    private fun mkvAtom(ns: Long, title: String) = ebml(
        byteArrayOf(0xB6.toByte()),
        ebml(byteArrayOf(0x91.toByte()), be64(ns)) +
            ebml(byteArrayOf(0x80.toByte()), ebml(byteArrayOf(0x85.toByte()), title.encodeToByteArray())),
    )

    private fun mkvFile(chaptersContent: ByteArray): ByteArray {
        val chapters = ebml(byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70), chaptersContent)
        val segment = ebml(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), chapters)
        val header = ebml(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), ByteArray(0))
        return header + segment
    }

    @Test
    fun matroska_picks_default_edition_only() {
        val flagDefault = ebml(byteArrayOf(0x45, 0xDB.toByte()), byteArrayOf(1)) // EditionFlagDefault = 1
        val editionA = ebml(byteArrayOf(0x45, 0xB9.toByte()), mkvAtom(0, "A-Intro") + mkvAtom(3_000_000_000L, "A-Mid"))
        val editionB =
            ebml(
                byteArrayOf(0x45, 0xB9.toByte()),
                flagDefault + mkvAtom(0, "B-Intro") + mkvAtom(5_000_000_000L, "B-End")
            )

        val result = ChapterExtractor.extract(BytesReader(mkvFile(editionA + editionB)))

        assertEquals(2, result.size) // only the default edition (B), not the union of both
        assertEquals("B-Intro", result[0].title)
        assertEquals("B-End", result[1].title)
    }

    @Test
    fun matroska_uses_first_edition_when_none_default() {
        val editionA = ebml(byteArrayOf(0x45, 0xB9.toByte()), mkvAtom(0, "A-Intro") + mkvAtom(3_000_000_000L, "A-Mid"))
        val editionB = ebml(byteArrayOf(0x45, 0xB9.toByte()), mkvAtom(0, "B-Intro") + mkvAtom(5_000_000_000L, "B-End"))

        val result = ChapterExtractor.extract(BytesReader(mkvFile(editionA + editionB)))

        assertEquals(2, result.size)
        assertEquals("A-Intro", result[0].title)
        assertEquals(3_000, result[1].startMs)
        assertEquals("A-Mid", result[1].title)
    }

    // ---- MP4 chapter thumbnails (image track) + handler-based track selection ----

    /** A chapter track (text or image) with a single-chunk sample table; samples sit at [baseOffset]. */
    private fun chapTrack(
        trackId: Int,
        handler: String,
        timescale: Int,
        sizes: List<Int>,
        deltas: List<Int>,
        baseOffset: Int
    ): ByteArray {
        val tkhd = box("tkhd", bytes(byteArrayOf(0, 0, 0, 7), ByteArray(8), be32(trackId), ByteArray(60)))
        val hdlr = box("hdlr", bytes(be32(0), be32(0), handler.encodeToByteArray(), ByteArray(12), byteArrayOf(0)))
        val mdhd =
            box("mdhd", bytes(byteArrayOf(0, 0, 0, 0), ByteArray(8), be32(timescale), be32(60_000), ByteArray(4)))
        var sttsEntries = ByteArray(0)
        for (d in deltas) sttsEntries += be32(1) + be32(d)
        val stts = box("stts", bytes(be32(0), be32(deltas.size)) + sttsEntries)
        var stszEntries = ByteArray(0)
        for (s in sizes) stszEntries += be32(s)
        val stsz = box("stsz", bytes(be32(0), be32(0), be32(sizes.size)) + stszEntries)
        val stsc = box("stsc", bytes(be32(0), be32(1), be32(1) + be32(sizes.size) + be32(1))) // 1 chunk, all samples
        val stco = box("stco", bytes(be32(0), be32(1), be32(baseOffset)))
        val stbl = box("stbl", stts + stsz + stsc + stco)
        return box("trak", tkhd + box("mdia", hdlr + mdhd + box("minf", stbl)))
    }

    private val jpg0 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
    private val jpg1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x33, 0x44, 0x55, 0xFF.toByte(), 0xD9.toByte())

    /** ftyp + mdat(titles + jpegs) + moov whose main track references BOTH an image and a text chapter track,
     *  with the IMAGE track placed first in moov order to exercise handler-based selection. */
    private fun dualChapterFile(): ByteArray {
        val t0 = be16("Intro".length) + "Intro".encodeToByteArray()
        val t1 = be16("Part Two".length) + "Part Two".encodeToByteArray()
        val ftyp = box("ftyp", "isom".encodeToByteArray() + be32(0) + "isom".encodeToByteArray())
        val mdat = box("mdat", t0 + t1 + jpg0 + jpg1)
        val textBase = ftyp.size + 8
        val jpgBase = textBase + t0.size + t1.size
        val mainTrak = box(
            "trak",
            box("tkhd", bytes(byteArrayOf(0, 0, 0, 7), ByteArray(8), be32(1), ByteArray(60))) +
                box("tref", box("chap", be32(3) + be32(2)))
        ) // references image (3) THEN text (2)
        val imageTrak = chapTrack(3, "vide", 1000, listOf(jpg0.size, jpg1.size), listOf(5000, 1000), jpgBase)
        val textTrak = chapTrack(2, "text", 1000, listOf(t0.size, t1.size), listOf(5000, 1000), textBase)
        return ftyp + mdat + box("moov", mainTrak + imageTrak + textTrak) // image trak before text trak
    }

    @Test
    fun picks_text_track_by_handler_not_first_referenced() {
        // Image track is referenced first; titles must still come from the TEXT track (not decoded jpeg bytes).
        val chapters = ChapterExtractor.extract(BytesReader(dualChapterFile()))

        assertEquals(2, chapters.size)
        assertEquals("Intro", chapters[0].title)
        assertEquals("Part Two", chapters[1].title)
        assertEquals(null, chapters[0].thumbnail) // thumbnails not requested
    }

    @Test
    fun extracts_chapter_thumbnails_when_requested() {
        val chapters = ChapterExtractor.extract(BytesReader(dualChapterFile()), includeThumbnails = true)

        assertEquals(2, chapters.size)
        assertEquals("Intro", chapters[0].title)
        assertTrue(jpg0.contentEquals(chapters[0].thumbnail))
        assertTrue(jpg1.contentEquals(chapters[1].thumbnail))
    }

    @Test
    fun thumbnails_not_duplicated_when_image_count_differs() {
        // 3 text chapters but only 2 image samples → the 3rd chapter gets null, not a duplicate of the 2nd.
        val t0 = be16("A".length) + "A".encodeToByteArray()
        val t1 = be16("B".length) + "B".encodeToByteArray()
        val t2 = be16("C".length) + "C".encodeToByteArray()
        val jA = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0xFF.toByte(), 0xD9.toByte())
        val jB = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x02, 0x03, 0xFF.toByte(), 0xD9.toByte())
        val ftyp = box("ftyp", "isom".encodeToByteArray() + be32(0) + "isom".encodeToByteArray())
        val mdat = box("mdat", t0 + t1 + t2 + jA + jB)
        val textBase = ftyp.size + 8
        val jpgBase = textBase + t0.size + t1.size + t2.size
        val mainTrak = box(
            "trak",
            box("tkhd", bytes(byteArrayOf(0, 0, 0, 7), ByteArray(8), be32(1), ByteArray(60))) +
                box("tref", box("chap", be32(3) + be32(2)))
        )
        val imageTrak = chapTrack(
            3,
            "vide",
            1000,
            listOf(jA.size, jB.size),
            listOf(5000, 1000),
            jpgBase
        ) // 2 @ 0,5000ms
        val textTrak = chapTrack(
            2,
            "text",
            1000,
            listOf(t0.size, t1.size, t2.size),
            listOf(5000, 5000, 1000),
            textBase
        ) // 3 @ 0,5000,10000ms
        val file = ftyp + mdat + box("moov", mainTrak + imageTrak + textTrak)

        val chapters = ChapterExtractor.extract(BytesReader(file), includeThumbnails = true)

        assertEquals(3, chapters.size)
        assertTrue(jA.contentEquals(chapters[0].thumbnail))
        assertTrue(jB.contentEquals(chapters[1].thumbnail))
        assertEquals(null, chapters[2].thumbnail) // not a duplicate of jB
    }

    @Test
    fun thumbnails_stay_null_when_absent_even_if_requested() {
        // MKV (and any title-only MP4) has no image track → thumbnails stay null with includeThumbnails=true.
        val mkv =
            mkvFile(ebml(byteArrayOf(0x45, 0xB9.toByte()), mkvAtom(0, "Opening") + mkvAtom(5_000_000_000L, "Middle")))
        val mkvChapters = ChapterExtractor.extract(BytesReader(mkv), includeThumbnails = true)

        assertEquals(2, mkvChapters.size)
        assertTrue(mkvChapters.all { it.thumbnail == null })
    }

    @Test
    fun garbage_and_empty_return_no_chapters() {
        assertTrue(ChapterExtractor.extract(BytesReader(ByteArray(0))).isEmpty())
        assertTrue(ChapterExtractor.extract(BytesReader(ByteArray(64) { 0x7F })).isEmpty())
        // A valid-ish mp4 with no chapter structures.
        val noChapters = box("ftyp", "isom".encodeToByteArray()) + box("moov", box("udta", ByteArray(0)))
        assertTrue(ChapterExtractor.extract(BytesReader(noChapters)).isEmpty())
    }
}
