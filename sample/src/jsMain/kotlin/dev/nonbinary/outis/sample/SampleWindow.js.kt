/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.ui.window.PlayerWindow
import kotlinx.browser.document
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.events.Event

/** Safari's own name for what the standard calls picture-in-picture. */
private const val WEBKIT_PIP_MODE = "picture-in-picture"

/**
 * Browser fullscreen and picture-in-picture.
 *
 * Fullscreen goes through the Fullscreen API on the **document element**, so the Compose canvas and the
 * engine's `<video>` underneath it go fullscreen together — that is what keeps the shared overlay
 * compositing over the video rather than being left behind in the windowed page.
 *
 * Picture-in-picture goes to the `<video>` itself, reached through [VideoPlayer.nativePlayerHandle].
 * That element is real, in the document and rendered — `PlayerSurface` gives it `position: fixed` and
 * keeps it tracking the Compose surface's bounds — which is what makes it eligible at all: browsers
 * reject `requestPictureInPicture()` on an element that is detached, hidden, or has no video track.
 *
 * Everything here is reached via `asDynamic()`, because Kotlin/JS's DOM externs declare neither API.
 *
 * Both states are read back from events rather than assumed from the last call. The browser leaves
 * either mode without being asked — Escape, the user closing the PiP window, swiping away — and
 * assuming would leave the button offering to exit a mode the page is no longer in.
 */
@Composable
actual fun rememberSampleWindow(player: VideoPlayer): PlayerWindow {
    var isFullscreen by remember { mutableStateOf(false) }
    var isInPip by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val onFullscreenChange: (Event) -> Unit = {
            isFullscreen = document.asDynamic().fullscreenElement != null
        }
        document.addEventListener("fullscreenchange", onFullscreenChange)
        // Safari fires only the webkit-prefixed event for fullscreen changes it initiated.
        document.addEventListener("webkitfullscreenchange", onFullscreenChange)
        onDispose {
            document.removeEventListener("fullscreenchange", onFullscreenChange)
            document.removeEventListener("webkitfullscreenchange", onFullscreenChange)
        }
    }

    // `nativePlayerHandle` is a plain property, not Compose state, so reading it directly captures
    // whatever it happened to be at first composition and never updates. That is not hypothetical: the
    // PiP button stayed hidden until some *unrelated* state change — toggling fullscreen — forced a
    // recomposition that re-read it. Observed through the same event the surface uses, it is correct
    // from the first frame, and it also survives the engine attaching a replacement element.
    val video by produceState(player.nativePlayerHandle as? HTMLVideoElement, player) {
        value = player.nativePlayerHandle as? HTMLVideoElement
        player.events.collect { event ->
            if (event is PlayerEvent.NativePlayerAttached) value = event.handle as? HTMLVideoElement
        }
    }
    var isPipSupported by remember { mutableStateOf(false) }

    DisposableEffect(video) {
        val element = video
        if (element == null) {
            isPipSupported = false
            onDispose { }
        } else {
            // Re-asked on `loadedmetadata` because support is a question about the element's *content*,
            // not just the browser: Safari answers `webkitSupportsPresentationMode` with `false` while
            // the element still has no video track, which every element is on the frame it is created.
            val refreshSupport: (Event) -> Unit = { isPipSupported = element.supportsPip() }
            isPipSupported = element.supportsPip()

            val onEnter: (Event) -> Unit = { isInPip = true }
            val onLeave: (Event) -> Unit = { isInPip = false }
            // Safari reports both directions through one event, carrying the mode on the element.
            val onWebkitChange: (Event) -> Unit = {
                isInPip = element.asDynamic().webkitPresentationMode == WEBKIT_PIP_MODE
            }
            element.addEventListener("loadedmetadata", refreshSupport)
            element.addEventListener("enterpictureinpicture", onEnter)
            element.addEventListener("leavepictureinpicture", onLeave)
            element.addEventListener("webkitpresentationmodechanged", onWebkitChange)
            onDispose {
                element.removeEventListener("loadedmetadata", refreshSupport)
                element.removeEventListener("enterpictureinpicture", onEnter)
                element.removeEventListener("leavepictureinpicture", onLeave)
                element.removeEventListener("webkitpresentationmodechanged", onWebkitChange)
            }
        }
    }

    return PlayerWindow(
        isFullscreen = isFullscreen,
        isInPip = isInPip,
        isPipSupported = isPipSupported,
        onToggleFullscreen = { wantFullscreen ->
            if (wantFullscreen) {
                document.documentElement?.asDynamic()?.requestFullscreen()
            } else {
                document.asDynamic().exitFullscreen()
            }
        },
        onEnterPip = { video?.requestPip() == true },
    )
}

/**
 * Whether this element can currently be put into picture-in-picture.
 *
 * Safari is asked first and on its own terms. `webkitSupportsPresentationMode` is a question about
 * *this element* — a video with no video track answers `false` — whereas the standard
 * `document.pictureInPictureEnabled` is a document-wide capability flag that says nothing about the
 * element. The stricter, more accurate question wins where it is available.
 *
 * `disablePictureInPicture` is the author-facing opt-out, honoured so that setting the attribute hides
 * the button rather than leaving it present and rejected.
 */
private fun HTMLVideoElement.supportsPip(): Boolean {
    val element = asDynamic()
    if (element.disablePictureInPicture == true) return false
    if (element.webkitSupportsPresentationMode != null) {
        return element.webkitSupportsPresentationMode(WEBKIT_PIP_MODE) == true
    }
    return document.asDynamic().pictureInPictureEnabled == true
}

/**
 * Asks for picture-in-picture: standard API first, Safari's fallback second.
 *
 * The return value is optimistic by necessity. `requestPictureInPicture()` resolves a `Promise`, so the
 * real answer arrives after this function has returned, while `PlayerWindow.onEnterPip` is synchronous.
 * The honest signal is the `enterpictureinpicture` event driving `isInPip` above; the boolean here only
 * reports that a request was successfully *dispatched*. A rejected promise is swallowed rather than left
 * unhandled — that is what a user dismissing the permission prompt produces, and it is not an error.
 */
private fun HTMLVideoElement.requestPip(): Boolean {
    val element = asDynamic()
    return when {
        element.requestPictureInPicture != null -> {
            element.requestPictureInPicture()?.catch { _: Any? -> Unit }
            true
        }
        element.webkitSetPresentationMode != null -> {
            element.webkitSetPresentationMode(WEBKIT_PIP_MODE)
            true
        }
        else -> false
    }
}
