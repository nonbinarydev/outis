/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.analytics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.nonbinary.outis.analytics.firebase.FirebaseUsageAnalytics
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.sample.catalogue.CatalogueItem

/**
 * Binds the Firebase/GA4 usage adapter to [player] while Marketing consent stands, and returns it for the
 * sample's own events: `null` while consent is withheld, so those are not logged either. Revoking consent
 * removes the adapter and turns collection off at once.
 */
@Composable
internal fun rememberUsageAnalytics(
    player: VideoPlayer,
    appContext: AppContext,
    granted: Boolean,
): FirebaseUsageAnalytics? {
    val usage = remember(player) { FirebaseUsageAnalytics(appContext) }
    DisposableEffect(usage, granted) {
        analyticsTag(granted)
        FirebaseUsageAnalytics.setCollectionEnabled(appContext, granted)
        if (granted) player.addComponent(usage)
        onDispose { player.removeComponent(usage) }
    }
    return usage.takeIf { granted }
}

/** Reports that the viewer chose [item], by id only: a custom stream's URL could identify them. */
internal fun FirebaseUsageAnalytics.logSelection(item: CatalogueItem) {
    logEvent("select_content", mapOf("content_type" to "video", "content_id" to item.id))
}

/**
 * Loads the web page's gtag.js the first time [enabled] holds, and silences it whenever it does not. The
 * apps have no tag: their Firebase comes from the host's own configuration file.
 */
internal expect fun analyticsTag(enabled: Boolean)
