/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

/**
 * Marks the parts of `:core` expected to change shape before 1.0: the ad model with its parsers and
 * platform bridges, and the replaceable SMIL selection. The rest — [VideoPlayer], `MediaItem`,
 * [PlayerState], [PlayerEvent], [PlayerError], and the `PlayerComponent` seam analytics adapters build
 * on — is the surface held steady.
 *
 * `WARNING`, not `ERROR`, for the same reason as `:ui`'s marker: the alpha version already says nothing is
 * final, so the marker's job is to show which parts will move first, not to gate them. Opt in with
 * `@OptIn(ExperimentalPlayerApi::class)`, or module-wide with
 * `optIn.add("dev.nonbinary.outis.core.ExperimentalPlayerApi")`.
 */
@RequiresOptIn(
    message = "This Outis API is experimental and may change before 1.0.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
annotation class ExperimentalPlayerApi
