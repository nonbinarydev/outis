/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.VideoPlayer
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

/**
 * Web surface — the Compose control overlay **on top of** the video, the same shared overlay used on
 * Android/iOS.
 *
 * Compose Multiplatform draws the UI into a single `<canvas>` (skiko). Instead of mounting the engine's
 * `<video>` on top (which would occlude the overlay), the video is kept **underneath** the canvas and a
 * transparent hole is punched through the canvas over this surface's rect (`BlendMode.Clear` — skiko
 * presents the canvas with per-pixel alpha, so the video shows through). Native `controls` are off; the
 * Compose overlay composites over the video.
 *
 * Layering (bottom→top): page background → `<video>` (`z-index: 0`) → Compose canvas (`z-index: 1`,
 * raised by [raiseComposeHostsAbove], carrying the hole) → controls. The `<video>` (from
 * [VideoPlayer.nativePlayerHandle], refreshed on [PlayerEvent.NativePlayerAttached]) is `position:
 * fixed` and continuously repositioned to track this surface's on-screen bounds.
 *
 * Positions are converted from Compose px to CSS px by dividing by [LocalDensity] (which equals the
 * browser `devicePixelRatio`). Repositioning happens in [onGloballyPositioned] without writing Compose
 * state, so scrolling/resizing the hero doesn't churn recomposition.
 */
// The parameter order matches the `expect` declaration, where `modifier` correctly leads the
// optional parameters. Kotlin forbids default values on an `actual`, so the rule cannot see which
// parameters are optional here and reads the ordering as wrong.
@Suppress("ComposableParametersOrdering")
@ExperimentalPlayerUiApi
@Composable
actual fun PlayerSurface(
    player: VideoPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    surfaceType: SurfaceType,
    showSubtitles: Boolean,
) {
    val video by produceState(player.nativePlayerHandle as? HTMLVideoElement, player) {
        value = player.nativePlayerHandle as? HTMLVideoElement
        player.events.collect { event ->
            if (event is PlayerEvent.NativePlayerAttached) value = event.handle as? HTMLVideoElement
        }
    }
    val density = LocalDensity.current.density
    // Latest on-screen rect in CSS px. A plain holder (not Compose state) so per-frame layout updates
    // during scroll reposition the DOM element directly without recomposing.
    val placement = remember { ElementPlacement() }

    DisposableEffect(video, contentScale) {
        val v = video
        if (v != null) {
            // Video UNDERNEATH the transparent Compose canvas (see the file header), native controls off.
            // z-index 0 keeps it above the page background — a NEGATIVE z-index would hide it BEHIND the
            // body's background; the Compose host is lifted above it instead (raiseComposeHostsAbove).
            v.controls = false
            with(v.style) {
                position = "fixed"
                margin = "0"
                backgroundColor = "black"
                zIndex = "0"
                setProperty("object-fit", contentScale.toObjectFit())
            }
            placement.applyTo(v)
            document.body?.appendChild(v)
            raiseComposeHostsAbove(v)
        }
        onDispose {
            if (v != null) {
                v.controls = false
                v.parentNode?.removeChild(v)
            }
        }
    }

    // On a fatal error the (behind-the-overlay) video would show a frozen/garbage frame through the
    // canvas hole. Hide it so the Compose error overlay paints over a clean background; restored on the
    // next good load.
    val errored = player.state.collectAsState().value.error != null
    LaunchedEffect(video, errored) {
        video?.let { it.style.display = if (errored) "none" else "" }
    }

    Box(
        modifier
            // Punch a transparent hole in the Compose canvas so the <video> behind shows through. This
            // also clears any opaque background painted behind this rect (e.g. App.kt's Color.Black).
            .drawBehind { drawRect(Color.Transparent, blendMode = BlendMode.Clear) }
            .onGloballyPositioned { coordinates ->
                val pos = coordinates.positionInWindow()
                val size = coordinates.size
                placement.leftPx = pos.x / density
                placement.topPx = pos.y / density
                placement.widthPx = size.width / density
                placement.heightPx = size.height / density
                video?.let(placement::applyTo)
            },
    )
}

private fun ContentScale.toObjectFit(): String = when (this) {
    ContentScale.Crop -> "cover"
    ContentScale.FillBounds -> "fill"
    else -> "contain"
}

/**
 * Lift the Compose canvas host(s) above the (z-index 0) `<video>` so the overlay — and its
 * `BlendMode.Clear` hole — composite on top while the video shows through underneath. Only adds
 * `position: relative` to a *static* host (so its `z-index` takes effect); a host that's already
 * positioned keeps its own layout.
 */
private fun raiseComposeHostsAbove(video: HTMLVideoElement) {
    val children = document.body?.children ?: return
    for (i in 0 until children.length) {
        val el = children.item(i) as? HTMLElement ?: continue
        if (el == video) continue
        if (window.getComputedStyle(el).position == "static") el.style.position = "relative"
        el.style.zIndex = "1"
    }
}

/** Mutable CSS-pixel placement for the DOM `<video>`, applied on layout without triggering recomposition. */
private class ElementPlacement {
    var leftPx: Float = 0f
    var topPx: Float = 0f
    var widthPx: Float = 0f
    var heightPx: Float = 0f

    fun applyTo(v: HTMLVideoElement) {
        with(v.style) {
            left = "${leftPx}px"
            top = "${topPx}px"
            width = "${widthPx}px"
            height = "${heightPx}px"
        }
    }
}
