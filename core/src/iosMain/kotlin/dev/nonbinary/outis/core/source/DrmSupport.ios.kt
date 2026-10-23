/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import platform.Foundation.NSProcessInfo

actual suspend fun drmSupport(): DrmSupport = deviceDrmSupport

/** FairPlay decrypts only on a device: the simulator has no FairPlay CDM. Clear Key works on both. */
internal val deviceDrmSupport: DrmSupport by lazy {
    val simulator = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null
    val schemes = if (simulator) setOf(DrmScheme.CLEARKEY) else setOf(DrmScheme.FAIRPLAY, DrmScheme.CLEARKEY)
    DrmSupport(schemes, widevineLevel = null)
}
