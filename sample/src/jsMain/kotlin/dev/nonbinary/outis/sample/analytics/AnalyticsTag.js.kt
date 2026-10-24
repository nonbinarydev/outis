/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.analytics

import dev.nonbinary.outis.sample.generated.SampleConfig
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLScriptElement

private var tagLoaded = false

/**
 * Nothing reaches Google before consent, because gtag.js is not on the page until then. A loaded script
 * cannot be unloaded, so a revoke sets `ga-disable-<id>`, which stops it sending.
 */
internal actual fun analyticsTag(enabled: Boolean) {
    val id = SampleConfig.GA4_MEASUREMENT_ID
    if (id.isEmpty()) return
    val page = window.asDynamic()
    page["ga-disable-$id"] = !enabled
    if (!enabled || tagLoaded) return
    tagLoaded = true
    if (page.dataLayer == undefined) page.dataLayer = js("[]")
    // gtag.js reads each command as an `arguments` object, so this must push `arguments`, not an array.
    page.gtag = js("(function () { window.dataLayer.push(arguments); })")
    page.gtag("js", js("new Date()"))
    page.gtag("config", id)
    val script = document.createElement("script") as HTMLScriptElement
    script.async = true
    script.src = "https://www.googletagmanager.com/gtag/js?id=$id"
    document.head?.appendChild(script)
}
