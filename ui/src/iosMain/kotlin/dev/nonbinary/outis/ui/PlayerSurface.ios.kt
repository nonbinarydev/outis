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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitViewController
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.ads.setAdContainer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.presentationSize
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
        if (showSubtitles) {
            SidecarCues(
                player,
                avPlayer,
                fitted = gravity == AVLayerVideoGravityResizeAspect,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * The cues of the item's sidecar subtitle file, drawn as captions on the picture: inside it, not in the bars,
 * when [fitted] letterboxes it, as AVKit draws a stream's own subtitles. Collected here rather than in
 * [PlayerSurface], so a change of cue recomposes this alone and not the UIKit interop.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
private fun SidecarCues(player: VideoPlayer, avPlayer: AVPlayer?, fitted: Boolean, modifier: Modifier = Modifier) {
    val cues by remember(player) { player.state.map { it.sidecarCues }.distinctUntilChanged() }
        .collectAsState(initial = emptyList())
    if (cues.isEmpty()) return
    BoxWithConstraints(modifier) {
        // Not snapshot state: read again with every cue and every change of surface.
        val aspect = avPlayer?.currentItem?.presentationSize?.useContents { width / height }
            ?.takeIf { it > 0.0 && it.isFinite() }
        val surface = DpSize(maxWidth, maxHeight)
        val picture = if (fitted && aspect != null) fit(aspect, surface) else surface
        Box(Modifier.align(Alignment.Center).size(picture)) {
            BasicText(
                text = cues.joinToString("\n"),
                style = TextStyle(
                    color = Color.White,
                    fontSize = (picture.height.value * CUE_TEXT_HEIGHT).coerceAtLeast(MIN_CUE_TEXT_SP).sp,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = picture.height * CUE_BOTTOM_INSET)
                    .background(CueBackground, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** The largest size of [aspect], width over height, that fits in [bounds]. */
private fun fit(aspect: Double, bounds: DpSize): DpSize {
    val ratio = aspect.toFloat()
    return if (bounds.width > bounds.height * ratio) {
        DpSize(bounds.height * ratio, bounds.height)
    } else {
        DpSize(bounds.width, bounds.width / ratio)
    }
}

/** Caption text height and bottom inset, as fractions of the picture's height. */
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
