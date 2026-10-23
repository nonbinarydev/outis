/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import dev.nonbinary.outis.core.net.resolveUrl
import dev.nonbinary.outis.core.net.urlPath

/**
 * The media elements of a SMIL document in document order, each `src` resolved against [documentUrl] or the
 * base the document sets — `<meta name="base">` or `<meta base>`, Wowza's `httpBase`, `xml:base`.
 *
 * Only elements and attributes are read. A DOCTYPE is skipped unread, so nothing it declares is ever expanded;
 * attribute values expand XML's five predefined entities and numeric references, and keep any other `&name;`
 * as written.
 *
 * @throws SmilFormatException when the root element is not `<smil>` or the markup is unterminated.
 */
internal fun parseSmil(text: String, documentUrl: String): List<SmilRendition> {
    val scanner = MarkupScanner(text)
    val root = scanner.next()
    if (root !is StartTag || localName(root.name) != "smil") {
        throw SmilFormatException("Not a SMIL document: the root element is ${(root as? StartTag)?.name ?: "missing"}")
    }
    val collector = SmilCollector()
    collector.start(root)
    generateSequence { scanner.next() }.forEach { markup ->
        when (markup) {
            is StartTag -> collector.start(markup)
            is EndTag -> collector.end(markup.name)
        }
    }
    return collector.renditions(documentUrl)
}

private val MEDIA_ELEMENTS = setOf("video", "audio", "ref", "media")
private val META_BASE_NAMES = setOf("base", "httpbase")

private val TYPE_FORMATS = mapOf(
    "application/x-mpegurl" to MimeType.HLS,
    "application/vnd.apple.mpegurl" to MimeType.HLS,
    "audio/mpegurl" to MimeType.HLS,
    "audio/x-mpegurl" to MimeType.HLS,
    "application/dash+xml" to MimeType.DASH,
    "video/mp4" to MimeType.MP4,
    "audio/mp4" to MimeType.MP4,
    "video/webm" to MimeType.WEBM,
    "audio/webm" to MimeType.WEBM,
)

private val EXTENSION_FORMATS = mapOf(
    ".m3u8" to MimeType.HLS,
    ".mpd" to MimeType.DASH,
    ".mp4" to MimeType.MP4,
    ".m4v" to MimeType.MP4,
    ".m4a" to MimeType.MP4,
    ".webm" to MimeType.WEBM,
)

private fun formatOf(type: String?, url: String): MimeType? {
    type?.substringBefore(';')?.trim()?.lowercase()?.let(TYPE_FORMATS::get)?.let { return it }
    val path = urlPath(url).lowercase()
    return EXTENSION_FORMATS.entries.firstOrNull { path.endsWith(it.key) }?.value
}

private fun localName(name: String): String = name.substringAfter(':')

/** Builds renditions from the tags as they stream past, tracking the open elements for `xml:base` and `<param>`. */
private class SmilCollector {
    private class Media(val name: String, val attributes: Map<String, String>, val bases: List<String>) {
        val params = LinkedHashMap<String, String>()
    }

    private class Open(val name: String, val bases: List<String>, val media: Media?)

    private val open = ArrayDeque<Open>()
    private val media = mutableListOf<Media>()
    private var metaBase: String? = null

    fun start(tag: StartTag) {
        val parent = open.lastOrNull()
        val bases = parent?.bases.orEmpty() + listOfNotNull(tag.attributes["xml:base"])
        val name = localName(tag.name)
        val element = if (name in MEDIA_ELEMENTS && !tag.attributes["src"].isNullOrBlank()) {
            Media(name, tag.attributes, bases).also(media::add)
        } else {
            null
        }
        when (name) {
            "meta" -> metaBase = metaBaseOf(tag.attributes) ?: metaBase
            "param" -> tag.attributes["name"]?.let { parent?.media?.params?.put(it, tag.attributes["value"].orEmpty()) }
        }
        if (!tag.selfClosing) open.addLast(Open(tag.name, bases, element))
    }

    /** Close the innermost open element called [name]; a stray end tag closes nothing. */
    fun end(name: String) {
        val index = open.indexOfLast { it.name == name }
        while (index >= 0 && open.size > index) open.removeLast()
    }

    fun renditions(documentUrl: String): List<SmilRendition> {
        val documentBase = metaBase?.let { resolveUrl(documentUrl, it) } ?: documentUrl
        return media.map { m ->
            val url = resolveUrl(m.bases.fold(documentBase, ::resolveUrl), m.attributes.getValue("src"))
            SmilRendition(url, formatOf(m.attributes["type"], url), m.name, m.attributes, m.params.toMap())
        }
    }

    private fun metaBaseOf(attributes: Map<String, String>): String? =
        attributes["base"] ?: attributes["content"]?.takeIf { attributes["name"]?.lowercase() in META_BASE_NAMES }
}

private sealed interface Markup

private class StartTag(val name: String, val attributes: Map<String, String>, val selfClosing: Boolean) : Markup

private class EndTag(val name: String) : Markup

/** Steps through a document's tags, skipping text, comments, CDATA, processing instructions and declarations. */
private class MarkupScanner(private val text: String) {
    private var pos = 0

    fun next(): Markup? {
        while (true) {
            pos = text.indexOf('<', pos).takeIf { it >= 0 } ?: return null
            when {
                text.startsWith("<!--", pos) -> pos = indexAfter("-->", pos)
                text.startsWith("<![CDATA[", pos) -> pos = indexAfter("]]>", pos)
                text.startsWith("<?", pos) -> pos = indexAfter("?>", pos)
                text.startsWith("<!", pos) -> skipDeclaration()
                text.startsWith("</", pos) -> return endTag()
                else -> return startTag()
            }
        }
    }

    private fun indexAfter(terminator: String, from: Int): Int {
        val at = text.indexOf(terminator, from)
        if (at < 0) throw SmilFormatException("Unterminated markup at offset $from")
        return at + terminator.length
    }

    /** A DOCTYPE or other `<!…>` declaration, internal subset included — stepped over, never interpreted. */
    private fun skipDeclaration() {
        var depth = 0
        var i = pos + 2
        while (i < text.length) {
            when {
                text.startsWith("<!--", i) -> i = indexAfter("-->", i) - 1
                text[i] == '"' || text[i] == '\'' -> i = indexAfter(text[i].toString(), i + 1) - 1
                text[i] == '[' -> depth++
                text[i] == ']' -> depth--
                text[i] == '>' && depth <= 0 -> {
                    pos = i + 1
                    return
                }
            }
            i++
        }
        throw SmilFormatException("Unterminated declaration at offset $pos")
    }

    private fun endTag(): EndTag {
        val close = indexAfter(">", pos) - 1
        return EndTag(text.substring(pos + 2, close).trim()).also { pos = close + 1 }
    }

    private fun startTag(): StartTag {
        val nameEnd = scanName(pos + 1)
        val name = text.substring(pos + 1, nameEnd)
        if (name.isEmpty()) throw SmilFormatException("Malformed tag at offset $pos")
        val attributes = LinkedHashMap<String, String>()
        var i = nameEnd
        while (true) {
            i = skipWhitespace(i)
            when {
                i >= text.length -> throw SmilFormatException("Unterminated <$name>")
                text[i] == '>' -> return StartTag(name, attributes, selfClosing = false).also { pos = i + 1 }
                text.startsWith("/>", i) -> return StartTag(name, attributes, selfClosing = true).also { pos = i + 2 }
                else -> i = readAttribute(i, attributes)
            }
        }
    }

    /** Reads `name="value"` — or a bare name — starting at [start] into [into]; returns the index after it. */
    private fun readAttribute(start: Int, into: MutableMap<String, String>): Int {
        val nameEnd = scanName(start)
        if (nameEnd == start) return start + 1 // a stray character: step over it
        val name = text.substring(start, nameEnd)
        val equals = skipWhitespace(nameEnd)
        if (text.getOrNull(equals) != '=') {
            into[name] = ""
            return equals
        }
        val valueStart = skipWhitespace(equals + 1)
        val quote = text.getOrNull(valueStart)
        val quoted = quote == '"' || quote == '\''
        val valueEnd = if (quoted) indexAfter(quote.toString(), valueStart + 1) - 1 else scanUnquoted(valueStart)
        into[name] = decodeEntities(text.substring(if (quoted) valueStart + 1 else valueStart, valueEnd))
        return if (quoted) valueEnd + 1 else valueEnd
    }

    private fun scanName(from: Int): Int {
        var i = from
        while (i < text.length && !text[i].isWhitespace() && text[i] !in NAME_ENDS) i++
        return i
    }

    private fun scanUnquoted(from: Int): Int {
        var i = from
        while (i < text.length && !text[i].isWhitespace() && text[i] != '>') i++
        return i
    }

    private fun skipWhitespace(from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private companion object {
        const val NAME_ENDS = "=>/\"'<"
    }
}

private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|lt|gt|amp|quot|apos);")
private val PREDEFINED_ENTITIES = mapOf("lt" to "<", "gt" to ">", "amp" to "&", "quot" to "\"", "apos" to "'")

/** XML attribute-value normalisation, then the predefined entities and numeric references. */
private fun decodeEntities(raw: String): String {
    val value = raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
    if ('&' !in value) return value
    return ENTITY.replace(value) { match ->
        val reference = match.groupValues[1]
        PREDEFINED_ENTITIES[reference] ?: codePointText(reference) ?: match.value
    }
}

/** The character a `#123` or `#x7B` reference names, or `null` for one XML does not allow. */
private fun codePointText(reference: String): String? {
    val hex = reference.startsWith("#x") || reference.startsWith("#X")
    val code = (if (hex) reference.drop(2).toIntOrNull(HEX_RADIX) else reference.drop(1).toIntOrNull()) ?: return null
    if (code !in 1..MAX_CODE_POINT || code in SURROGATES) return null
    if (code < SUPPLEMENTARY_START) return code.toChar().toString()
    val offset = code - SUPPLEMENTARY_START
    return charArrayOf(
        (HIGH_SURROGATE_START + (offset shr SURROGATE_BITS)).toChar(),
        (LOW_SURROGATE_START + (offset and SURROGATE_MASK)).toChar(),
    ).concatToString()
}

private const val HEX_RADIX = 16
private const val MAX_CODE_POINT = 0x10FFFF
private const val SUPPLEMENTARY_START = 0x10000
private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_START = 0xDC00
private const val SURROGATE_BITS = 10
private const val SURROGATE_MASK = 0x3FF
private val SURROGATES = 0xD800..0xDFFF
