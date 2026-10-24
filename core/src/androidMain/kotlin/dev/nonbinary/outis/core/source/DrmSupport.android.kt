/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import android.media.MediaDrm
import android.media.UnsupportedSchemeException
import android.os.Build
import androidx.media3.common.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Not injectable: an actual cannot take a dispatcher parameter its expect, shared with iOS and web, lacks.
@Suppress("InjectDispatcher")
public actual suspend fun drmSupport(): DrmSupport = withContext(Dispatchers.IO) { deviceDrmSupport }

private val deviceDrmSupport: DrmSupport by lazy {
    val schemes = DrmScheme.entries.filterTo(mutableSetOf(), ::isDrmSchemeSupported)
    DrmSupport(schemes, if (DrmScheme.WIDEVINE in schemes) widevineSecurityLevel() else null)
}

/** Whether this device has a CDM for [scheme]. Support varies by device, not OS version; FairPlay is Apple's. */
internal fun isDrmSchemeSupported(scheme: DrmScheme): Boolean = when (scheme) {
    DrmScheme.WIDEVINE -> MediaDrm.isCryptoSchemeSupported(C.WIDEVINE_UUID)
    DrmScheme.PLAYREADY -> MediaDrm.isCryptoSchemeSupported(C.PLAYREADY_UUID)
    DrmScheme.CLEARKEY -> MediaDrm.isCryptoSchemeSupported(C.CLEARKEY_UUID)
    DrmScheme.FAIRPLAY -> false
}

/** L2 counts as L3: it decodes video in software, which is what HD licences refuse. */
@Suppress("DEPRECATION") // MediaDrm.release(), the only way to free one before API 28's close()
private fun widevineSecurityLevel(): WidevineLevel? = try {
    val drm = MediaDrm(C.WIDEVINE_UUID)
    try {
        if (drm.getPropertyString("securityLevel") == "L1") WidevineLevel.L1 else WidevineLevel.L3
    } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) drm.close() else drm.release()
    }
} catch (_: UnsupportedSchemeException) {
    null
} catch (_: IllegalStateException) {
    null
}
