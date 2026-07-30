/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.nonbinary.outis.core.chapters

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithURL
import kotlin.coroutines.resume

internal actual suspend fun httpGetText(url: String): String? = suspendCancellableCoroutine { cont ->
    val nsUrl = NSURL(string = url)
    val task = NSURLSession.sharedSession.dataTaskWithURL(nsUrl) { data, _, _ ->
        cont.resume(data?.let { NSString.create(it, NSUTF8StringEncoding) as String? })
    }
    cont.invokeOnCancellation { task.cancel() }
    task.resume()
}
