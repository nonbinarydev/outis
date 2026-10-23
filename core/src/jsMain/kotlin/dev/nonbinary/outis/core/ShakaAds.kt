/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.ads.Ad
import dev.nonbinary.outis.core.ads.AdState
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLVideoElement

/**
 * Client-side ads (CSAI) through Shaka's ad manager, which wraps the IMA HTML5 SDK — `google.ima`, a
 * global the host page's `ima3.js` script provides. IMA draws its ad UI into [container], which [sync]
 * keeps over [video]; its ad lifecycle reaches the engine as [onAdState].
 */
internal class ShakaAds(
    private val video: HTMLVideoElement,
    private val onAdState: (AdState?) -> Unit,
) {
    private val container = (document.createElement("div") as HTMLElement).also { div ->
        with(div.style) {
            position = "fixed"
            margin = "0"
            zIndex = "2" // above the Compose canvas (z-index 1) during ads
            setProperty("overflow", "hidden")
            setProperty("pointer-events", "none") // inert until an ad break (sync flips it)
        }
        document.body?.appendChild(div)
    }

    /** The Player whose ad manager has our containers and listeners; a recreated Player needs them again. */
    private var wiredTo: shaka.Player? = null

    /**
     * Stitch client-side ads from [adTagUri] over the content [player] just loaded. Shaka rebuilds its IMA
     * client for each asset, so a later item only requests again.
     */
    fun request(player: shaka.Player, adTagUri: String) {
        val adManager = player.getAdManager()
        if (wiredTo !== player) {
            // Client-side and server-side containers; only client-side ads are requested here.
            adManager.setContainers(container, container)
            wire(adManager)
            wiredTo = player
        }
        val request = js("new google.ima.AdsRequest()")
        request.adTagUrl = adTagUri
        adManager.requestClientSideAds(request, null)
    }

    /**
     * Keep the container over the `<video>`, and interactive **only during an ad break**: IMA's skip and
     * click-through must be reachable above the Compose canvas (z-index 1), but outside ads the container
     * must be inert so the overlay receives touches. Cheap; runs on the engine's reconcile poll.
     */
    fun sync(inAdBreak: Boolean) {
        val s = container.style
        if (inAdBreak) {
            val r = video.getBoundingClientRect()
            s.position = "fixed"
            s.left = "${r.left}px"
            s.top = "${r.top}px"
            s.width = "${r.width}px"
            s.height = "${r.height}px"
            s.zIndex = "2" // re-assert in case the surface raised body children to z-index 1
            s.setProperty("pointer-events", "auto")
        } else {
            s.setProperty("pointer-events", "none")
        }
    }

    fun release() {
        container.parentNode?.removeChild(container)
    }

    private fun wire(adManager: dynamic) {
        adManager.addEventListener(shaka.ads.Utils.AD_CONTENT_PAUSE_REQUESTED) { _: dynamic ->
            onAdState(AdState(isInAdBreak = true))
        }
        adManager.addEventListener(shaka.ads.Utils.AD_STARTED) { e: dynamic -> onAdState(adStateFromShaka(e.ad)) }
        val clear = { _: dynamic -> onAdState(null) }
        adManager.addEventListener(shaka.ads.Utils.AD_CONTENT_RESUME_REQUESTED, clear)
        adManager.addEventListener(shaka.ads.Utils.ALL_ADS_COMPLETED, clear)
        adManager.addEventListener(shaka.ads.Utils.AD_ERROR, clear)
    }
}

/** Map a Shaka `shaka.extern.IAd` to [AdState]. */
private fun adStateFromShaka(ad: dynamic): AdState {
    if (ad == null) return AdState(isInAdBreak = true)
    val durationSec: Double = ad.getDuration()
    val remainingSec: Double = ad.getRemainingTime()
    val skippable: Boolean = ad.isSkippable()
    val title: String? = ad.getTitle()
    val adId: String? = ad.getAdId()
    val seqLen: Int = ad.getSequenceLength()
    val posInSeq: Int = ad.getPositionInSequence()
    val canSkipNow: Boolean = ad.canSkipNow()
    return AdState(
        isInAdBreak = true,
        currentAd = Ad(
            id = adId ?: "",
            durationMs = (durationSec * 1000).toLong(),
            title = title,
            skipOffsetMs = if (skippable) {
                val secs: Double = ad.getTimeUntilSkippable()
                (secs.coerceAtLeast(0.0) * 1000).toLong()
            } else {
                null
            },
        ),
        adIndexInBreak = (posInSeq - 1).coerceAtLeast(0),
        adCountInBreak = seqLen,
        adRemainingMs = (remainingSec.coerceAtLeast(0.0) * 1000).toLong(),
        canSkip = skippable && canSkipNow,
    )
}
