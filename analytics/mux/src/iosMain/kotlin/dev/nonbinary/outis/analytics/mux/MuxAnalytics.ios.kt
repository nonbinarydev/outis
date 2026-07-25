/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.mux

import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.plugin.PlayerHost

/**
 * Stub. The real Mux AVPlayer SDK (`Mux-Stats-AVPlayer`) is CocoaPods/SPM and cannot be built or
 * verified on this or a Linux CI runner, and there is no iOS host yet — see #21 and ADR-0003.
 *
 * Returning `null` means the adapter simply records no binding on iOS: attaching MuxAnalytics is a
 * no-op there rather than a crash, so common code stays uniform.
 */
internal actual fun bindMux(
    handle: Any,
    appContext: AppContext,
    host: PlayerHost,
    config: MuxConfig,
): MuxBinding? = null
