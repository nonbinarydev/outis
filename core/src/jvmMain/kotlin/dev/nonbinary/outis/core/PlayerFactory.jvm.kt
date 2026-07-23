/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

/**
 * No JVM/desktop playback engine — JVM exists only to compile the API and run unit tests fast.
 * Use the test Fake (`FakeVideoPlayer`) in tests instead.
 */
internal actual fun createPlatformPlayer(context: AppContext, config: PlayerConfig): VideoPlayer =
    throw UnsupportedOperationException(
        "JVM is an API/test-only target — there is no JVM playback engine. Use a Fake in tests.",
    )
