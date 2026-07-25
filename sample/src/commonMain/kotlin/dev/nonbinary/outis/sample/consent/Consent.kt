/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.consent

/**
 * A *purpose* an SDK collects data for, not an SDK. Adapters declare their category; consent is granted
 * per category. This is what keeps the "is Mux essential?" question out of the SDK and in one place.
 *
 * **[PERFORMANCE].requiresConsent is the policy line issue #39 is about.** Flip it to `false` to treat
 * QoS monitoring (Mux) as legitimate-interest rather than consent-gated — a decision for the operator's
 * legal basis, not a fact this sample should assert. It ships `true` because that is the safe default,
 * and because Mux Data stores a viewer id (localStorage on web), which is exactly what ePrivacy governs.
 */
enum class ConsentCategory(
    val label: String,
    val description: String,
    val requiresConsent: Boolean,
) {
    ESSENTIAL(
        label = "Essential",
        description = "Needed for the app to work: playback itself, and the on-device diagnostics log " +
            "(which never leaves the device). Always on.",
        requiresConsent = false,
    ),
    PERFORMANCE(
        label = "Performance",
        description = "Quality-of-service monitoring (Mux Data): rebuffering, startup time, stream " +
            "quality. Used to improve playback, not for advertising.",
        requiresConsent = true,
    ),
    MARKETING(
        label = "Marketing",
        description = "Usage analytics (Google Analytics): which content is watched, session length. " +
            "Cross-site, so always consent-gated.",
        requiresConsent = true,
    ),
}

/**
 * The current consent decision.
 *
 * [decided] separates "the user chose to deny performance" from "the user has not been asked yet" — the
 * first-run dialog shows only while it is `false`. A category that does not require consent is always
 * granted regardless of [grants].
 */
data class ConsentState(
    val decided: Boolean = false,
    val grants: Map<ConsentCategory, Boolean> = emptyMap(),
) {
    /** True when this purpose may run: either it needs no consent, or consent was given. */
    fun isGranted(category: ConsentCategory): Boolean =
        !category.requiresConsent || grants[category] == true

    companion object {
        /** Nothing asked yet, nothing gated-in. Essential is still granted via [isGranted]. */
        val UNDECIDED = ConsentState(decided = false, grants = emptyMap())
    }
}
