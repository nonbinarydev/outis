/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.consent

import dev.nonbinary.outis.core.AppContext

/**
 * Dumb per-platform string persistence — the [ConsentManager] owns the encoding. Kept minimal on
 * purpose: one string in, one string out, so each platform actual is a few lines against its native
 * store (SharedPreferences, localStorage, NSUserDefaults).
 */
interface ConsentStore {
    fun loadRaw(): String?
    fun saveRaw(value: String)
}

/** Android needs the [AppContext] for SharedPreferences; the other platforms ignore it. */
expect fun consentStore(appContext: AppContext): ConsentStore
