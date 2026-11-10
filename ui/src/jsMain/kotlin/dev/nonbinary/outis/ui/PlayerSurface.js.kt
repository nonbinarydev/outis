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
import org.w3c.dom.events.Event
import kotlin.math.roundToInt

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
public actual fun PlayerSurface(
    player: VideoPlayer,
    modifier: Modifier,
    contentScale: ContentScale,
    surfaceType: SurfaceType,
    showSubtitles: Boolean,
    subtitleSize: Float?,
    subtitleBottom: Float?,
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

    // Subtitle size and gap: native cues (Shaka's default displayer) on the <video>, styled and placed by CueStyler.
    val styler = remember(video, subtitleSize, subtitleBottom) {
        video?.takeIf { subtitleSize != null || subtitleBottom != null }
            ?.let { CueStyler(it, subtitleSize, subtitleBottom) }
    }
    DisposableEffect(styler) {
        styler?.start()
        onDispose { styler?.stop() }
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
                styler?.restyle()
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

private var cueStylers = 0

/**
 * The web side of [PlayerSurface]'s subtitle size and gap, for the native cues Shaka's default text displayer puts on
 * the `<video>`. Size is a `::cue` font size scoped to this element; the gap is each active cue's line position, set
 * as cues change. Placing cues is safe -- it is changing a track's mode that makes Shaka restart its text stream.
 */
private class CueStyler(private val video: HTMLVideoElement, private val size: Float?, private val bottom: Float?) {
    private val cls = "outis-cues-" + (++cueStylers)
    private val sheet = document.createElement("style") as HTMLElement
    private val wired = mutableListOf<dynamic>()
    private val onCue: (Event) -> Unit = { place() }
    private val onAdd: (Event) -> Unit = { wire() }

    fun start() {
        video.classList.add(cls)
        document.head?.appendChild(sheet)
        video.textTracks.asDynamic().addEventListener("addtrack", onAdd)
        wire()
        restyle()
    }

    fun stop() {
        video.classList.remove(cls)
        sheet.parentNode?.removeChild(sheet)
        video.textTracks.asDynamic().removeEventListener("addtrack", onAdd)
        wired.forEach { it.removeEventListener("cuechange", onCue) }
        wired.clear()
    }

    private fun height(): Double = video.clientHeight.takeIf { it > 0 }?.toDouble() ?: window.innerHeight.toDouble()

    private fun wire() {
        val tracks = video.textTracks.asDynamic()
        for (i in 0 until (tracks.length as Int)) {
            val t = tracks[i]
            if (wired.none { it === t }) {
                t.addEventListener("cuechange", onCue)
                wired.add(t)
            }
        }
    }

    fun restyle() {
        size?.let { sheet.textContent = "video.$cls::cue { font-size: ${(it * height()).roundToInt()}px; }" }
        place()
    }

    private fun place() {
        val b = bottom ?: return
        val h = height()
        // A native cue line is its font size plus leading, ~1.36x; Chromium's default font is ~5% of the height.
        val perLine = (size ?: DEFAULT_CUE_SIZE) * h * CUE_LEADING
        for (t in wired) {
            val active = t.activeCues ?: continue
            for (c in 0 until (active.length as Int)) {
                val cue = active[c]
                val lines = ((cue.text as? String).orEmpty().count { it == '\n' } + 1).coerceIn(1, MAX_CUE_LINES)
                cue.snapToLines = false
                cue.lineAlign = "start"
                cue.line = ((h * (1 - b) - lines * perLine) / h * PERCENT).coerceIn(0.0, PERCENT)
            }
        }
    }

    private companion object {
        const val DEFAULT_CUE_SIZE = 0.05f
        const val CUE_LEADING = 1.36
        const val MAX_CUE_LINES = 4
        const val PERCENT = 100.0
    }
}
