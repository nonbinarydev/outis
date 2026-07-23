/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import dev.nonbinary.outis.core.source.DrmScheme

/** Static, and correct: AVFoundation serves FairPlay and nothing else. */
actual fun drmSchemeCaveat(scheme: DrmScheme): String? = when (scheme) {
    DrmScheme.FAIRPLAY -> null
    DrmScheme.WIDEVINE -> "AVFoundation has no Widevine CDM — FairPlay is the only scheme on iOS."
    DrmScheme.PLAYREADY -> "AVFoundation has no PlayReady CDM — FairPlay is the only scheme on iOS."
}
