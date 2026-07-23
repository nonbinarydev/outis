/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.ui.controls.DefaultControls
import dev.nonbinary.outis.ui.controls.PlayerControlsScope
import dev.nonbinary.outis.ui.controls.PlayerControlsScopeImpl
import dev.nonbinary.outis.ui.controls.PlayerControlsState
import dev.nonbinary.outis.ui.controls.rememberPlayerControlsState
import dev.nonbinary.outis.ui.window.PlayerWindow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Minimum gap between auto-hide re-arms while the pointer is moving. Without it every mouse-move
 * event would re-arm the timer; ~4x/s is frequent enough that controls never hide under the cursor.
 */
private const val POINTER_MOVE_THROTTLE_MS = 250L

/**
 * Batteries-included player: a [PlayerSurface] with a customisable controls overlay on top.
 *
 * Tiers of customisation:
 * - **Default:** `PlayerView(player)`.
 * - **Tweak/replace overlay:** pass the [controls] trailing lambda (receiver = [PlayerControlsScope])
 *   and compose any layout from the building blocks (`PlayPauseButton()`, `Scrubber()`,
 *   `SubtitleButton()`, `FullscreenButton()`, …), or replace it entirely.
 * - **Controls beside the video** (left rail, custom shell): skip `PlayerView` and use [PlayerSurface]
 *   plus the building-block functions with a hoisted [rememberPlayerControlsState].
 *
 * Input patterns: touch taps toggle the overlay; a mouse hovers to reveal it, clicks to play/pause, and
 * double-clicks to toggle fullscreen; on web, Space/K play-pause, M mutes and F toggles fullscreen while
 * the pointer is over the player. Works on D-pad/TV too — controls are focusable and the overlay grabs
 * focus when it appears. In PIP ([PlayerWindow.isInPip]) the overlay collapses to bare video.
 */
@ExperimentalPlayerUiApi
@Composable
// Assembles surface, controls, gestures, keyboard, auto-hide and window hooks, each behind its own
// flag. The branches are configuration, not logic — this is the one composable that wires the rest.
@Suppress("CyclomaticComplexMethod")
fun PlayerView(
    player: VideoPlayer,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    surfaceType: SurfaceType = SurfaceType.SurfaceView,
    window: PlayerWindow = PlayerWindow(),
    pauseWhenStopped: Boolean = true,
    state: PlayerControlsState = rememberPlayerControlsState(player),
    controls: @Composable PlayerControlsScope.() -> Unit = { DefaultControls() },
) {
    // Pause when the host leaves the foreground — app backgrounded or screen locked (Lifecycle ON_STOP,
    // which does NOT fire while in PiP, so PiP keeps playing) — and resume on return if it was playing.
    // Event effects (not start/stop-or-dispose) so merely moving the player to another PlayerView, e.g.
    // toggling fullscreen, doesn't pause it. Consumers wanting background playback set pauseWhenStopped=false.
    if (pauseWhenStopped) {
        val resumeOnStart = remember { mutableStateOf(false) }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
            resumeOnStart.value = player.state.value.playWhenReady
            player.pause()
        }
        LifecycleEventEffect(Lifecycle.Event.ON_START) {
            if (resumeOnStart.value) {
                player.play()
                resumeOnStart.value = false
            }
        }
    }
    val focusRequester = remember { FocusRequester() }
    // The window is tracked as State, and nothing below is keyed on the PlayerWindow *instance*.
    // PlayerWindow is a data class of lambdas that hosts rebuild each pass, so it is never equal to its
    // predecessor; keying on it restarted the gesture detector on every recomposition, and a host that
    // recomposes at playback cadence could lose taps entirely because awaitEachGesture never survived
    // long enough to see a down and its matching up.
    val currentWindow = rememberUpdatedState(window)
    val scope = remember(state, focusRequester) { PlayerControlsScopeImpl(state, currentWindow, focusRequester) }
    // True while a mouse hovers the player. Gates the web keyboard shortcuts so Space etc. only act on the
    // player — when the pointer is elsewhere the page still scrolls on Space as normal.
    val pointerOver = remember { mutableStateOf(false) }
    // Web-only keyboard shortcuts via a DOM keydown listener. Compose/JS routes keys through its focus
    // system, which is unreliable on the skiko canvas (Safari especially), so on web we listen on the
    // document directly; Android/iOS no-op (TV uses the D-pad wake below).
    PlatformPlayerKeyboard(state, window) { pointerOver.value }
    Box(
        modifier = modifier
            // TV/D-pad: any key wakes hidden controls, or re-arms auto-hide while they're up. Never consumes.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    if (state.controlsVisible) state.notifyInteraction() else state.showControls()
                }
                false
            }
            // Tap / click. TOUCH → toggle overlay (mobile). MOUSE → single click = play/pause (immediate);
            // a double click = fullscreen (desktop). awaitFirstDown ignores presses consumed by a control,
            // so clicking a button does only that.
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (waitForUpOrCancellation() == null) return@awaitEachGesture
                    if (down.type == PointerType.Touch) {
                        state.toggleControls()
                        return@awaitEachGesture
                    }
                    // Mouse: act on the single click immediately (no double-click lag).
                    state.playPause()
                    state.showControls()
                    // Where fullscreen is wired, a quick second click = double-click → fullscreen; undo the
                    // first play/pause so a double-click leaves playback state unchanged.
                    val liveWindow = currentWindow.value
                    val toggleFullscreen = liveWindow.onToggleFullscreen
                    if (toggleFullscreen != null) {
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown() }
                        if (second != null) {
                            waitForUpOrCancellation()
                            state.playPause()
                            toggleFullscreen(!currentWindow.value.isFullscreen)
                        }
                    }
                }
            }
            // Pointer hover (web/desktop): moving the mouse reveals the controls + re-arms auto-hide and
            // marks the pointer over the player (for the keyboard gate); leaving hides them. Enter/Exit and
            // button-less Move only fire for mouse/stylus, so on touch this is a no-op. Observed without
            // consuming, so taps, clicks and scrubbing still work.
            .pointerInput(state) {
                awaitPointerEventScope {
                    var lastWakeMs = 0L
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Enter, PointerEventType.Move ->
                                if (event.changes.none { it.pressed }) {
                                    pointerOver.value = true
                                    val now = event.changes.firstOrNull()?.uptimeMillis ?: 0L
                                    if (!state.controlsVisible) {
                                        state.showControls()
                                        lastWakeMs = now
                                    } else if (now - lastWakeMs >= POINTER_MOVE_THROTTLE_MS) {
                                        state.notifyInteraction()
                                        lastWakeMs = now
                                    }
                                }
                            PointerEventType.Exit -> {
                                pointerOver.value = false
                                if (!state.keepVisible) state.hideControls()
                            }
                        }
                    }
                }
            },
    ) {
        PlayerSurface(player, Modifier.matchParentSize(), contentScale, surfaceType)
        if (!window.isInPip) scope.controls()
    }
}
