/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.sample.tv.TvApp

/**
 * The Android TV host: the leanback launcher starts this, phones and tablets start [MainActivity]. Both
 * live in one APK, so the TV screen ships without a second application module. Everything visible is
 * `:sample`'s [TvApp].
 */
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The application context, as in MainActivity: the engine must not hold an Activity.
            TvApp(AppContext(applicationContext))
        }
    }
}
