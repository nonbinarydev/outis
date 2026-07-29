/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.mux

import androidx.media3.common.MediaLibraryInfo
import androidx.media3.exoplayer.ExoPlayer
import com.mux.stats.sdk.core.CustomOptions
import com.mux.stats.sdk.core.model.CustomerData
import com.mux.stats.sdk.core.model.CustomerPlayerData
import com.mux.stats.sdk.core.model.CustomerVideoData
import com.mux.stats.sdk.core.model.CustomerViewData
import com.mux.stats.sdk.muxstats.ExoPlayerBinding
import com.mux.stats.sdk.muxstats.MuxDataSdk
import com.mux.stats.sdk.muxstats.MuxNetwork
import com.mux.stats.sdk.muxstats.MuxStatsSdkMedia3
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.analytics.StreamType
import dev.nonbinary.outis.core.plugin.PlayerHost

/**
 * Binds Mux's Media3 SDK to the ExoPlayer instance. `monitorWithMuxData` is Mux's own extension — it
 * hooks Media3's `AnalyticsListener`, so rendition changes, bandwidth and dropped frames are collected
 * natively, not via `PlayerEvent` (ADR-0003).
 */
internal actual fun bindMux(
    appContext: AppContext,
    host: PlayerHost,
    config: MuxConfig,
): MuxBinding? {
    val player = host.nativePlayerHandle.value as? ExoPlayer ?: return null
    val state = host.state.value
    val item = state.mediaItem
    val analytics = item?.analytics

    val playerData = CustomerPlayerData().apply {
        config.playerName?.let { playerName = it }
        config.viewerId?.let { viewerUserId = it }
    }
    val videoData = CustomerVideoData().apply {
        analytics?.videoId?.let { videoId = it }
        // Analytics title overrides, else the display title, so an app that set one for chrome need not
        // repeat it for QoS.
        (analytics?.title ?: item?.metadata?.title)?.let { videoTitle = it }
        analytics?.series?.let { videoSeries = it }
        analytics?.durationMs?.let { videoDuration = it }
        analytics?.cdn?.let { videoCdn = it }
        videoStreamType = muxStreamType(analytics?.streamType, state.isLive)
    }
    // DRM is view-level in Mux, and comes off the source config rather than the analytics bundle.
    val viewData = CustomerViewData().apply {
        muxDrmType(item?.drmConfig?.scheme)?.let { viewDrmType = it }
    }

    // player_software_name is sourced from IDevice.getPlayerSoftware(), not CustomerData, so brand it by
    // handing the low-level constructor our own device rather than using the monitorWithMuxData() shortcut.
    // The other AndroidDevice fields mirror what data-media3 reports for itself.
    val ctx = appContext.applicationContext
    val device = MuxDataSdk.AndroidDevice(
        ctx,
        MediaLibraryInfo.VERSION,
        MUX_PLUGIN_NAME,
        MUX_PLUGIN_VERSION,
        config.playerSoftware(),
    )
    val stats = MuxStatsSdkMedia3(
        ctx,
        config.envKey,
        CustomerData(playerData, videoData, viewData),
        player,
        null,
        CustomOptions(),
        MuxNetwork(device),
        device,
        MuxDataSdk.LogcatLevel.NONE,
        ExoPlayerBinding(),
    )
    return object : MuxBinding {
        override fun dispose() = stats.release()
    }
}

internal actual val nativePlayerLabel: String = "ExoPlayer"

// data-media3 reports player_software_name via IDevice, not CustomerData. These mirror the identifiers it
// uses for itself; keep MUX_PLUGIN_VERSION in step with the data-media3 dependency (it only feeds the
// player_mux_plugin_version dimension). player_software_name is the field we actually override.
private const val MUX_PLUGIN_NAME = "mux-media3"
private const val MUX_PLUGIN_VERSION = "1.13.0"

/** Mux's stream-type vocabulary. When the item does not declare it, fall back to the player's live signal. */
private fun muxStreamType(type: StreamType?, isLive: Boolean): String = when {
    type == StreamType.LIVE || (type == null && isLive) -> "live"
    else -> "on-demand"
}
