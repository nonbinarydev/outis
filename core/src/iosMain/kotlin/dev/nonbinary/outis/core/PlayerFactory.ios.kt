/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

internal actual fun createPlatformPlayer(context: AppContext, config: PlayerConfig): VideoPlayer =
    AVPlayerEngine(config)
