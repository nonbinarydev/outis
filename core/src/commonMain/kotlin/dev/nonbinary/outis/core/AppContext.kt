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
 *
 * An `expect class`, which Kotlin still ships as Beta (KT-61573), and kept one on purpose: Swift sees a
 * class constructor as `AppContext()`, where the factory-function alternative surfaces as
 * `AppContext_iosKt.AppContext()`. The build passes `-Xexpect-actual-classes` to accept it.
 */
expect class AppContext
