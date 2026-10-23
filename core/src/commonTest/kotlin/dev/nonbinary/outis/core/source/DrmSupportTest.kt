/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import dev.nonbinary.outis.core.PlayerError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DrmSupportTest {

    @Test
    fun anUnsupportedSchemeIsADrmErrorNamingIt() {
        val error = unsupportedDrmError(DrmConfig(DrmScheme.PLAYREADY, licenseServerUrl = "https://l"))

        assertEquals(PlayerError.Category.DRM, error.category)
        assertEquals("PlayReady DRM isn't supported on this device", error.message)
        assertNull(error.nativeCause) // synthesised by the SDK, not the engine
    }

    @Test
    fun aForcedWidevineLevelIsNamedInstead() {
        assertEquals(
            "Widevine L1 isn't supported on this device",
            unsupportedDrmError(DrmConfig(DrmScheme.WIDEVINE, widevineLevel = WidevineLevel.L1)).message,
        )
        assertEquals(
            "Widevine DRM isn't supported on this device",
            unsupportedDrmError(DrmConfig(DrmScheme.WIDEVINE)).message,
        )
    }

    @Test
    fun everySchemeHasItsWrittenName() {
        assertEquals(
            listOf("Widevine", "PlayReady", "FairPlay", "Clear Key"),
            listOf(DrmScheme.WIDEVINE, DrmScheme.PLAYREADY, DrmScheme.FAIRPLAY, DrmScheme.CLEARKEY).map { it.label },
        )
    }
}
