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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    return rememberPlayerWindow(
        player = player,
        onToggleFullscreen = { wantFullscreen -> activity?.setImmersive(wantFullscreen) },
        // Without this a refused PiP is completely silent: :ui turns the platform's refusal into `false`
        // plus this callback, so a host supplying neither leaves the user tapping a button that appears
        // to do nothing — which is exactly how the missing manifest attribute presented.
        onPipUnavailable = {
            Toast.makeText(context, "Picture-in-picture is unavailable here", Toast.LENGTH_SHORT).show()
        },
    )
}

/**
 * Hides or restores the system bars.
 *
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` rather than a sticky hide: the bars return on an edge swipe
 * and then retreat by themselves, so a fullscreen player never strands the user without a way back.
 *
 * This deliberately does **not** touch orientation. The manifest declares `configChanges` for it, so the
 * Activity survives rotation either way, and forcing landscape would fight a user who has deliberately
 * locked their device to portrait.
 *
 * `setDecorFitsSystemWindows(false)` is re-asserted rather than assumed. The Activity calls
 * `enableEdgeToEdge()` at startup, which sets it, but that is the host's choice rather than something
 * this function can rely on — and the insets controller only behaves as intended once the window is
 * already laying out edge to edge.
 */
private fun Activity.setImmersive(immersive: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    if (immersive) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
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
