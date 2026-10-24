/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import kotlinx.browser.window
import kotlin.js.Promise

/** The browser's EME key systems: which one Shaka is given for a scheme, and whether the browser grants it. */
internal object KeySystems {
    private val probes = mutableMapOf<String, Promise<Boolean>>()

    /** The EME key system for [scheme], as Shaka is configured with it. */
    fun of(scheme: DrmScheme): String = when (scheme) {
        DrmScheme.WIDEVINE -> "com.widevine.alpha"
        DrmScheme.PLAYREADY -> "com.microsoft.playready"
        DrmScheme.FAIRPLAY -> "com.apple.fps"
        DrmScheme.CLEARKEY -> "org.w3.clearkey"
    }

    /**
     * Whether the browser grants [scheme]'s key system, with video [robustness] when given. Asked once per
     * pair and kept; the promise never rejects.
     */
    fun supports(scheme: DrmScheme, robustness: String = ""): Promise<Boolean> =
        probes.getOrPut("${of(scheme)}|$robustness") { probe(of(scheme), robustness) }

    private fun probe(keySystem: String, robustness: String): Promise<Boolean> {
        val navigator: dynamic = window.navigator
        // EME exists only in a secure context (https, or localhost).
        if (navigator.requestMediaKeySystemAccess == undefined) return Promise.resolve(false)
        val configurations = arrayOf(configuration(robustness))
        val request: Promise<Any?> = navigator.requestMediaKeySystemAccess(keySystem, configurations)
        return request.then({ true }, { false })
    }

    // Any one codec granted is enough: headless Chromium, for one, has VP9 but no H.264.
    private fun configuration(robustness: String): dynamic {
        val config: dynamic = js("({})")
        config.initDataTypes = arrayOf("cenc", "sinf", "skd", "keyids", "webm")
        config.videoCapabilities = arrayOf(
            "video/mp4; codecs=\"avc1.42E01E\"",
            "video/mp4; codecs=\"vp09.00.10.08\"",
            "video/webm; codecs=\"vp9\"",
        ).map { contentType ->
            val capability: dynamic = js("({})")
            capability.contentType = contentType
            capability.robustness = robustness
            capability
        }.toTypedArray()
        return config
    }
}
