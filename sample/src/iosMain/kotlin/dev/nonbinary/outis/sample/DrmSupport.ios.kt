/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.MimeType

/** Static, and correct: AVFoundation serves FairPlay and nothing else. */
actual fun drmSchemeCaveat(scheme: DrmScheme): String? = when (scheme) {
    DrmScheme.FAIRPLAY -> null
    DrmScheme.WIDEVINE -> "AVFoundation has no Widevine CDM — FairPlay is the only scheme on iOS."
    DrmScheme.PLAYREADY -> "AVFoundation has no PlayReady CDM — FairPlay is the only scheme on iOS."
    // Clear Key works on iOS via HLS (AVContentKeySession). A DASH Clear Key item fails on the container,
    // not the scheme — the DASH warning comes from containerCaveat instead — so no scheme caveat here.
    DrmScheme.CLEARKEY -> null
}

/** AVFoundation plays HLS + progressive MP4 only — no DASH, and no WebM/VP9. */
actual fun containerCaveat(mimeType: MimeType): String? = when (mimeType) {
    MimeType.DASH -> "AVPlayer has no DASH — this won't play on iOS (the HLS/progressive entries do)."
    MimeType.WEBM -> "AVPlayer can't decode WebM/VP9 — this won't play on iOS."
    MimeType.MP4, MimeType.HLS -> null
}
