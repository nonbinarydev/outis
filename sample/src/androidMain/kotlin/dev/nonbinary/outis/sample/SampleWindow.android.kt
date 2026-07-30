/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.ui.window.PlayerWindow
import dev.nonbinary.outis.ui.window.rememberPlayerWindow

/**
 * Android's fullscreen and picture-in-picture hooks.
 *
 * `:ui` already ships most of this: [rememberPlayerWindow] finds the hosting Activity, tracks
 * picture-in-picture through the lifecycle, and reports whether PiP is currently permitted. What it
 * cannot do is decide what "fullscreen" means for a given application — hiding system bars is a
 * decision about the app's own chrome — so that is supplied here.
 *
 * Both capabilities need manifest declarations that a library cannot inject:
 * `android:supportsPictureInPicture="true"`, and `configChanges` covering the size and layout changes
 * that entering PiP causes.
 */
@Composable
actual fun rememberSampleWindow(player: VideoPlayer): PlayerWindow {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val window = rememberPlayerWindow(
        player = player,
        // Route to the Activity ([ImmersiveHost]) rather than hiding the bars from here directly: the hide
        // only sticks when it is also re-applied from the Activity's onWindowFocusChanged, and Compose has
        // no visibility of window focus. See [ImmersiveHost].
        onToggleFullscreen = { wantFullscreen ->
            (activity as? ImmersiveHost)?.immersiveRequested = wantFullscreen
        },
        // Without this a refused PiP is completely silent: :ui turns the platform's refusal into `false`
        // plus this callback, so a host supplying neither leaves the user tapping a button that appears
        // to do nothing — which is exactly how the missing manifest attribute presented.
        onPipUnavailable = {
            Toast.makeText(context, "Picture-in-picture is unavailable here", Toast.LENGTH_SHORT).show()
        },
    )
    // Re-assert the requested state from the live fullscreen flag so a relayout can't strand the top bar,
    // and so the bars are restored if the player leaves composition while still fullscreen. The Activity
    // re-applies on focus regain, which is what actually makes the hide stick. Idempotent, so overlapping
    // with the callback is harmless.
    DisposableEffect(activity, window.isFullscreen) {
        (activity as? ImmersiveHost)?.immersiveRequested = window.isFullscreen
        onDispose { if (window.isFullscreen) (activity as? ImmersiveHost)?.immersiveRequested = false }
    }
    return window
}

/**
 * Compose's `LocalContext` under an Activity is usually a `ContextThemeWrapper` rather than the Activity
 * itself, so unwrap rather than cast. `null` means there is genuinely no Activity — a Compose preview,
 * or an application-context host — and the caller treats that as "no fullscreen", giving the same
 * self-hiding button an application that never implemented it would get.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
