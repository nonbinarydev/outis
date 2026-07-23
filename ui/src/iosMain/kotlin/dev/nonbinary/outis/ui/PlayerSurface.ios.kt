/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.setAdContainer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVKit.AVPlayerViewController

/**
 * iOS surface backed by [AVPlayerViewController] (`showsPlaybackControls = false`) hosted via Compose's
 * UIKit interop. AVKit renders the video and the selected subtitle cues natively, so our Compose
 * overlay just draws on top. The Media [AVPlayer] is read from [VideoPlayer.nativePlayerHandle] and
 * refreshed on [PlayerEvent.NativePlayerAttached].
 *
 * The interop is **non-interactive** so the Compose overlay receives every touch immediately (no
 * cooperative-gesture delay), and `onRelease` clears the controller's player so it can't keep a
 * detached AVPlayer alive (or keep playing audio) after the surface leaves composition.
 */
// The parameter order matches the `expect` declaration, where `modifier` correctly leads the
// optional parameters. Kotlin forbids default values on an `actual`, so the rule cannot see which
// parameters are optional here and reads the ordering as wrong.
@Suppress("ComposableParametersOrdering")
@OptIn(ExperimentalForeignApi::class)
@ExperimentalPlayerUiApi
@Composable
actual fun PlayerSurface(
    player: VideoPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    surfaceType: SurfaceType,
    showSubtitles: Boolean,
) {
    val avPlayer by produceState(player.nativePlayerHandle as? AVPlayer, player) {
        value = player.nativePlayerHandle as? AVPlayer
        player.events.collect { event ->
            if (event is PlayerEvent.NativePlayerAttached) value = event.handle as? AVPlayer
        }
    }
    val gravity = contentScale.toVideoGravity()
    UIKitViewController(
        factory = {
            val controller = AVPlayerViewController()
            controller.setShowsPlaybackControls(false)
            controller.player = avPlayer
            controller.videoGravity = gravity
            // Hand this controller to the engine so an iOS IMA adapter can anchor its ad UI over the video
            // (the ad-display container view + presenting view controller) for client-side ads.
            player.setAdContainer(controller)
            controller
        },
        modifier = modifier,
        update = { controller ->
            controller.player = avPlayer
            controller.videoGravity = gravity
        },
        onRelease = { controller ->
            controller.player = null
            player.setAdContainer(null)
        },
        properties = UIKitInteropProperties(interactionMode = null), // non-interactive: overlay gets all touches
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun ContentScale.toVideoGravity(): String = when (this) {
    ContentScale.Crop -> AVLayerVideoGravityResizeAspectFill
    ContentScale.FillBounds -> AVLayerVideoGravityResize
    else -> AVLayerVideoGravityResizeAspect
}!! // framework string constants are non-null at runtime
