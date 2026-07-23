/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.setAdViewProvider

/**
 * Android surface backed by Media3's [PlayerView] with `useController = false` — it owns the video
 * SurfaceView, the shutter, and the native subtitle [androidx.media3.ui.SubtitleView] (so selected
 * text tracks render with no extra work). We draw our own Compose overlay on top.
 *
 * The Media3 [Player] is obtained from [VideoPlayer.nativePlayerHandle] and refreshed when the engine
 * re-emits [PlayerEvent.NativePlayerAttached] (e.g. a future config-change rebind).
 */
// The parameter order matches the `expect` declaration, where `modifier` correctly leads the
// optional parameters. Kotlin forbids default values on an `actual`, so the rule cannot see which
// parameters are optional here and reads the ordering as wrong.
@Suppress("ComposableParametersOrdering")
@OptIn(UnstableApi::class)
@ExperimentalPlayerUiApi
@Composable
actual fun PlayerSurface(
    player: VideoPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    surfaceType: SurfaceType,
    showSubtitles: Boolean,
) {
    val media3Player by produceState<Player?>(player.nativePlayerHandle as? Player, player) {
        value = player.nativePlayerHandle as? Player
        player.events.collect { event ->
            if (event is PlayerEvent.NativePlayerAttached) value = event.handle as? Player
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            view.player = media3Player
            // This Media3 PlayerView is an AdViewProvider — hand it to the engine so IMA renders its ad
            // UI (skip/countdown/click-through) into this surface for client-side ads.
            player.setAdViewProvider(view)
            view.resizeMode = when (contentScale) {
                ContentScale.Crop -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                ContentScale.FillBounds -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            view.subtitleView?.visibility = if (showSubtitles) View.VISIBLE else View.GONE
        },
        onRelease = {
            it.player = null
            player.setAdViewProvider(null)
        },
    )
}
