/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

import kotlinx.browser.window
import kotlinx.coroutines.await

internal actual suspend fun httpGetText(url: String): String? = runCatching {
    window.fetch(url).await().text().await()
}.getOrNull()
