/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import android.app.Activity
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Bridge between the shared Compose UI (which decides *when* the player is fullscreen) and the host
 * Activity (the only place the platform guarantees the window has focus). Mirrors the iOS `FullscreenBridge`.
 *
 * The hiding **must** be re-applied from [android.app.Activity.onWindowFocusChanged]: with
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` the platform re-shows the system bars on every focus regain, and
 * entering fullscreen resizes the embedded video `SurfaceView` — a relayout/focus event that lands right
 * after the toggle and puts the status bar straight back. Applying the hide only from Compose (before that
 * event) is why the bar looked like it never hid. The Activity implements this and re-hides on focus.
 */
interface ImmersiveHost {
    /** The desired fullscreen state. Applied immediately on set, and re-applied on every focus regain. */
    var immersiveRequested: Boolean
}

/**
 * Hides or restores the system bars. `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` keeps a way back — the bars
 * return on an edge swipe and then retreat. `setDecorFitsSystemWindows(false)` is re-asserted because the
 * controller only behaves once the window is laying out edge to edge. Deliberately does not touch
 * orientation. Public (not internal) because `MainActivity` calls it from the separate `:sample:android`
 * module, across which `internal` is not visible.
 */
@Suppress("DEPRECATION")
fun Activity.applyImmersive(immersive: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    if (immersive) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // Legacy belt-and-braces for OEM ROMs that ignore the controller; harmless on stock Android.
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
