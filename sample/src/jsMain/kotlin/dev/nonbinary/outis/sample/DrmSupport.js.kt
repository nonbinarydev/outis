/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import dev.nonbinary.outis.core.source.DrmScheme
import kotlinx.browser.window

/**
 * The weakest of the three, and the reason the hint is worded as an expectation.
 *
 * The honest probe is `navigator.requestMediaKeySystemAccess`, which is async and so cannot answer a
 * synchronous call used during composition. This sniffs the engine instead: Safari means WebKit, which
 * means FairPlay and no Widevine. Everything else is assumed Widevine-capable, which holds for Chrome,
 * Edge and Firefox but is a guess rather than a fact.
 */
private val isWebKit: Boolean by lazy {
    val ua = window.navigator.userAgent
    ua.contains("Safari", ignoreCase = true) &&
        !ua.contains("Chrome", ignoreCase = true) &&
        !ua.contains("Chromium", ignoreCase = true)
}

actual fun drmSchemeCaveat(scheme: DrmScheme): String? = when (scheme) {
    DrmScheme.FAIRPLAY ->
        if (isWebKit) null else "FairPlay needs Safari — this browser is not expected to have the CDM."
    DrmScheme.WIDEVINE ->
        if (isWebKit) "Safari has no Widevine CDM — this is not expected to play here." else null
    DrmScheme.PLAYREADY ->
        "PlayReady is only expected on Edge/Windows builds that ship the CDM."
    DrmScheme.CLEARKEY ->
        if (isWebKit) "Safari has no Clear Key CDM — this is not expected to play here." else null
}
