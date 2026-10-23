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
 * Entry point for the iOS host (`sample/iosApp`), which presents it from SwiftUI with a
 * `UIViewControllerRepresentable`. [onCrashReportingConsent] is how the host's Crashlytics learns the
 * viewer's choice; Swift receives its argument as a `KotlinBoolean`.
 */
fun mainViewController(onCrashReportingConsent: (Boolean) -> Unit): UIViewController =
    ComposeUIViewController { App(AppContext(), onCrashReportingConsent = onCrashReportingConsent) }
