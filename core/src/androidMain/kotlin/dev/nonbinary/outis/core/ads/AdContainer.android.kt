/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:Suppress("MatchingDeclarationName")

package dev.nonbinary.outis.core.ads

import android.view.View
import android.view.ViewGroup
import dev.nonbinary.outis.core.ExoPlayerEngine
import dev.nonbinary.outis.core.ExperimentalPlayerApi
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.engine

/**
 * Hand the player the view group IMA draws client-side ad UI into (skip, countdown, click-through) — laid
 * over the video — or `null` to detach it. [obstructions] are the views over the video that viewability
 * measurement should not count against the ad, such as your own controls. `:ui`'s Android surface calls
 * this itself; call it only when you draw the video without `:ui`. A no-op on a player that is not the
 * Media3 engine.
 */
@ExperimentalPlayerApi
fun VideoPlayer.setAdContainer(container: ViewGroup?, obstructions: List<AdObstruction> = emptyList()) {
    (engine as? ExoPlayerEngine)?.setAdContainer(container, obstructions)
}

/** A view drawn over the video that ad viewability measurement should treat as friendly. */
@ExperimentalPlayerApi
class AdObstruction(
    /** The overlapping view. */
    val view: View,
    /** Why it overlaps the ad, in Open Measurement's terms. */
    val purpose: Purpose,
    /** A short free-text explanation for measurement vendors, or `null`. */
    val reason: String? = null,
) {
    /** Open Measurement's friendly-obstruction purposes. */
    enum class Purpose {
        /** Playback controls — play/pause, scrubber, fullscreen. */
        CONTROLS,

        /** A control that closes or skips the ad. */
        CLOSE_AD,

        /** Anything else the viewer can see. */
        OTHER,

        /** Present but transparent or hidden, so it never actually covers the ad. */
        NOT_VISIBLE,
    }
}
