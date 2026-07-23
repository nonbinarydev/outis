/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.plugin

import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Universal extension seam — the single attach point for analytics/QoS, ads, and anything else
 * the roadmap adds. v1 ships the seam with **zero** registered components; it costs nothing now
 * and cannot be added later without breaking the published contract.
 */
interface PlayerComponent {
    /** Called when the component is registered. Wire up collectors on [PlayerHost.scope] here. */
    fun attach(host: PlayerHost)

    /** Called on removal or player release. Tear down anything started in [attach]. */
    fun detach()
}

/**
 * Read-only view of the player handed to a [PlayerComponent].
 *
 * [nativePlayerHandle] is a **flow**, not a snapshot, on purpose: production analytics SDKs
 * (Mux, Conviva) bind to the concrete native player and must re-bind whenever it is recreated
 * (Android config-change reattach, or the future ads/DRM construction path rebuilding ExoPlayer).
 * A one-shot handle would silently leave them monitoring a dead player.
 */
interface PlayerHost {
    /**
     * The player's current state, updated continuously (position is re-sampled every
     * [dev.nonbinary.outis.core.PlayerConfig.positionPollIntervalMs]). Conflated like any
     * `StateFlow`, so a slow collector **will** miss intermediate values — read discrete
     * occurrences from [events] instead.
     */
    val state: StateFlow<PlayerState>

    /**
     * Discrete playback occurrences (errors, transitions) as they happen. Hot and **replay-free** —
     * a component only sees events emitted after it starts collecting, so subscribe in
     * [PlayerComponent.attach] rather than lazily.
     */
    val events: SharedFlow<PlayerEvent>

    /** Player-lifecycle-scoped; cancelled on release. Launch component collectors here. */
    val scope: CoroutineScope

    /** The current native player (or `null`), emitting again on every (re)construction. */
    val nativePlayerHandle: StateFlow<Any?>
}
