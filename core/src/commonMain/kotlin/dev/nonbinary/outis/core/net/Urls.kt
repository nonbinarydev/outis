/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.net

/**
 * [reference] resolved against [base], per RFC 3986 §5.2: the way a browser resolves a link. A [base]
 * with no scheme resolves as a bare path.
 */
internal fun resolveUrl(base: String, reference: String): String {
    val b = UriReference.parse(base.trim())
    val r = UriReference.parse(reference.trim())
    val target = when {
        r.scheme != null -> r.copy(path = removeDotSegments(r.path))
        r.authority != null -> r.copy(scheme = b.scheme, path = removeDotSegments(r.path))
        r.path.isEmpty() -> b.copy(query = r.query ?: b.query, fragment = r.fragment)
        r.path.startsWith('/') -> r.copy(scheme = b.scheme, authority = b.authority, path = removeDotSegments(r.path))
        else -> r.copy(scheme = b.scheme, authority = b.authority, path = removeDotSegments(merge(b, r.path)))
    }
    return target.toString()
}

/**
 * The origin of [url] — scheme, host and port, lower-cased, with the scheme's default port filled in — or
 * `null` when it has no scheme or no authority.
 */
internal fun originOf(url: String): String? {
    val u = UriReference.parse(url.trim())
    val scheme = u.scheme?.lowercase() ?: return null
    val hostAndPort = u.authority?.substringAfterLast('@') ?: return null
    // A colon inside an IPv6 literal's brackets is not a port separator.
    val colon = hostAndPort.lastIndexOf(':').takeIf { it > hostAndPort.lastIndexOf(']') } ?: -1
    val host = if (colon < 0) hostAndPort else hostAndPort.substring(0, colon)
    val port = (if (colon < 0) "" else hostAndPort.substring(colon + 1)).ifEmpty { DEFAULT_PORTS[scheme].orEmpty() }
    return "$scheme://${host.lowercase()}:$port"
}

/** [url] without its query and fragment — what an extension check should look at. */
internal fun urlPath(url: String): String = url.substringBefore('#').substringBefore('?')

private val DEFAULT_PORTS = mapOf("http" to "80", "https" to "443")

/** RFC 3986 appendix B. Every group is optional, so every string matches. */
private val URI_PARTS = Regex("""^(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\?([^#]*))?(#([\s\S]*))?$""")

private data class UriReference(
    val scheme: String?,
    val authority: String?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    override fun toString(): String = buildString {
        scheme?.let { append(it).append(':') }
        authority?.let { append("//").append(it) }
        append(path)
        query?.let { append('?').append(it) }
        fragment?.let { append('#').append(it) }
    }

    companion object {
        fun parse(value: String): UriReference {
            val groups = checkNotNull(URI_PARTS.matchEntire(value)).groups
            return UriReference(
                scheme = groups[SCHEME]?.value,
                authority = groups[AUTHORITY]?.value,
                path = groups[PATH]?.value.orEmpty(),
                query = groups[QUERY]?.value,
                fragment = groups[FRAGMENT]?.value,
            )
        }

        private const val SCHEME = 2
        private const val AUTHORITY = 4
        private const val PATH = 5
        private const val QUERY = 7
        private const val FRAGMENT = 9
    }
}

/** RFC 3986 §5.2.3. */
private fun merge(base: UriReference, path: String): String = when {
    base.authority != null && base.path.isEmpty() -> "/$path"
    else -> base.path.substring(0, base.path.lastIndexOf('/') + 1) + path
}

/** RFC 3986 §5.2.4. */
private fun removeDotSegments(path: String): String {
    var input = path
    val output = StringBuilder()
    while (input.isNotEmpty()) {
        input = when {
            input.startsWith("../") -> input.substring("../".length)
            input.startsWith("./") -> input.substring("./".length)
            input.startsWith("/./") -> input.substring("/.".length)
            input == "/." -> "/"
            input.startsWith("/../") || input == "/.." -> {
                output.setLength(output.lastIndexOf('/').coerceAtLeast(0))
                "/" + input.substring("/..".length).removePrefix("/")
            }
            input == "." || input == ".." -> ""
            else -> {
                val end = input.indexOf('/', startIndex = 1).takeIf { it >= 0 } ?: input.length
                output.appendRange(input, 0, end)
                input.substring(end)
            }
        }
    }
    return output.toString()
}
