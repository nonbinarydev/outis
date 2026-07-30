/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import android.media.MediaDrm
import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.MimeType
import java.util.UUID

// The standard EME/CENC system IDs. Hardcoded rather than taken from Media3's `C` so the sample does
// not reach into the engine's dependencies for two constants.
private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
private val PLAYREADY_UUID = UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95")
private val CLEARKEY_UUID = UUID.fromString("1077efec-c0b2-4d02-ace3-3c1e52e2fb4b")

/**
 * The only platform here that can answer honestly: support is a property of the *device*, not the OS
 * version, and `isCryptoSchemeSupported` is the real query. PlayReady is absent from most Android
 * builds; Widevine is present on effectively all of them.
 */
actual fun drmSchemeCaveat(scheme: DrmScheme): String? = when (scheme) {
    DrmScheme.WIDEVINE ->
        if (MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)) null else "This device reports no Widevine CDM."
    DrmScheme.PLAYREADY ->
        if (MediaDrm.isCryptoSchemeSupported(PLAYREADY_UUID)) null else "This device reports no PlayReady CDM."
    DrmScheme.CLEARKEY ->
        if (MediaDrm.isCryptoSchemeSupported(CLEARKEY_UUID)) null else "This device reports no Clear Key CDM."
    DrmScheme.FAIRPLAY -> "FairPlay is an Apple key system — not available on Android."
}

/** ExoPlayer handles every container the catalogue uses — DASH, HLS, progressive MP4 and WebM/VP9. */
actual fun containerCaveat(mimeType: MimeType): String? = when (mimeType) {
    MimeType.MP4, MimeType.HLS, MimeType.DASH, MimeType.WEBM -> null
}
