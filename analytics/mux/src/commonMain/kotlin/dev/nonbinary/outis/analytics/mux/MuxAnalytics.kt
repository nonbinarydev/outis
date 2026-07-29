/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.mux

import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.plugin.PlayerHost
import dev.nonbinary.outis.core.source.DrmScheme
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
        // Re-bind whenever the native player, the view-level presentation object (iOS's
        // AVPlayerViewController), or the media item changes. bindMux reads whichever object its platform
        // monitors off the host — the ExoPlayer / <video> handle, or the presentation VC on iOS — together
        // with the item's metadata, so it must re-run when any of them appears or is recreated. Re-binding
        // per item also starts a fresh Mux view for each video (the correct QoS semantics); binding only
        // once an item exists avoids a first view with no per-video metadata (on web the <video> exists
        // before the catalogue loads). bindMux returns null when its object isn't available yet.
        combine(
            host.nativePlayerHandle,
            host.nativePresentationHandle,
            host.state.map { it.mediaItem }.distinctUntilChanged(),
        ) { player, presentation, item -> Triple(player, presentation, item) }
            .distinctUntilChanged()
            .onEach { (_, _, item) ->
                binding?.dispose()
                binding = if (item == null) null else bindMux(appContext, host, config)
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
    /**
     * Overrides Mux's `player_software_name`. Defaults to `"Outis (<engine>)"` — the underlying player
     * per platform (`AVPlayer`, `ExoPlayer`, `Shaka Player`).
     */
    val playerSoftwareName: String? = null,
)

/**
 * One live Mux monitor over a native player. [dispose] must fully detach it — a leaked monitor keeps
 * reporting against a dead player and corrupts the session data.
 */
internal interface MuxBinding {
    fun dispose()
}

/**
 * Starts a Mux monitor on the object this platform binds to, read off [host]: the native
 * [PlayerHost.nativePlayerHandle] (Android ExoPlayer, web `<video>`) or the
 * [PlayerHost.nativePresentationHandle] (iOS `AVPlayerViewController`). Returns `null` when that object is
 * not yet available, so the caller simply records no binding rather than failing.
 */
internal expect fun bindMux(
    appContext: AppContext,
    host: PlayerHost,
    config: MuxConfig,
): MuxBinding?

/**
 * The native player each platform drives — `AVPlayer`, `ExoPlayer`, `Shaka Player`. Used to build the
 * default Mux `player_software_name` when [MuxConfig.playerSoftwareName] is unset.
 */
internal expect val nativePlayerLabel: String

/** The Mux `player_software_name` to report: the caller's override, else `"Outis (<engine>)"`. */
internal fun MuxConfig.playerSoftware(): String = playerSoftwareName ?: "Outis ($nativePlayerLabel)"

/** Maps an Outis DRM scheme to Mux's `view_drm_type` vocabulary; `null` for clear content. */
internal fun muxDrmType(scheme: DrmScheme?): String? = when (scheme) {
    DrmScheme.WIDEVINE -> "widevine"
    DrmScheme.PLAYREADY -> "playready"
    DrmScheme.FAIRPLAY -> "fairplay"
    null -> null
}
