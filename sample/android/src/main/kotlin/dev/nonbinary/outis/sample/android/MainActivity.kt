/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.sample.App
import dev.nonbinary.outis.sample.ImmersiveHost
import dev.nonbinary.outis.sample.applyImmersive

/**
 * The entire Android host. Everything visible lives in `:sample`'s [App], shared verbatim with iOS and
 * Web — this class exists because the Kotlin Multiplatform Android plugin builds libraries, so an
 * installable application needs a `com.android.application` module of its own.
 *
 * [ComponentActivity] rather than a bare `Activity`: `:ui`'s `rememberPlayerWindow` observes the
 * Activity lifecycle to track picture-in-picture, and that requires a lifecycle owner.
 *
 * Implements [ImmersiveHost] so the shared UI can request fullscreen. The hide MUST be re-applied from
 * [onWindowFocusChanged]: the platform re-shows the system bars on every focus regain (the embedded video
 * SurfaceView relayout on entering fullscreen, a dialog dismissing, returning from PiP), so applying it
 * only from Compose — before that focus event lands — leaves the status bar stranded visible.
 */
class MainActivity : ComponentActivity(), ImmersiveHost {

    override var immersiveRequested: Boolean = false
        set(value) {
            field = value
            // Apply now for the common case (toggling while focused); onWindowFocusChanged re-asserts it
            // after the focus churn that would otherwise put the bars back.
            applyImmersive(value)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // The application context, not this Activity: the engine outlives any single Activity
            // instance, and holding an Activity reference in a player would leak it across a
            // configuration change.
            App(AppContext(applicationContext))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The point at which the window is guaranteed to have focus. Re-hide here so the status bar can't
        // creep back after the SurfaceView relayout / a dialog / PiP hands focus back to the player.
        if (hasFocus && immersiveRequested) applyImmersive(true)
    }
}
