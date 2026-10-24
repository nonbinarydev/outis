/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.net

/** How long one [httpGet] may take, connecting included, unless the caller sets its own limit. */
internal const val DEFAULT_HTTP_TIMEOUT_MS = 30_000

/** The status codes [httpGet] reads as success. */
internal val HTTP_SUCCESS = 200..299

/** What an [httpGet] came to. */
internal sealed interface HttpResult {
    /** A 2xx response: its body as UTF-8, and the URL it was finally served from after any redirects. */
    class Ok(val body: String, val url: String) : HttpResult

    /** The server answered with a status outside 2xx. */
    class Status(val code: Int) : HttpResult

    /** No response at all — DNS, connection, TLS, timeout, or a URL the platform would not request. */
    class Failed(val message: String?, val nativeCause: Any? = null) : HttpResult
}

/** [httpGet]'s shape, so a test can stand in for the network. */
internal typealias HttpFetch = suspend (url: String, headers: Map<String, String>, timeoutMs: Int) -> HttpResult

/**
 * GET a small text resource — a sidecar or a SMIL document — sending [headers]. Never throws, except to
 * propagate cancellation.
 */
internal expect suspend fun httpGet(
    url: String,
    headers: Map<String, String> = emptyMap(),
    timeoutMs: Int = DEFAULT_HTTP_TIMEOUT_MS,
): HttpResult
