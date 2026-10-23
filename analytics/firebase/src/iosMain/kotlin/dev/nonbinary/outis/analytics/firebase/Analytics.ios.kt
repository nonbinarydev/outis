/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.firebase

import cocoapods.FirebaseAnalytics.FIRAnalytics
import cocoapods.FirebaseCore.FIRApp
import dev.nonbinary.outis.core.AppContext
import kotlinx.cinterop.ExperimentalForeignApi

/** Logs through the Firebase Analytics SDK, which reports to GA4. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun logToAnalytics(appContext: AppContext, name: String, params: Map<String, Any>) {
    if (!firebaseConfigured()) return
    // Kotlin numbers and strings cross into Objective-C as NSNumber and NSString.
    FIRAnalytics.logEventWithName(name, parameters = params.mapKeys { (key, _) -> key as Any? })
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun setAnalyticsCollection(appContext: AppContext, enabled: Boolean) {
    if (firebaseConfigured()) FIRAnalytics.setAnalyticsCollectionEnabled(enabled)
}

/** Whether the app has called `FirebaseApp.configure()`, which Firebase requires before any of this. */
@OptIn(ExperimentalForeignApi::class)
private fun firebaseConfigured(): Boolean = FIRApp.defaultApp() != null
