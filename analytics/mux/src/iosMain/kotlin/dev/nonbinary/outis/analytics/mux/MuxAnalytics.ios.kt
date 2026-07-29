/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.mux

import cocoapods.Mux_Stats_AVPlayer.MUXSDKCustomerData
import cocoapods.Mux_Stats_AVPlayer.MUXSDKCustomerPlayerData
import cocoapods.Mux_Stats_AVPlayer.MUXSDKCustomerVideoData
import cocoapods.Mux_Stats_AVPlayer.MUXSDKStats
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.analytics.StreamType
import dev.nonbinary.outis.core.plugin.PlayerHost
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVKit.AVPlayerViewController

private var monitorCounter = 0

internal actual val nativePlayerLabel: String = "AVPlayer"

/**
 * Binds Mux's iOS Data SDK to the `:ui` [AVPlayerViewController], read off
 * [PlayerHost.nativePresentationHandle] — Mux monitors the view controller, not the bare `AVPlayer`.
 * Returns `null` until the surface has mounted (no VC yet), matching Android/web's "not ready" case.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun bindMux(
    appContext: AppContext,
    host: PlayerHost,
    config: MuxConfig,
): MuxBinding? {
    val controller = host.nativePresentationHandle.value as? AVPlayerViewController ?: return null
    val state = host.state.value
    val item = state.mediaItem
    val analytics = item?.analytics

    val playerData = MUXSDKCustomerPlayerData(config.envKey).apply {
        config.playerName?.let { setPlayerName(it) }
        config.viewerId?.let { setViewerUserId(it) }
        setPlayerSoftwareName(config.playerSoftware())
    }
    val videoData = MUXSDKCustomerVideoData().apply {
        analytics?.videoId?.let { setVideoId(it) }
        (analytics?.title ?: item?.metadata?.title)?.let { setVideoTitle(it) }
        analytics?.series?.let { setVideoSeries(it) }
        analytics?.cdn?.let { setVideoCdn(it) }
        setVideoStreamType(muxStreamType(analytics?.streamType, state.isLive))
    }
    val customerData = MUXSDKCustomerData(
        customerPlayerData = playerData,
        videoData = videoData,
        viewData = null,
    )

    val name = "outis-${monitorCounter++}"
    MUXSDKStats.monitorAVPlayerViewController(controller, withPlayerName = name, customerData = customerData)
    return object : MuxBinding {
        override fun dispose() {
            MUXSDKStats.destroyPlayer(name)
        }
    }
}

/** Mux's stream-type vocabulary. Falls back to the player's live signal when the item does not declare it. */
private fun muxStreamType(type: StreamType?, isLive: Boolean): String = when {
    type == StreamType.LIVE || (type == null && isLive) -> "live"
    else -> "on-demand"
}
