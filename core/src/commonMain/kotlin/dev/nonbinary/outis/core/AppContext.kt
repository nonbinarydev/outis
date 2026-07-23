/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

/**
 * Holder for the platform application context the player needs at construction.
 *
 * Android wraps `Context` (required for `ExoPlayer.Builder`); iOS, Web and JVM need nothing,
 * so their actuals are empty.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class AppContext
