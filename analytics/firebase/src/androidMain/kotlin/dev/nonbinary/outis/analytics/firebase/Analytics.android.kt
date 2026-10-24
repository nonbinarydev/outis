/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.firebase

import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import dev.nonbinary.outis.core.AppContext

/** Logs through the Firebase Analytics SDK, which reports to GA4. */
internal actual fun logToAnalytics(appContext: AppContext, name: String, params: Map<String, Any>) {
    val analytics = firebaseAnalytics(appContext) ?: return
    val bundle = Bundle()
    params.forEach { (key, value) ->
        when (value) {
            is Long -> bundle.putLong(key, value)
            is Int -> bundle.putLong(key, value.toLong())
            is Double -> bundle.putDouble(key, value)
            else -> bundle.putString(key, value.toString())
        }
    }
    analytics.logEvent(name, bundle)
}

internal actual fun setAnalyticsCollection(appContext: AppContext, enabled: Boolean) {
    firebaseAnalytics(appContext)?.setAnalyticsCollectionEnabled(enabled)
}

private fun firebaseAnalytics(appContext: AppContext): FirebaseAnalytics? {
    val context = appContext.applicationContext
    // No google-services.json means no default FirebaseApp, and getInstance would throw.
    if (FirebaseApp.getApps(context).isEmpty()) return null
    return FirebaseAnalytics.getInstance(context)
}
