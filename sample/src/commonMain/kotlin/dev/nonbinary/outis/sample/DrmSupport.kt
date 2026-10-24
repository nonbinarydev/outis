/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.DrmSupport
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.core.source.drmSupport

/** What this device can decrypt, as the SDK reports it — `null` until it answers, which on web takes a moment. */
@Composable
fun rememberDrmSupport(): DrmSupport? = produceState<DrmSupport?>(null) { value = drmSupport() }.value

/**
 * A short warning when [scheme] won't decrypt here, or `null` when it will — or while [support] is unknown.
 *
 * A **hint, not a gate**. Schemes stay selectable everywhere on purpose: trying one the platform cannot
 * do is an ordinary integration mistake, and watching the SDK report it is more useful than being
 * prevented from making it.
 */
fun drmSchemeCaveat(scheme: DrmScheme, support: DrmSupport?): String? = when {
    support == null || scheme in support.schemes -> null
    else -> "No ${scheme.displayName} CDM here — the player will refuse it."
}

private val DrmScheme.displayName: String
    get() = when (this) {
        DrmScheme.WIDEVINE -> "Widevine"
        DrmScheme.PLAYREADY -> "PlayReady"
        DrmScheme.FAIRPLAY -> "FairPlay"
        DrmScheme.CLEARKEY -> "Clear Key"
    }

/**
 * A short warning when [mimeType]'s container isn't expected to play on the host, or `null` when it
 * should. Same "hint, not a gate" contract as [drmSchemeCaveat]: it covers container support only (DASH,
 * WebM), which is static per platform — a per-*codec* caveat (e.g. AV1, which is device-dependent on
 * Apple hardware) would need a runtime capability probe and is out of scope here.
 */
expect fun containerCaveat(mimeType: MimeType): String?
