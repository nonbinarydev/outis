/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:OptIn(ExperimentalForeignApi::class)

package dev.nonbinary.outis.core.net

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import kotlin.coroutines.resume

internal actual suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult {
    val nsUrl = NSURL.URLWithString(url) ?: return HttpResult.Failed("Not a URL: $url")
    val request = NSMutableURLRequest(uRL = nsUrl)
    request.setTimeoutInterval(timeoutMs / MS_PER_SECOND)
    headers.forEach { (name, value) -> request.setValue(value, forHTTPHeaderField = name) }
    return suspendCancellableCoroutine { cont ->
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            cont.resume(toResult(data, response, error, url))
        }
        cont.invokeOnCancellation { task.cancel() }
        task.resume()
    }
}

private fun toResult(data: NSData?, response: NSURLResponse?, error: NSError?, url: String): HttpResult {
    val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt()
    return when {
        error != null || data == null -> HttpResult.Failed(error?.localizedDescription, error)
        status != null && status !in HTTP_SUCCESS -> HttpResult.Status(status)
        else -> HttpResult.Ok(data.toByteArray().decodeToString(), response?.URL?.absoluteString ?: url)
    }
}

private fun NSData.toByteArray(): ByteArray = bytes?.reinterpret<ByteVar>()?.readBytes(length.toInt()) ?: ByteArray(0)

private const val MS_PER_SECOND = 1000.0
