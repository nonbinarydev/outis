/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.consent

import dev.nonbinary.outis.core.AppContext
import platform.Foundation.NSUserDefaults

private const val KEY = "outis-consent"

actual fun consentStore(appContext: AppContext): ConsentStore = object : ConsentStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override fun loadRaw(): String? = defaults.stringForKey(KEY)
    override fun saveRaw(value: String) = defaults.setObject(value, KEY)
}
