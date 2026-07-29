/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.mux

import androidx.media3.exoplayer.ExoPlayer
import com.mux.stats.sdk.core.model.CustomerData
import com.mux.stats.sdk.core.model.CustomerPlayerData
import com.mux.stats.sdk.core.model.CustomerVideoData
import com.mux.stats.sdk.core.model.CustomerViewData
import com.mux.stats.sdk.muxstats.monitorWithMuxData
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

    val stats = player.monitorWithMuxData(
        appContext.applicationContext,
        config.envKey,
        CustomerData(playerData, videoData, viewData),
    )
    return object : MuxBinding {
        override fun dispose() = stats.release()
    }
}

/** Mux's stream-type vocabulary. When the item does not declare it, fall back to the player's live signal. */
private fun muxStreamType(type: StreamType?, isLive: Boolean): String = when {
    type == StreamType.LIVE || (type == null && isLive) -> "live"
    else -> "on-demand"
}
