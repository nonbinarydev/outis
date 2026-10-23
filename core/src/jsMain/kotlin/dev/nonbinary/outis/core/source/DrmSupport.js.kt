/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import kotlinx.coroutines.await

actual suspend fun drmSupport(): DrmSupport {
    val schemes = DrmScheme.entries.filterTo(mutableSetOf()) { KeySystems.supports(it).await() }
    val level = when {
        DrmScheme.WIDEVINE !in schemes -> null
        KeySystems.supports(DrmScheme.WIDEVINE, HARDWARE_ROBUSTNESS).await() -> WidevineLevel.L1
        else -> WidevineLevel.L3
    }
    return DrmSupport(schemes, level)
}

// The weakest hardware-backed Widevine robustness: granted where the browser has a hardware CDM.
private const val HARDWARE_ROBUSTNESS = "HW_SECURE_CRYPTO"
