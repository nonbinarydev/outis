/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.ads

import dev.nonbinary.outis.core.AVPlayerEngine
import dev.nonbinary.outis.core.ExperimentalPlayerApi
import dev.nonbinary.outis.core.PlayerState
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.engine
import platform.UIKit.UIViewController

/**
 * Push client-side-ad (CSAI) state into the player's [PlayerState.adState], or `null` to clear it once
 * the break ends. Called by an iOS IMA adapter that owns the Google IMA SDK: that SDK is UIKit-centric and
 * pulled in via CocoaPods, so it lives in the integration layer rather than the pod-free core, and this is
 * how it drives the shared chrome. The adapter pauses and resumes the content player itself (IMA iOS'
 * content pause/resume model). A no-op on a player that is not the AVPlayer engine.
 */
@ExperimentalPlayerApi
fun VideoPlayer.setAdState(adState: AdState?) {
    (engine as? AVPlayerEngine)?.setAdState(adState)
}

/**
 * Register the view controller IMA's ad UI anchors to — the `AVPlayerViewController` that `:ui`'s
 * `PlayerSurface` creates — or `null` to detach it. An iOS IMA adapter reads it back with [adContainer]
 * to build its `IMAAdDisplayContainer`. The iOS counterpart of Android's `setAdContainer`; a no-op on a
 * player that is not the AVPlayer engine.
 */
@ExperimentalPlayerApi
fun VideoPlayer.setAdContainer(controller: UIViewController?) {
    (engine as? AVPlayerEngine)?.setAdContainer(controller)
}

/** The registered ad-container view controller (see [setAdContainer]), or `null`. */
@ExperimentalPlayerApi
fun VideoPlayer.adContainer(): UIViewController? = (engine as? AVPlayerEngine)?.adContainer()
