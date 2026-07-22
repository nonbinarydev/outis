/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.ads

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fractions of an ad's duration at which each quartile beacon fires, in ascending order. Ascending
 * order matters: a viewer who scrubs forward should still see the earlier quartiles emitted first.
 */
private val QUARTILE_THRESHOLDS = listOf(
    0.25f to AdQuartile.FIRST,
    0.50f to AdQuartile.MIDPOINT,
    0.75f to AdQuartile.THIRD,
)

/**
 * Engine-agnostic **server-side-ad-insertion** controller — the reusable heart of the ad layer, a state
 * machine driven by the playhead. Feed it the player's position ([onPosition]) and route seeks through
 * [resolveSeek]; it derives [state] (current ad, countdown, cue-points) and emits [events]. It is pure
 * Kotlin and behaves identically on Android/iOS/Web — only the playhead and seek calls bind to each
 * engine. The cue-points themselves come from a provider source (MediaTailor/DAI tracking JSON, SCTE-35,
 * …); this controller just tracks whatever [breaks] it's given.
 *
 * **Not thread-safe** — drive [onPosition] and call [resolveSeek]/[skipCurrentBreak] from one thread
 * (e.g. the main dispatcher, as the sample does). Re-watching an as-yet-unwatched break (seeking back
 * into it) re-emits its start/quartile events; an analytics adapter that bills on them should dedupe by
 * ad id.
 *
 * Typical wiring (any platform):
 * ```
 * val ads = AdController(item.serverSideBreaks())
 * launch { player.state.collect { ads.onPosition(it.positionMs) } }
 * // chrome: disable the scrubber while ads.state.isInAdBreak; route seeks via ads.resolveSeek(from, to)
 * ```
 */
class AdController(breaks: List<AdBreak>) {
    private val breaks = breaks.sortedBy { it.startMs }.toMutableList()
    private val watched = mutableSetOf<String>()

    private val _state = MutableStateFlow(AdState(cuePoints = this.breaks.map { it.startMs }))

    /**
     * The derived ad state, recomputed on every [onPosition]. Safe to collect from the UI to drive the ad
     * countdown, the skip button and scrubber-locking. Outside a break this is a **reset** [AdState] that
     * still carries the current [AdState.cuePoints], so a timeline can always mark the ad positions.
     */
    val state: StateFlow<AdState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 32)

    /**
     * Fire-and-forget ad lifecycle signals (break/ad start and completion, quartiles, `AllAdsCompleted`)
     * for analytics and beaconing. Hot and **replay-free** with a 32-event buffer — events emitted before
     * you subscribe are lost, and emission is non-suspending (`tryEmit`), so a collector that can't keep
     * up will drop events rather than stall the playhead. Subscribe before the first [onPosition].
     */
    val events: SharedFlow<AdEvent> = _events.asSharedFlow()

    private var activeBreak: AdBreak? = null
    private var activeAd: Ad? = null
    private val firedQuartiles = mutableSetOf<AdQuartile>()

    /** Drive from the player's position flow (idempotent per position; safe to call on every update). */
    fun onPosition(positionMs: Long) {
        val active = breaks.firstOrNull { it.id !in watched && positionMs >= it.startMs && positionMs < it.endMs }
        if (active == null) {
            finalizePassedBreaks(positionMs)
            if (_state.value.isInAdBreak) _state.value = AdState(cuePoints = cuePoints())
            return
        }

        val offset = positionMs - active.startMs
        var acc = 0L
        var index = 0
        var current = active.ads.first()
        for ((i, ad) in active.ads.withIndex()) {
            if (offset < acc + ad.durationMs) {
                current = ad
                index = i
                break
            }
            acc += ad.durationMs
        }
        val adPos = (offset - acc).coerceAtLeast(0L)

        if (active.id != activeBreak?.id) {
            activeBreak = active
            activeAd = null
            _events.tryEmit(AdEvent.BreakStarted(active))
        }
        if (current.id != activeAd?.id) {
            activeAd?.let { _events.tryEmit(AdEvent.AdCompleted(it)) }
            activeAd = current
            firedQuartiles.clear()
            _events.tryEmit(AdEvent.AdStarted(current, index, active.ads.size))
        }
        emitQuartiles(current, adPos)

        _state.value = AdState(
            isInAdBreak = true,
            currentBreak = active,
            currentAd = current,
            adIndexInBreak = index,
            adCountInBreak = active.ads.size,
            adPositionMs = adPos,
            adRemainingMs = (current.durationMs - adPos).coerceAtLeast(0L),
            canSkip = current.skipOffsetMs?.let { adPos >= it } ?: false,
            cuePoints = cuePoints(),
        )
    }

    /**
     * Clamp a forward seek so it can't skip an unwatched break: if [targetMs] jumps over a break that
     * hasn't been watched yet, snap to that break's start instead (the user sees the ad, then seeks on).
     * A forward seek made from *inside* an unwatched break is pinned to [fromMs] (no skipping the current
     * ad). Backward seeks, and seeks that don't cross an unwatched break, pass through unchanged.
     */
    fun resolveSeek(fromMs: Long, targetMs: Long): Long {
        if (targetMs <= fromMs) return targetMs
        // Already inside an unwatched break → can't seek forward out of (i.e. skip) the current ad.
        if (breaks.any { it.id !in watched && fromMs >= it.startMs && fromMs < it.endMs }) return fromMs
        // A forward seek that crosses an unwatched break snaps to that break's start.
        val pending = breaks.firstOrNull { it.id !in watched && it.startMs in (fromMs + 1)..targetMs }
        return pending?.startMs ?: targetMs
    }

    /**
     * Skip the current break — meaningful only when [AdState.canSkip]. Marks it watched, clears ad state,
     * and returns the position to seek the player to (the break's end), or `null` if not in a break.
     */
    fun skipCurrentBreak(): Long? {
        val brk = activeBreak ?: return null
        activeAd?.let { _events.tryEmit(AdEvent.AdCompleted(it)) }
        watched += brk.id
        activeBreak = null
        activeAd = null
        firedQuartiles.clear()
        _state.value = AdState(cuePoints = cuePoints())
        _events.tryEmit(AdEvent.BreakCompleted(brk))
        if (breaks.all { it.id in watched }) _events.tryEmit(AdEvent.AllAdsCompleted)
        return brk.endMs
    }

    /**
     * Add a break discovered at runtime — for **live** cue sources (SCTE-35 markers, DAI events) that
     * surface avails during playback rather than up front. Ignored if a break with the same id is already
     * known. The position loop picks it up on the next [onPosition].
     */
    fun addBreak(adBreak: AdBreak) {
        if (breaks.any { it.id == adBreak.id }) return
        breaks.add(adBreak)
        breaks.sortBy { it.startMs }
        _state.value = _state.value.copy(cuePoints = cuePoints())
    }

    /** Mark every break at or before [positionMs] as already watched (e.g. resuming past them). */
    fun markWatchedUpTo(positionMs: Long) {
        breaks.forEach { if (it.endMs <= positionMs) watched += it.id }
    }

    /**
     * Call when playback reaches the end. Finalizes any still-unwatched break — e.g. a post-roll the last
     * position poll never reported past — so its completion (and `AllAdsCompleted`) events still fire.
     */
    fun onEnded() {
        finalizePassedBreaks(Long.MAX_VALUE)
        if (_state.value.isInAdBreak) _state.value = AdState(cuePoints = cuePoints())
    }

    /**
     * Mark every unwatched break the playhead has fully passed (`positionMs >= endMs`) as watched and
     * emit its completion — covers both playing through a break and a coarse poll/seek that jumped over
     * one, so a later [resolveSeek] won't snap back into a break already behind the viewer and completion
     * events still fire.
     */
    private fun finalizePassedBreaks(positionMs: Long) {
        // distinctBy keeps a duplicated break id from completing twice, which the previous
        // check-then-mark loop got for free.
        val passed = breaks.filter { it.id !in watched && positionMs >= it.endMs }.distinctBy { it.id }
        passed.forEach { brk ->
            if (brk.id == activeBreak?.id) activeAd?.let { _events.tryEmit(AdEvent.AdCompleted(it)) }
            watched += brk.id
            _events.tryEmit(AdEvent.BreakCompleted(brk))
        }
        if (passed.isNotEmpty() && breaks.all { it.id in watched }) _events.tryEmit(AdEvent.AllAdsCompleted)
        activeBreak = null
        activeAd = null
        firedQuartiles.clear()
    }

    private fun emitQuartiles(ad: Ad, adPos: Long) {
        val frac = if (ad.durationMs > 0) adPos.toFloat() / ad.durationMs else 0f
        for ((threshold, quartile) in QUARTILE_THRESHOLDS) {
            if (frac >= threshold && firedQuartiles.add(quartile)) {
                _events.tryEmit(AdEvent.Quartile(ad, quartile))
            }
        }
    }

    private fun cuePoints() = breaks.map { it.startMs }
}
