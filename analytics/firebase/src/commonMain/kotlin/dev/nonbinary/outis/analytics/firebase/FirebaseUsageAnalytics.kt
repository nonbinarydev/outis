/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.analytics.firebase

import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.plugin.PlayerHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach

/**
 * Usage analytics for an Outis player — what viewers watch — reported to Google Analytics 4 through
 * Firebase on Android and iOS, and through gtag.js on the web.
 *
 * Attach it as a [PlayerComponent]:
 * ```
 * val usage = FirebaseUsageAnalytics(appContext)
 * player.addComponent(usage)
 * ```
 *
 * Usage, not quality of service: the Mux adapter measures how well a video played, this records that it
 * played. From the player it reports three events, named as GA4's own video events where one exists:
 * `video_start` when an item first plays (again after a replay, not after a pause), `video_complete` at
 * the end, and `video_error` on a fatal error. Each carries the item's
 * [dev.nonbinary.outis.core.analytics.PlaybackMetadata]: `video_title`, `video_id`, `video_series`,
 * `video_duration` and `video_current_time` in seconds, `stream_type`, and `video_provider` = `outis`.
 * What the viewer chose is the app's to report, through [logEvent].
 *
 * The app sets Firebase up: `google-services.json` on Android, `GoogleService-Info.plist` and
 * `FirebaseApp.configure()` on iOS, and on the web the gtag.js snippet with its measurement id. Until it
 * has, events are dropped, so playback never depends on analytics. Consent is the app's too: attach this
 * once the viewer agrees to usage analytics, and use [setCollectionEnabled] to apply their choice to
 * Firebase as a whole.
 */
public class FirebaseUsageAnalytics(private val appContext: AppContext) : PlayerComponent {
    private var reporting: Job? = null

    /** Starts reporting [host]'s usage events. */
    override fun attach(host: PlayerHost) {
        val usage = UsageTracker()
        reporting = host.events
            .mapNotNull { usage.eventFor(it, host.state.value) }
            .onEach { logEvent(it.name, it.params) }
            .launchIn(host.scope)
    }

    /** Stops reporting. Events already handed to Firebase or gtag.js are still sent. */
    override fun detach() {
        reporting?.cancel()
        reporting = null
    }

    /**
     * Reports an event the app raises, such as a title being chosen. Values may be `String`, `Long`,
     * `Int` or `Double`; anything else is sent as its `toString()`. GA4 drops names and values past its
     * limits, so names are reduced to letters, digits and underscores, at most 40 characters, and string
     * values to 100 characters.
     */
    public fun logEvent(name: String, params: Map<String, Any> = emptyMap()) {
        logToAnalytics(appContext, ga4Name(name), ga4Params(params))
    }

    public companion object {
        /**
         * Turns Google Analytics collection on or off for the whole app, as the viewer's consent stands.
         * On Android and iOS this is Firebase's `setAnalyticsCollectionEnabled`: it covers Firebase's
         * automatic events as well as this adapter's, and Firebase keeps it across launches, so an app
         * that must not collect before consent also sets Firebase's off-by-default flag in its manifest
         * or `Info.plist`. On the web it sets Consent Mode's `analytics_storage`, which governs gtag.js's
         * cookies rather than whether it sends: to send nothing before consent, load gtag.js only once it
         * is given.
         */
        public fun setCollectionEnabled(appContext: AppContext, enabled: Boolean) {
            setAnalyticsCollection(appContext, enabled)
        }
    }
}

/** Hands [name] and [params] to the platform's analytics SDK; does nothing when the app has not set it up. */
internal expect fun logToAnalytics(appContext: AppContext, name: String, params: Map<String, Any>)

/** The platform's analytics-collection switch; does nothing when the app has not set analytics up. */
internal expect fun setAnalyticsCollection(appContext: AppContext, enabled: Boolean)
