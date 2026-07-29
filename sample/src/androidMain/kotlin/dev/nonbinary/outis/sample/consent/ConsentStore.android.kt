/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.consent

import android.content.Context
import androidx.core.content.edit
import dev.nonbinary.outis.core.AppContext

private const val PREFS = "outis-consent"
private const val KEY = "state"

actual fun consentStore(appContext: AppContext): ConsentStore = object : ConsentStore {
    private val prefs = appContext.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    override fun loadRaw(): String? = prefs.getString(KEY, null)
    override fun saveRaw(value: String) = prefs.edit { putString(KEY, value) }
}
