/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import dev.nonbinary.outis.core.PlayerError

/** What this device can decrypt, as [drmSupport] reports it. */
data class DrmSupport(
    /** The schemes this device has a working key system for. */
    val schemes: Set<DrmScheme>,
    /**
     * Widevine's security level when [schemes] holds Widevine, else `null`: [WidevineLevel.L1] for
     * hardware-backed decryption, which HD licences usually require, or [WidevineLevel.L3] for software.
     * Never [WidevineLevel.AUTO].
     */
    val widevineLevel: WidevineLevel?,
)

/**
 * What this device can decrypt, asked of the platform rather than read from a table: `MediaDrm` on
 * Android, `navigator.requestMediaKeySystemAccess` on the web, and on iOS the key systems AVFoundation
 * serves — FairPlay on a device (the simulator has none) and Clear Key. The answer cannot change while
 * the app runs, so it is worked out once and kept. `jvm` and `wasmJs` have no engine and report nothing.
 *
 * Engines check the same answer before loading a protected item, so a scheme reported missing here
 * fails the load at once with [PlayerError.Category.DRM], naming the scheme.
 */
expect suspend fun drmSupport(): DrmSupport

/** The scheme's name as people write it. */
internal val DrmScheme.label: String
    get() = when (this) {
        DrmScheme.WIDEVINE -> "Widevine"
        DrmScheme.PLAYREADY -> "PlayReady"
        DrmScheme.FAIRPLAY -> "FairPlay"
        DrmScheme.CLEARKEY -> "Clear Key"
    }

/** The error an engine reports, before any playback, for [drm] when this device cannot satisfy it. */
internal fun unsupportedDrmError(drm: DrmConfig): PlayerError {
    val what = if (drm.scheme == DrmScheme.WIDEVINE && drm.widevineLevel != WidevineLevel.AUTO) {
        "Widevine ${drm.widevineLevel}"
    } else {
        "${drm.scheme.label} DRM"
    }
    return PlayerError(PlayerError.Category.DRM, message = "$what isn't supported on this device")
}
