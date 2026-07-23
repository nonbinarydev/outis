/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.ui.window.ComposeUIViewController
import dev.nonbinary.outis.core.AppContext
import platform.UIKit.UIViewController

/**
 * Entry point for an iOS host application: present this from SwiftUI with a
 * `UIViewControllerRepresentable`, or set it as the window's root view controller.
 *
 * No Xcode project ships in this repository yet, so nothing calls this — it exists so the iOS target
 * compiles the same shared code the other platforms run, which is what makes this a meaningful
 * portability check rather than a web-only sample.
 */
fun mainViewController(): UIViewController = ComposeUIViewController { App(AppContext()) }
