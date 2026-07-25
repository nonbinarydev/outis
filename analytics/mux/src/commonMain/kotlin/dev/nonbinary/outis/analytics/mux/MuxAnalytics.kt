/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.mux

import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.plugin.PlayerHost
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Mux Data QoS for an Outis player.
 *
 * Attach it as a [PlayerComponent]:
 * ```
 * val mux = MuxAnalytics(appContext, MuxConfig(envKey = "…", playerName = "my-app"))
 * player.addComponent(mux)
 * ```
 *
 * It follows ADR-0003: it does **not** translate `PlayerEvent`s. Mux's own native SDK binds to the
 * concrete player — ExoPlayer, AVPlayer, the `<video>` element — and derives rendition changes,
 * bandwidth and dropped frames there, which is more than Outis exposes uniformly. This adapter simply
 * hands Mux the native handle and re-binds whenever the engine rebuilds it (the reason
 * [PlayerHost.nativePlayerHandle] is a flow).
 *
 * Per-item analytics come from [dev.nonbinary.outis.core.source.MediaItem.analytics], read off the
 * player state at bind time; viewer identity is on [MuxConfig], being session-scoped rather than
 * per-video. Binding therefore waits for an item to be present and re-binds on each new one — see
 * [attach].
 */
class MuxAnalytics(
    private val appContext: AppContext,
    private val config: MuxConfig,
) : PlayerComponent {

    private var binding: MuxBinding? = null

    override fun attach(host: PlayerHost) {
        // Bind when BOTH a native player and a media item are present, and re-bind when either changes.
        // bindMux snapshots the item's metadata, so binding on the handle alone would capture whatever
        // item existed then — and on web the <video> handle exists before the catalogue loads and the
        // first item is set, snapshotting null and sending no per-video metadata. Re-binding per item
        // also starts a fresh Mux view for each video, which is the correct QoS semantics. A null handle
        // (engine momentarily without a player) or null item tears the current binding down.
        combine(
            host.nativePlayerHandle,
            host.state.map { it.mediaItem }.distinctUntilChanged(),
        ) { handle, item -> handle to item }
            .distinctUntilChanged()
            .onEach { (handle, item) ->
                binding?.dispose()
                binding = if (handle != null && item != null) bindMux(handle, appContext, host, config) else null
            }
            .launchIn(host.scope)
    }

    override fun detach() {
        binding?.dispose()
        binding = null
    }
}

/**
 * Session-scoped Mux configuration. Everything per-video is [dev.nonbinary.outis.core.analytics.PlaybackMetadata]
 * on the item; this is what stays constant across a viewing session.
 */
data class MuxConfig(
    /** Mux Data environment key (the client-side monitoring key), from the Mux dashboard. */
    val envKey: String,
    /** Stable id for this viewer across sessions, if the app has one. Never PII. */
    val viewerId: String? = null,
    /** A name for this player integration, shown in Mux to distinguish surfaces (e.g. "web-demo"). */
    val playerName: String? = null,
)

/**
 * One live Mux monitor over a native player. [dispose] must fully detach it — a leaked monitor keeps
 * reporting against a dead player and corrupts the session data.
 */
internal interface MuxBinding {
    fun dispose()
}

/**
 * Starts a Mux monitor on the platform's native player [handle].
 *
 * `null` when this platform cannot bind — the iOS actual, until its CocoaPods SDK is wired — so the
 * caller simply records no binding rather than failing.
 */
internal expect fun bindMux(
    handle: Any,
    appContext: AppContext,
    host: PlayerHost,
    config: MuxConfig,
): MuxBinding?
