/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

/**
 * Extracts embedded chapter markers from a local MP4/M4V or Matroska file by parsing just the relevant
 * container structures over a [ChapterReader] (small ranged reads; never loads the whole file). Pure
 * Kotlin so it lives in `commonMain` and is unit-tested on the JVM. Wired on Android (Media3 has no
 * chapter API) and iOS (over a POSIX-backed reader), both reading the local file; web leaves chapters
 * empty (no local-file API).
 *
 * Supported:
 * - **MP4/M4V** — QuickTime `chap` text-track chapters (the iTunes/`m4v` form) with Nero `chpl` fallback.
 * - **Matroska (mkv)** — the `Chapters` element (EBML).
 *
 * Any parse failure yields an empty list — chapters must never break playback.
 */
object ChapterExtractor {

    // Backstops against malformed/adversarial files: declared counts are untrusted (32-bit), so every
    // table is clamped by the box's own byte length AND these hard caps before allocating or looping.
    private const val MAX_SAMPLES = 10_000 // a chapter text track realistically has < 1000 samples
    private const val MAX_TABLE_ENTRIES = 1_000_000 // stco/stsc/stts entry-count ceiling (≤8MB LongArray)
    private const val MAX_TITLE_BYTES = 64 * 1024 // a chapter title sample is short; bound the read
    private const val MAX_THUMBNAIL_BYTES = 4 * 1024 * 1024 // bound a single chapter preview image read
    private const val MAX_THUMBNAILS = 512 // cap how many preview images we retain in player state
    private const val MAX_TOTAL_THUMBNAIL_BYTES = 32L * 1024 * 1024 // aggregate ceiling across all thumbnails
    private const val THUMB_MATCH_TOLERANCE_MS = 2_000L // non-1:1 fallback: don't bind a far-off image sample
    private const val MS_PER_SECOND = 1000 // sample times are in the track timescale; Chapter is in ms

    /**
     * Parse chapter markers. With [includeThumbnails], also pull per-chapter preview images from an MP4
     * chapter image track (when present) into [Chapter.thumbnail]; otherwise (and always for Matroska /
     * title-only files) thumbnails stay `null`.
     */
    fun extract(reader: ChapterReader, includeThumbnails: Boolean = false): List<Chapter> {
        if (reader.size < 8) return emptyList()
        return try {
            val head = reader.readAt(0, 4)
            val result = when {
                head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                    head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> parseMatroska(reader)
                looksLikeMp4(reader) -> parseMp4(reader, includeThumbnails)
                else -> emptyList()
            }
            finalize(result)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun looksLikeMp4(reader: ChapterReader): Boolean {
        // The first top-level box is normally `ftyp`; some files lead with another box, so also accept a
        // plausible box chain whose first type is a 4-char ascii box.
        val type = reader.fourcc(4)
        return type == "ftyp" || type == "moov" || type == "free" || type == "skip" || type == "mdat" || type == "wide"
    }

    private fun finalize(list: List<Chapter>): List<Chapter> {
        val sorted = list.filter { it.startMs >= 0 }
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }
        return sorted.mapIndexed { i, c -> if (i + 1 < sorted.size) c.copy(endMs = sorted[i + 1].startMs) else c }
    }

    // ---------------------------------------------------------------- MP4 / M4V (ISO-BMFF) ----

    private class Box(val type: String, val start: Long, val end: Long) // content range [start, end)

    private fun children(reader: ChapterReader, start: Long, end: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = start
        while (pos + 8 <= end) {
            val size32 = reader.u32(pos)
            val type = reader.fourcc(pos + 4)
            var header = 8L
            var boxSize = size32
            when {
                size32 == 1L -> {
                    boxSize = reader.u64(pos + 8)
                    header = 16L
                }
                size32 == 0L -> boxSize = end - pos // extends to the end of the parent
            }
            if (boxSize < header || pos + boxSize > end) break
            out.add(Box(type, pos + header, pos + boxSize))
            pos += boxSize
        }
        return out
    }

    private fun List<Box>.find(type: String): Box? = firstOrNull { it.type == type }

    /** Clamp an untrusted entry count to what the box can actually hold and a hard cap — never over-allocate. */
    private fun boundedCount(declared: Long, availableBytes: Long, bytesPerEntry: Int): Int {
        if (declared <= 0 || availableBytes <= 0) return 0
        return minOf(declared, availableBytes / bytesPerEntry, MAX_TABLE_ENTRIES.toLong()).toInt().coerceAtLeast(0)
    }

    private fun parseMp4(reader: ChapterReader, includeThumbnails: Boolean): List<Chapter> {
        val moov = children(reader, 0, reader.size).find("moov") ?: return emptyList()
        val moovChildren = children(reader, moov.start, moov.end)
        val traks = moovChildren.filter { it.type == "trak" }

        parseQuickTimeChapters(reader, traks, includeThumbnails).let { if (it.isNotEmpty()) return it }

        val udta = moovChildren.find("udta") ?: return emptyList()
        val chpl = children(reader, udta.start, udta.end).find("chpl") ?: return emptyList()
        return parseNeroChpl(reader, chpl) // Nero chpl carries no thumbnails
    }

    /**
     * Nero `chpl`: version(1) flags(3) [+4 if version!=0] count(1), then
     * count×{start(u64, 100ns) len(1) title(utf8)}.
     */
    private fun parseNeroChpl(reader: ChapterReader, chpl: Box): List<Chapter> {
        val version = reader.u8(chpl.start)
        var p = chpl.start + 4
        if (version != 0) p += 4
        val count = reader.u8(p)
        p += 1
        if (count <= 0) return emptyList()
        val out = ArrayList<Chapter>(count)
        for (i in 0 until count) {
            if (p + 9 > chpl.end) break
            val start100ns = reader.u64(p)
            p += 8
            val len = reader.u8(p)
            p += 1
            if (len < 0 || p + len > chpl.end) break
            val title = reader.bytes(p, len).decodeToString()
            p += len
            out.add(Chapter(startMs = start100ns / 10_000, title = title.ifBlank { null }))
        }
        return out
    }

    private fun parseQuickTimeChapters(
        reader: ChapterReader,
        traks: List<Box>,
        includeThumbnails: Boolean,
    ): List<Chapter> {
        val chapterTrackIds = HashSet<Long>()
        for (trak in traks) {
            val tref = children(reader, trak.start, trak.end).find("tref") ?: continue
            val chap = children(reader, tref.start, tref.end).find("chap") ?: continue
            var p = chap.start
            while (p + 4 <= chap.end) {
                chapterTrackIds.add(reader.u32(p))
                p += 4
            }
        }
        if (chapterTrackIds.isEmpty()) return emptyList()
        val referenced = traks.filter { trackId(reader, it) in chapterTrackIds }
        // A `chap` reference can list BOTH a text (titles) track and a video (thumbnails) track. Pick the
        // text/subtitle track for titles by handler type — never decode the image track's samples as text.
        val textTrak = referenced.firstOrNull { handlerType(reader, it).isTextHandler() }
            ?: referenced.firstOrNull { handlerType(reader, it) != "vide" }
            ?: return emptyList()
        val chapters = parseChapterTextTrack(reader, textTrak)
        if (!includeThumbnails || chapters.isEmpty()) return chapters
        val imageTrak = referenced.firstOrNull { handlerType(reader, it) == "vide" } ?: return chapters
        return attachThumbnails(reader, chapters, readChapterSamples(reader, imageTrak))
    }

    // Title-track media handlers: QuickTime/3GPP timed text (`text`), ISO subtitle (`sbtl`/`subt`). Note
    // `tx3g` is a sample-entry fourcc, not a handler, so it must NOT appear here.
    private fun String?.isTextHandler() = this == "text" || this == "sbtl" || this == "subt"

    /** The media handler type (`hdlr`) of a track — `text`/`sbtl`/`subt` for titles, `vide` for thumbnails. */
    private fun handlerType(reader: ChapterReader, trak: Box): String? {
        val mdia = children(reader, trak.start, trak.end).find("mdia") ?: return null
        val hdlr = children(reader, mdia.start, mdia.end).find("hdlr") ?: return null
        return reader.fourcc(hdlr.start + 8) // version(1) flags(3) pre_defined(4), then handler_type fourcc
    }

    private fun trackId(reader: ChapterReader, trak: Box): Long? {
        val tkhd = children(reader, trak.start, trak.end).find("tkhd") ?: return null
        val version = reader.u8(tkhd.start)
        val off = tkhd.start + 4 + if (version == 1) 16 else 8 // fullbox + creation + modification
        return reader.u32(off)
    }

    /**
     * Read each chapter's preview image into [Chapter.thumbnail]. The canonical QuickTime layout is a 1:1,
     * co-timed image track, so when the counts match we pair by **ordinal**; otherwise we fall back to the
     * nearest start time, never reusing an image sample and only within [THUMB_MATCH_TOLERANCE_MS] (so a
     * chapter with no co-timed image keeps `null` instead of borrowing a neighbour's). Reads are bounded per
     * sample ([MAX_THUMBNAIL_BYTES]) and in aggregate ([MAX_THUMBNAILS] / [MAX_TOTAL_THUMBNAIL_BYTES]) so a
     * pathological file can't pin unbounded memory in player state.
     */
    private fun attachThumbnails(
        reader: ChapterReader,
        chapters: List<Chapter>,
        samples: List<RawSample>
    ): List<Chapter> {
        if (samples.isEmpty()) return chapters
        val byOrdinal = samples.size == chapters.size
        val used = BooleanArray(samples.size)
        var count = 0
        var totalBytes = 0L
        return chapters.mapIndexed { i, ch ->
            if (count >= MAX_THUMBNAILS || totalBytes >= MAX_TOTAL_THUMBNAIL_BYTES) return@mapIndexed ch
            val idx = if (byOrdinal) i else nearestUnusedSample(samples, used, ch.startMs)
            val bytes = idx?.let { samples[it] }
                ?.takeIf { it.size in 1..MAX_THUMBNAIL_BYTES && it.offset > 0 && it.offset + it.size <= reader.size }
                ?.let { reader.bytes(it.offset, it.size) }
                ?.takeIf { it.isNotEmpty() }
            if (bytes == null) {
                ch
            } else {
                used[idx] = true
                count++
                totalBytes += bytes.size
                ch.copy(thumbnail = bytes)
            }
        }
    }

    /** Index of the unused image sample nearest [t], or null if none lies within [THUMB_MATCH_TOLERANCE_MS]. */
    private fun nearestUnusedSample(samples: List<RawSample>, used: BooleanArray, t: Long): Int? {
        var best = -1
        var bestDist = Long.MAX_VALUE
        for (i in samples.indices) {
            if (used[i]) continue
            val d = if (samples[i].startMs >= t) samples[i].startMs - t else t - samples[i].startMs
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return if (best >= 0 && bestDist <= THUMB_MATCH_TOLERANCE_MS) best else null
    }

    private class RawSample(val startMs: Long, val offset: Long, val size: Int)

    /** Decode a chapter text track's samples into titled [Chapter]s (start time + decoded title). */
    private fun parseChapterTextTrack(reader: ChapterReader, trak: Box): List<Chapter> =
        readChapterSamples(reader, trak).map { s ->
            // Only read a title from an in-bounds, sanely-sized sample; a truncated/unmapped (0) offset → no title.
            val title = if (s.size in 2..MAX_TITLE_BYTES && s.offset > 0 && s.offset + s.size <= reader.size) {
                decodeChapterSampleText(reader.bytes(s.offset, s.size))
            } else {
                null
            }
            Chapter(startMs = s.startMs, title = title?.ifBlank { null })
        }

    /**
     * Read a chapter track's samples (start time + file offset + byte size) from its sample tables
     * (stts/stsz/stsc/stco/co64). Shared by the text (title) track and the image (thumbnail) track.
     */
    // Nine guard clauses, one per required sample-table box. detekt's excludeGuardClauses only
    // recognises an unbroken run of them, and these are interleaved with the reads they guard.
    @Suppress("ReturnCount")
    private fun readChapterSamples(reader: ChapterReader, trak: Box): List<RawSample> {
        val mdia = children(reader, trak.start, trak.end).find("mdia") ?: return emptyList()
        val mdiaChildren = children(reader, mdia.start, mdia.end)
        val mdhd = mdiaChildren.find("mdhd") ?: return emptyList()
        val mdhdVersion = reader.u8(mdhd.start)
        val timescale = reader.u32(mdhd.start + 4 + if (mdhdVersion == 1) 16 else 8)
        if (timescale <= 0) return emptyList()
        val minf = mdiaChildren.find("minf") ?: return emptyList()
        val stbl = children(reader, minf.start, minf.end).find("stbl") ?: return emptyList()
        val stblChildren = children(reader, stbl.start, stbl.end)
        val stts = stblChildren.find("stts") ?: return emptyList()
        val stsz = stblChildren.find("stsz") ?: return emptyList()
        val stsc = stblChildren.find("stsc") ?: return emptyList()
        val stco = stblChildren.firstOrNull { it.type == "stco" || it.type == "co64" } ?: return emptyList()

        val startTimes = readSampleStartTimes(reader, stts)
        val n = startTimes.size
        if (n == 0) return emptyList()
        val sizes = readSampleSizes(reader, stsz, n)
        val chunkOffsets = readChunkOffsets(reader, stco)
        val sampleOffsets = mapSamplesToOffsets(reader, stsc, chunkOffsets, sizes, n)

        return List(n) { i ->
            RawSample(
                startMs = startTimes[i] * MS_PER_SECOND / timescale,
                offset = sampleOffsets[i],
                size = sizes[i].toInt(),
            )
        }
    }

    /**
     * Sample start times from `stts`, as cumulative deltas in the track timescale. Entry counts come
     * from the file and are untrusted, so they are bounded by the box length and by [MAX_SAMPLES] —
     * a malformed count must not be allowed to allocate without limit or spin.
     */
    private fun readSampleStartTimes(reader: ChapterReader, stts: Box): List<Long> {
        val startTimes = ArrayList<Long>()
        val entries = boundedCount(reader.u32(stts.start + 4), stts.end - (stts.start + 8), 8)
        var p = stts.start + 8
        var t = 0L
        var i = 0
        while (i < entries && startTimes.size < MAX_SAMPLES) {
            if (p + 8 > stts.end) break
            val sampleCount = reader.u32(p)
            val delta = reader.u32(p + 4)
            p += 8
            var j = 0L
            while (j < sampleCount && startTimes.size < MAX_SAMPLES) {
                startTimes.add(t)
                t += delta
                j++
            }
            i++
        }
        return startTimes
    }

    /**
     * Sample sizes from `stsz`, either the single fixed size or the per-sample table. Samples beyond
     * what the box actually contains keep size 0 — which yields no title — rather than reading past it.
     */
    private fun readSampleSizes(reader: ChapterReader, stsz: Box, n: Int): LongArray {
        val sizes = LongArray(n)
        val sampleSize = reader.u32(stsz.start + 4)
        if (sampleSize != 0L) {
            for (i in 0 until n) sizes[i] = sampleSize
            return sizes
        }
        val avail = boundedCount(reader.u32(stsz.start + 8), stsz.end - (stsz.start + 12), 4)
        var p = stsz.start + 12
        for (i in 0 until minOf(n, avail)) {
            sizes[i] = reader.u32(p)
            p += 4
        }
        return sizes
    }

    /** Chunk file offsets from `stco` (32-bit) or `co64` (64-bit), clamped before allocating. */
    private fun readChunkOffsets(reader: ChapterReader, stco: Box): LongArray {
        val is64 = stco.type == "co64"
        val width = if (is64) 8 else 4
        val cnt = boundedCount(reader.u32(stco.start + 4), stco.end - (stco.start + 8), width)
        val arr = LongArray(cnt)
        var p = stco.start + 8
        for (i in 0 until cnt) {
            arr[i] = if (is64) reader.u64(p) else reader.u32(p)
            p += width
        }
        return arr
    }

    /**
     * Resolve each sample's absolute file offset by walking the `stsc` sample-to-chunk table: samples
     * are laid out consecutively within a chunk, so each one starts where the previous one ended.
     */
    private fun mapSamplesToOffsets(
        reader: ChapterReader,
        stsc: Box,
        chunkOffsets: LongArray,
        sizes: LongArray,
        n: Int,
    ): LongArray {
        val sampleOffsets = LongArray(n)
        val entryCount = boundedCount(reader.u32(stsc.start + 4), stsc.end - (stsc.start + 8), 12)
        val firstChunks = LongArray(entryCount)
        val perChunk = LongArray(entryCount)
        var p = stsc.start + 8
        for (i in 0 until entryCount) {
            firstChunks[i] = reader.u32(p)
            perChunk[i] = reader.u32(p + 4)
            p += 12
        }
        var sample = 0
        for (e in 0 until entryCount) {
            val firstChunk = firstChunks[e].toInt()
            val samplesPerChunk = perChunk[e].toInt()
            val lastChunk = if (e + 1 < entryCount) firstChunks[e + 1].toInt() - 1 else chunkOffsets.size
            for (chunk in firstChunk..lastChunk) {
                val ci = chunk - 1
                if (ci !in chunkOffsets.indices) break
                var off = chunkOffsets[ci]
                var s = 0
                while (s < samplesPerChunk && sample < n) {
                    sampleOffsets[sample] = off
                    off += sizes[sample]
                    sample++
                    s++
                }
                if (sample >= n) break
            }
            if (sample >= n) break
        }
        return sampleOffsets
    }

    /** A QuickTime text sample: 2-byte big-endian length, then the title (UTF-8, or UTF-16 with a BOM). */
    private fun decodeChapterSampleText(data: ByteArray): String? {
        if (data.size < 2) return null
        val len = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val end = minOf(2 + len, data.size)
        if (end <= 2) return null
        val text = data.copyOfRange(2, end)
        return when {
            text.size >= 2 && text[0] == 0xFE.toByte() && text[1] == 0xFF.toByte() -> decodeUtf16(
                text,
                2,
                bigEndian = true
            )
            text.size >= 2 && text[0] == 0xFF.toByte() && text[1] == 0xFE.toByte() -> decodeUtf16(
                text,
                2,
                bigEndian = false
            )
            else -> text.decodeToString()
        }
    }

    /** Minimal BMP UTF-16 decode (chapter titles are simple text); surrogate pairs fall back gracefully. */
    private fun decodeUtf16(bytes: ByteArray, from: Int, bigEndian: Boolean): String {
        val sb = StringBuilder()
        var i = from
        while (i + 1 < bytes.size) {
            val hi = bytes[if (bigEndian) i else i + 1].toInt() and 0xFF
            val lo = bytes[if (bigEndian) i + 1 else i].toInt() and 0xFF
            sb.append(((hi shl 8) or lo).toChar())
            i += 2
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- Matroska (EBML) ----

    private class Elem(val contentStart: Long, val contentEnd: Long)

    private fun parseMatroska(reader: ChapterReader): List<Chapter> {
        val segment = findElement(reader, 0, reader.size, 0x18538067L) ?: return emptyList()
        val chapters = findElement(reader, segment.contentStart, segment.contentEnd, 0x1043A770L) ?: return emptyList()
        // Editions are ALTERNATIVE chapterings of the same content, not additive — present exactly one:
        // the default edition (EditionFlagDefault == 1) if any is marked, else the first.
        val editions = collectElements(reader, chapters.contentStart, chapters.contentEnd, 0x45B9L)
        val chosen = editions.firstOrNull {
            findElement(reader, it.contentStart, it.contentEnd, 0x45DBL)?.let { f -> readUInt(reader, f) == 1L } == true
        } ?: editions.firstOrNull()
        // Fall back to scanning directly under Chapters if a (malformed) file omits the EditionEntry wrapper.
        val region = chosen ?: Elem(chapters.contentStart, chapters.contentEnd)
        val out = ArrayList<Chapter>()
        collectChapterAtoms(reader, region.contentStart, region.contentEnd, out)
        return out
    }

    private fun collectChapterAtoms(reader: ChapterReader, start: Long, end: Long, out: MutableList<Chapter>) {
        walkElements(reader, start, end) { id, cs, ce ->
            if (id == 0xB6L) { // ChapterAtom
                val timeStart = findElement(reader, cs, ce, 0x91L)?.let { readUInt(reader, it) }
                if (timeStart != null) {
                    val title = findElement(reader, cs, ce, 0x80L)?.let { display -> // ChapterDisplay
                        findElement(
                            reader,
                            display.contentStart,
                            display.contentEnd,
                            0x85L
                        )?.let { readString(reader, it) } // ChapString
                    }
                    out.add(Chapter(startMs = timeStart / 1_000_000, title = title?.ifBlank { null }))
                }
                collectChapterAtoms(reader, cs, ce, out) // nested (sub-)chapters
            }
        }
    }

    private inline fun walkElements(
        reader: ChapterReader,
        start: Long,
        end: Long,
        onEach: (id: Long, cs: Long, ce: Long) -> Unit,
    ) {
        var pos = start
        while (pos < end) {
            val (id, idLen) = readVint(reader, pos, keepMarker = true)
            if (idLen == 0 || idLen > 4) break
            val (size, sizeLen) = readVint(reader, pos + idLen, keepMarker = false)
            if (sizeLen == 0) break
            val contentStart = pos + idLen + sizeLen
            // Unknown-size (live/streamed mkv) is treated as running to the parent end. Known limitation:
            // if such an element is followed by more siblings, those siblings are not walked.
            val contentEnd = if (size < 0) end else minOf(contentStart + size, end)
            onEach(id, contentStart, contentEnd)
            pos = contentEnd
        }
    }

    /** All direct child elements with [target] id in `[start, end)`. */
    private fun collectElements(reader: ChapterReader, start: Long, end: Long, target: Long): List<Elem> {
        val out = ArrayList<Elem>()
        walkElements(reader, start, end) { id, cs, ce -> if (id == target) out.add(Elem(cs, ce)) }
        return out
    }

    private fun findElement(reader: ChapterReader, start: Long, end: Long, target: Long): Elem? {
        var found: Elem? = null
        var pos = start
        while (pos < end && found == null) {
            val (id, idLen) = readVint(reader, pos, keepMarker = true)
            if (idLen == 0 || idLen > 4) break
            val (size, sizeLen) = readVint(reader, pos + idLen, keepMarker = false)
            if (sizeLen == 0) break
            val contentStart = pos + idLen + sizeLen
            val contentEnd = if (size < 0) end else minOf(contentStart + size, end)
            if (id == target) found = Elem(contentStart, contentEnd)
            pos = contentEnd
        }
        return found
    }

    /**
     * Read an EBML variable-length integer at [off]. With [keepMarker] the length-descriptor bits are kept
     * (for element IDs, compared by their canonical value); without, they're cleared (for sizes — all-ones
     * means "unknown size", returned as -1). Returns value (-1 on unknown/invalid) and byte length (0 = bad).
     */
    // Six returns: three malformed-input guards (EOF or 0x00 lead byte, no length marker in the lead
    // byte, truncated mid-vint — the last appearing once per branch) plus the two success paths for
    // keepMarker on and off. Every one bails on a distinct malformed shape, so folding them into a
    // single exit would mean tracking the failure in a flag and re-testing it at the bottom.
    @Suppress("ReturnCount")
    private fun readVint(reader: ChapterReader, off: Long, keepMarker: Boolean): Pair<Long, Int> {
        val first = reader.u8(off)
        if (first <= 0) return -1L to 0 // EOF (-1) or a 0x00 lead byte (invalid)
        var mask = 0x80
        var len = 1
        while (mask != 0 && (first and mask) == 0) {
            mask = mask shr 1
            len++
        }
        if (mask == 0) return -1L to 0
        if (keepMarker) {
            var value = first.toLong()
            for (i in 1 until len) {
                val b = reader.u8(off + i)
                if (b < 0) return -1L to 0 // truncated mid-vint → signal "bad"; callers break
                value = (value shl 8) or b.toLong()
            }
            return value to len
        }
        val dataMask = mask - 1
        var value = (first and dataMask).toLong()
        var allOnes = (first and dataMask) == dataMask
        for (i in 1 until len) {
            val b = reader.u8(off + i)
            if (b < 0) return -1L to 0 // truncated mid-vint → "bad", not a fabricated unknown-size
            value = (value shl 8) or b.toLong()
            if (b != 0xFF) allOnes = false
        }
        return (if (allOnes) -1L else value) to len
    }

    private fun readUInt(reader: ChapterReader, elem: Elem): Long {
        var value = 0L
        var p = elem.contentStart
        while (p < elem.contentEnd) {
            value = (value shl 8) or reader.u8(p).toLong()
            p++
        }
        return value
    }

    private fun readString(reader: ChapterReader, elem: Elem): String {
        val len = (elem.contentEnd - elem.contentStart).toInt()
        if (len <= 0) return ""
        return reader.bytes(elem.contentStart, len).decodeToString()
    }

    // ---------------------------------------------------------------- byte helpers ----

    private fun ChapterReader.u8(off: Long): Int {
        val b = readAt(off, 1)
        return if (b.isEmpty()) -1 else b[0].toInt() and 0xFF
    }

    private fun ChapterReader.u32(off: Long): Long {
        val b = readAt(off, 4)
        if (b.size < 4) return 0
        return ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
    }

    private fun ChapterReader.u64(off: Long): Long {
        val hi = u32(off)
        val lo = u32(off + 4)
        return (hi shl 32) or (lo and 0xFFFFFFFFL)
    }

    private fun ChapterReader.bytes(off: Long, len: Int): ByteArray = if (len <= 0) ByteArray(0) else readAt(off, len)

    private fun ChapterReader.fourcc(off: Long): String {
        val b = readAt(off, 4)
        return if (b.size < 4) "" else b.decodeToString()
    }
}
