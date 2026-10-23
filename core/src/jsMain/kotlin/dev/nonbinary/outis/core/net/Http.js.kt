/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.net

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.withTimeoutOrNull
import org.w3c.fetch.RequestInit
import kotlin.coroutines.cancellation.CancellationException

internal actual suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult {
    // Kotlin's DOM declarations predate AbortController and RequestInit.signal.
    val controller = AbortController()
    val requestHeaders: dynamic = js("({})")
    headers.forEach { (name, value) -> requestHeaders[name] = value }
    val init: dynamic = js("({})")
    init.headers = requestHeaders
    init.signal = controller.signal
    return try {
        runCatching {
            withTimeoutOrNull(timeoutMs.toLong()) {
                val response = window.fetch(url, init.unsafeCast<RequestInit>()).await()
                if (response.ok) {
                    HttpResult.Ok(response.text().await(), response.url.ifEmpty { url })
                } else {
                    HttpResult.Status(response.status.toInt())
                }
            } ?: HttpResult.Failed("No response within $timeoutMs ms")
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            HttpResult.Failed(e.message, e)
        }
    } finally {
        // Stops a request that timed out or was cancelled; a no-op once the body has been read.
        controller.abort()
    }
}

private external class AbortController {
    val signal: dynamic
    fun abort()
}
