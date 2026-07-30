/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.chapters

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

internal actual suspend fun httpGetText(url: String): String? = withContext(Dispatchers.IO) {
    runCatching { URL(url).readText() }.getOrNull()
}
