/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:Suppress("MatchingDeclarationName")

package dev.nonbinary.outis.analytics.mux

import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.plugin.PlayerHost
import org.w3c.dom.HTMLVideoElement

/** The mux-embed default export (`mux.monitor` / `mux.destroyMonitor`). Untyped — it is plain JS. */
@JsModule("mux-embed")
@JsNonModule
private external val muxEmbed: dynamic

/**
 * Binds `mux-embed` to the engine's `<video>` element — which is what the web engine exposes as
 * `nativePlayerHandle`. mux-embed hooks the media element directly, so QoS is collected natively rather
 * than translated from `PlayerEvent` (ADR-0003).
 *
 * The `<video>` element itself carries no id, so a stable one is generated and set on it: mux-embed
 * keys its monitor by element id, and `destroyMonitor` needs the same key to tear the right one down.
 */
internal actual fun bindMux(
    handle: Any,
    appContext: AppContext,
    host: PlayerHost,
    config: MuxConfig,
): MuxBinding? {
    val video = handle as? HTMLVideoElement ?: return null
    if (video.id.isEmpty()) video.id = "outis-mux-${monitorCounter++}"
    val elementId = video.id

    val item = host.state.value.mediaItem
    val analytics = item?.analytics

    // mux-embed's data keys are snake_case; unset keys are simply omitted.
    val data: dynamic = js("{}")
    data.env_key = config.envKey
    config.playerName?.let { data.player_name = it }
    config.viewerId?.let { data.viewer_user_id = it }
    analytics?.videoId?.let { data.video_id = it }
    (analytics?.title ?: item?.metadata?.title)?.let { data.video_title = it }
    analytics?.series?.let { data.video_series = it }
    analytics?.durationMs?.let { data.video_duration = it }
    analytics?.cdn?.let { data.video_cdn = it }
    data.video_stream_type = if (host.state.value.isLive) "live" else "on-demand"

    val options: dynamic = js("{}")
    options.data = data
    muxEmbed.monitor(video, options)

    return object : MuxBinding {
        override fun dispose() {
            muxEmbed.destroyMonitor(elementId)
        }
    }
}

private var monitorCounter = 0
