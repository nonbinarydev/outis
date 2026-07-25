/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.consent

import dev.nonbinary.outis.core.AppContext
import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set

private const val KEY = "outis-consent"

actual fun consentStore(appContext: AppContext): ConsentStore = object : ConsentStore {
    override fun loadRaw(): String? = localStorage[KEY]
    override fun saveRaw(value: String) {
        localStorage[KEY] = value
    }
}
