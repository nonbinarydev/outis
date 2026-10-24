/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

// The file is named for its contents; its one object is named for the JS global it declares.
@file:Suppress("MatchingDeclarationName")

package dev.nonbinary.outis.core

import org.w3c.dom.HTMLMediaElement

// Lower-case deliberately: this mirrors the `shaka` global exactly as the Shaka Player JS API
// documents it, so every member access below reads the same as the upstream docs. Parameters are
// Shaka's signatures, which Kotlin never reads.
@Suppress("ClassNaming", "UnusedParameter")
@JsModule("shaka-player")
@JsNonModule
internal external object shaka {
    object polyfill {
        fun installAll()
    }

    object net {
        object NetworkingEngine {
            object RequestType {
                val LICENSE: Int
            }
        }
    }

    // One member per Shaka method this engine calls, so its size is Shaka's API surface, not a design.
    @Suppress("TooManyFunctions")
    class Player {
        fun attach(mediaElement: HTMLMediaElement): kotlin.js.Promise<dynamic>
        fun load(uri: String, startTime: Double? = definedExternally): kotlin.js.Promise<dynamic>
        fun unload(): kotlin.js.Promise<dynamic>
        fun destroy(): kotlin.js.Promise<dynamic>
        fun addEventListener(type: String, listener: (event: dynamic) -> Unit)
        fun getVariantTracks(): Array<dynamic>
        fun getAudioTracks(): Array<dynamic>
        fun getTextTracks(): Array<dynamic>
        fun selectAudioTrack(track: dynamic) // keeps video ABR, unlike selectVariantTrack

        // Selecting a track also shows it, and `null` hides text: Shaka 5 has no separate visibility switch.
        fun selectTextTrack(track: dynamic)
        fun selectVariantTrack(track: dynamic, clearBuffer: Boolean = definedExternally)
        fun configure(config: dynamic)
        fun getNetworkingEngine(): dynamic

        // shaka.ads.AdManager — used dynamically (setContainers/requestClientSideAds/events)
        fun getAdManager(): dynamic
    }

    /** Client-side-ads (IMA HTML5) event-name constants. Shaka 5 keeps them on `Utils`, not `AdManager`. */
    object ads {
        object Utils {
            val AD_STARTED: String
            val AD_CONTENT_PAUSE_REQUESTED: String
            val AD_CONTENT_RESUME_REQUESTED: String
            val ALL_ADS_COMPLETED: String
            val AD_ERROR: String
        }
    }
}

private var shakaPolyfillsInstalled = false

/**
 * A new Shaka Player, with Shaka's polyfills installed first. They must precede the first Player: Shaka 5's
 * constructor already calls built-ins that current browsers lack (`Map.getOrInsertComputed`).
 */
internal fun newShakaPlayer(): shaka.Player {
    if (!shakaPolyfillsInstalled) {
        shaka.polyfill.installAll()
        shakaPolyfillsInstalled = true
    }
    return shaka.Player()
}
