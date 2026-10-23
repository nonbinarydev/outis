/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

// Not injectable: an actual cannot take a dispatcher parameter its expect, shared with iOS and web, lacks.
@Suppress("InjectDispatcher")
internal actual suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult =
    withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (connection is HttpURLConnection) read(connection) else HttpResult.Ok(connection.readText(), url)
        } catch (e: IOException) {
            HttpResult.Failed(e.message, e)
        } catch (e: IllegalArgumentException) {
            // A header value carrying a line break.
            HttpResult.Failed(e.message, e)
        } catch (e: SecurityException) {
            // No INTERNET permission.
            HttpResult.Failed(e.message, e)
        }
    }

private fun read(connection: HttpURLConnection): HttpResult = try {
    val status = connection.responseCode
    if (status in HTTP_SUCCESS) {
        // After redirects, getURL() is where the body was actually served from.
        HttpResult.Ok(connection.readText(), connection.url.toString())
    } else {
        HttpResult.Status(status)
    }
} finally {
    connection.disconnect()
}

private fun URLConnection.readText(): String = getInputStream().use { it.readBytes() }.decodeToString()
