/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import android.content.Context

/**
 * Android [AppContext] — holds the application context, required by `ExoPlayer.Builder`.
 *
 * Pass `AppContext(context.applicationContext)`. The Media3 engine (PR2) reads this.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual class AppContext(
    /**
     * The process-wide `Application` context. Pass `context.applicationContext`, **never** an Activity
     * or other short-lived `Context` — the player outlives configuration changes and holding an
     * Activity here leaks it.
     */
    val applicationContext: Context,
)
