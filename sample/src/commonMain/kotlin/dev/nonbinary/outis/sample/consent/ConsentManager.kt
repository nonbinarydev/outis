/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.consent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The persisted shape — a flat record, versioned by field presence, small enough to store as a string. */
@Serializable
private data class PersistedConsent(
    val decided: Boolean = false,
    val performance: Boolean = false,
    val marketing: Boolean = false,
)

/**
 * Holds the consent decision and persists it, so a choice survives reload.
 *
 * The single source of truth the UI binds to and the adapters gate on. Grants only ever change through
 * here, and every change is written straight to the [ConsentStore] — a revoke must not be lost across a
 * restart, or the app would keep collecting against a choice the user already reversed.
 */
class ConsentManager(private val store: ConsentStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ConsentState> = _state.asStateFlow()

    /** Grant everything and mark the choice made — the "Accept all" action. */
    fun acceptAll() = commit(
        ConsentState(
            decided = true,
            grants = ConsentCategory.entries.associateWith { true },
        ),
    )

    /** Deny every consent-gated category — the "Reject non-essential" action. */
    fun rejectNonEssential() = commit(
        ConsentState(
            decided = true,
            grants = ConsentCategory.entries.associateWith { !it.requiresConsent },
        ),
    )

    /**
     * Commit an explicit per-category choice — the Manage screen's Save. Batched rather than
     * per-toggle so the first-run dialog (shown while `!decided`) does not dismiss itself the instant a
     * switch is flipped; it edits local state and commits once here.
     */
    fun save(grants: Map<ConsentCategory, Boolean>) = commit(ConsentState(decided = true, grants = grants))

    private fun commit(state: ConsentState) {
        _state.value = state
        store.saveRaw(
            json.encodeToString(
                PersistedConsent(
                    decided = state.decided,
                    performance = state.isGranted(ConsentCategory.PERFORMANCE),
                    marketing = state.isGranted(ConsentCategory.MARKETING),
                ),
            ),
        )
    }

    private fun load(): ConsentState {
        val raw = store.loadRaw() ?: return ConsentState.UNDECIDED
        val p = runCatching { json.decodeFromString<PersistedConsent>(raw) }.getOrNull()
            ?: return ConsentState.UNDECIDED
        return ConsentState(
            decided = p.decided,
            grants = mapOf(
                ConsentCategory.PERFORMANCE to p.performance,
                ConsentCategory.MARKETING to p.marketing,
            ),
        )
    }
}
