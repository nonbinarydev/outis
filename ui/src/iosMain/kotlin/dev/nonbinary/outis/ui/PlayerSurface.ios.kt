/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.ads.setAdContainer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVKit.AVPlayerViewController

/**
 * iOS surface backed by [AVPlayerViewController] (`showsPlaybackControls = false`) hosted via Compose's
 * UIKit interop. AVKit renders the video and the media's own subtitle cues natively, so our Compose
 * overlay just draws on top. The one exception is a sidecar subtitle file, which AVPlayer cannot take:
 * its cues arrive in [dev.nonbinary.outis.core.PlayerState.sidecarCues] and are drawn here, when
 * [showSubtitles] allows. The Media [AVPlayer] is read from [VideoPlayer.nativePlayerHandle] and
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
public actual fun PlayerSurface(
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
    Box(modifier) {
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
            modifier = Modifier.fillMaxSize(),
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
        if (showSubtitles) SidecarCues(player, Modifier.matchParentSize())
    }
}

/**
 * The cues of the item's sidecar subtitle file, drawn as captions over the video. Collected here rather than
 * in [PlayerSurface], so a change of cue recomposes this alone and not the UIKit interop.
 */
@Composable
private fun SidecarCues(player: VideoPlayer, modifier: Modifier = Modifier) {
    val cues by remember(player) { player.state.map { it.sidecarCues }.distinctUntilChanged() }
        .collectAsState(initial = emptyList())
    if (cues.isEmpty()) return
    BoxWithConstraints(modifier) {
        val height = if (maxHeight == Dp.Infinity) 0.dp else maxHeight
        BasicText(
            text = cues.joinToString("\n"),
            style = TextStyle(
                color = Color.White,
                fontSize = (height.value * CUE_TEXT_HEIGHT).coerceAtLeast(MIN_CUE_TEXT_SP).sp,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = height * CUE_BOTTOM_INSET)
                .background(CueBackground, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Caption text height and bottom inset, as fractions of the surface's height. */
private const val CUE_TEXT_HEIGHT = 0.045f
private const val CUE_BOTTOM_INSET = 0.06f
private const val MIN_CUE_TEXT_SP = 12f
private val CueBackground = Color(0xBF000000)

@OptIn(ExperimentalForeignApi::class)
private fun ContentScale.toVideoGravity(): String = when (this) {
    ContentScale.Crop -> AVLayerVideoGravityResizeAspectFill
    ContentScale.FillBounds -> AVLayerVideoGravityResize
    else -> AVLayerVideoGravityResizeAspect
}!! // framework string constants are non-null at runtime
