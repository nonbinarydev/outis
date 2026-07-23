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

/**
 * The entire Android host. Everything visible lives in `:sample`'s [App], shared verbatim with iOS and
 * Web — this class exists because the Kotlin Multiplatform Android plugin builds libraries, so an
 * installable application needs a `com.android.application` module of its own.
 *
 * [ComponentActivity] rather than a bare `Activity`: `:ui`'s `rememberPlayerWindow` observes the
 * Activity lifecycle to track picture-in-picture, and that requires a lifecycle owner.
 */
class MainActivity : ComponentActivity() {
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
}
