/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.firebase

import dev.nonbinary.outis.core.AppContext
import kotlinx.browser.window

/**
 * Logs through the page's gtag.js, which reports to GA4. The page loads gtag.js and configures its
 * measurement id; until it has, there is no `gtag` and the event is dropped.
 */
internal actual fun logToAnalytics(appContext: AppContext, name: String, params: Map<String, Any>) {
    val gtag = gtag()
    if (gtag == undefined) return
    val jsParams: dynamic = js("({})")
    params.forEach { (key, value) ->
        // A Kotlin Long is not a JS number, so it would not reach GA4 as one.
        jsParams[key] = if (value is Long) value.toDouble() else value
    }
    gtag("event", name, jsParams)
}

internal actual fun setAnalyticsCollection(appContext: AppContext, enabled: Boolean) {
    val gtag = gtag()
    if (gtag == undefined) return
    val consent: dynamic = js("({})")
    consent["analytics_storage"] = if (enabled) "granted" else "denied"
    gtag("consent", "update", consent)
}

private fun gtag(): dynamic = window.asDynamic().gtag
