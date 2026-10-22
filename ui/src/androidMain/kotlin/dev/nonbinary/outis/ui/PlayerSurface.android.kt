/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

import android.graphics.Rect
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.nonbinary.outis.core.PlaybackState
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.setAdViewProvider
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.ui.geometry.Rect as ComposeRect

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
    val media3Player by produceState(player.nativePlayerHandle as? Player, player) {
        value = player.nativePlayerHandle as? Player
        player.events.collect { event ->
            if (event is PlayerEvent.NativePlayerAttached) value = event.handle as? Player
        }
    }

    // Keep the display awake while the user is actually watching — intending playback (playWhenReady) and
    // not idle/ended — so the screen doesn't time out mid-video. Released when paused/ended so a paused
    // player sleeps normally. `keepScreenOn` on the surface View scopes the flag to this view's lifetime
    // (auto-cleared on detach), so nothing leaks the wake-lock. produceState only re-emits on a real
    // change, so the frequent position updates on `player.state` don't churn this.
    val keepAwake by produceState(false, player) {
        player.state.collect { s ->
            value = s.playWhenReady &&
                s.playbackState != PlaybackState.IDLE &&
                s.playbackState != PlaybackState.ENDED
        }
    }

    // Picture-in-picture animates from a source rectangle, and this composable *is* that rectangle —
    // nothing else knows where the video sits on screen. Recorded here so `rememberPlayerWindow` can
    // pass it as a sourceRectHint without every host having to measure its own layout and plumb it in.
    // Written from layout rather than Compose state: it changes on scroll and resize, and recomposing
    // the whole surface to track a rect that only matters at the instant PiP is requested is waste.
    DisposableEffect(player) {
        onDispose { PlayerSurfaceBounds.forget(player) }
    }

    AndroidView(
        modifier = modifier.onGloballyPositioned { coordinates ->
            PlayerSurfaceBounds.record(player, coordinates.boundsInWindow())
        },
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { view ->
            view.player = media3Player
            view.keepScreenOn = keepAwake
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

/**
 * Where each player's video surface currently sits in its window, in window pixels.
 *
 * Exists so [dev.nonbinary.outis.ui.window.rememberPlayerWindow] can supply a picture-in-picture
 * `sourceRectHint` — the rectangle the system animates *from* when shrinking to the PiP tile. Without
 * it the transition animates from the whole window and visibly jumps.
 *
 * Keyed weakly on the player so a released player cannot pin its bounds (or itself) in memory if a
 * composable is torn down without disposing; [forget] is the normal path.
 */
internal object PlayerSurfaceBounds {
    private val bounds = WeakHashMap<VideoPlayer, Rect>()

    fun record(player: VideoPlayer, rect: ComposeRect) {
        // Rounded outwards: a hint that is a pixel small can crop the animation, one a pixel large
        // cannot, and the platform treats this as a hint rather than a constraint either way.
        bounds[player] = Rect(
            floor(rect.left).toInt(),
            floor(rect.top).toInt(),
            ceil(rect.right).toInt(),
            ceil(rect.bottom).toInt(),
        )
    }

    fun forget(player: VideoPlayer) {
        bounds.remove(player)
    }

    /** `null` when the player has no surface on screen — the caller then omits the hint entirely. */
    fun of(player: VideoPlayer): Rect? = bounds[player]?.takeIf { !it.isEmpty }
}
