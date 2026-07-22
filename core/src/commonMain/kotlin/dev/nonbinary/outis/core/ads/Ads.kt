/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.ads

/**
 * Ad-insertion config for a [dev.nonbinary.outis.core.source.MediaItem]. Server-side today (the
 * KMP-friendly path: the player just plays a normally-stitched stream and the SDK tracks cue-points over
 * it); a client-side (IMA/CSAI) variant — an ad-tag URL plus per-platform ad rendering — slots in beside
 * it later behind the same [AdState]/[AdEvent] contract.
 */
sealed interface AdConfig {
    /**
     * Server-side ad insertion. The stream is already stitched, so playback is unchanged; [breaks] are
     * the ad cue-points (from a MediaTailor/DAI tracking response, SCTE-35 markers, …) that the SDK
     * tracks over the timeline to drive ad UI, emit [AdEvent]s and block seeking through unwatched ads.
     */
    data class ServerSide(
        /**
         * Cue-points to track over the content timeline, in playback order. Need not be sorted —
         * [AdController] sorts by [AdBreak.startMs] on construction. Empty means SSAI is configured but no
         * avails are known yet; live breaks can still be added later via [AdController.addBreak].
         */
        val breaks: List<AdBreak>,
    ) : AdConfig

    /**
     * Client-side ad insertion (CSAI): the player stitches ads itself from a VAST/VMAP [adTagUri] via the
     * platform IMA SDK. IMA owns the ad lifecycle — fetch, playback, and the skip/countdown/click-through
     * UI it renders over the video — and the engine surfaces it through the same [AdState]/[AdEvent]
     * contract (so the chrome blocks seeking during ads identically). **Android today** (Media3 IMA
     * extension); iOS (IMA iOS) and Web (IMA HTML5) adapters follow.
     */
    data class ClientSide(
        /**
         * VAST/VMAP ad-tag URL handed straight to the platform IMA SDK. A VMAP tag carries its own
         * pre/mid/post-roll schedule; a bare VAST tag yields a single pre-roll. **Not** parsed by Outis —
         * macro substitution, redirects and wrapper resolution are all IMA's job.
         */
        val adTagUri: String,
    ) : AdConfig
}

/** One ad break (pod) beginning at [startMs] on the content timeline, made of one or more [ads]. */
data class AdBreak(
    /**
     * Stable identity of the break. **Must be unique within a session** — [AdController] keys its
     * watched-set and duplicate detection off it, so two breaks sharing an id means the second is silently
     * dropped and the first counts as watched for both.
     */
    val id: String,
    /**
     * Start of the break in ms on the *stitched* stream timeline — i.e. an ordinary player position, ads
     * included, not a content-only ("as-broadcast") offset.
     */
    val startMs: Long,
    /**
     * The pod's ads in playback order, laid out back-to-back from [startMs]. Order is load-bearing:
     * [AdController] maps the playhead to an ad by accumulating [Ad.durationMs] along this list.
     */
    val ads: List<Ad>,
) {
    /** Total break length — the sum of its ads. */
    val durationMs: Long get() = ads.sumOf { it.durationMs }

    /** Exclusive end of the break on the timeline. */
    val endMs: Long get() = startMs + durationMs
}

/** A single ad within an [AdBreak]. [skipOffsetMs] `null` => unskippable. */
data class Ad(
    /**
     * Creative identity (VAST ad id, MediaTailor `adId`, …). Used to detect ad transitions and as the
     * natural dedupe key for analytics adapters, since seeking back into an unwatched break re-emits its
     * [AdEvent.AdStarted]/[AdEvent.Quartile] events.
     */
    val id: String,
    /**
     * Creative length in ms. Drives the ad-to-playhead mapping, the countdown and the quartile fractions,
     * so it must match what is actually stitched into the stream. `0` disables quartile reporting for this
     * ad (the fraction is treated as `0`) rather than firing all three at once.
     */
    val durationMs: Long,
    /** Human-readable creative title for the ad UI, or `null` when the ad source supplied none. */
    val title: String? = null,
    /**
     * Offset into this ad, in ms, after which it may be skipped — surfaced as [AdState.canSkip] once the
     * playhead passes it. `null` means **unskippable**; `0` means skippable immediately.
     */
    val skipOffsetMs: Long? = null,
    /**
     * Landing page to open if the viewer taps the ad, or `null` when the creative has none (in which case
     * the chrome should render no click-through affordance). Opening it is the host app's job — the SDK
     * never navigates on its own.
     */
    val clickThroughUrl: String? = null,
)

/** Snapshot of ad playback, derived from the playhead by [AdController]. */
data class AdState(
    /**
     * `true` while the playhead sits inside an unwatched break. **This, not [currentAd] being non-`null`,
     * is the flag chrome should gate on** — it is the single condition for disabling the scrubber and
     * showing ad UI.
     */
    val isInAdBreak: Boolean = false,
    /** The break being played, or `null` when not in one. Non-`null` exactly when [isInAdBreak] is `true`. */
    val currentBreak: AdBreak? = null,
    /** The ad within [currentBreak] the playhead is on, or `null` when not in a break. */
    val currentAd: Ad? = null,
    /** 0-based index of [currentAd] within [currentBreak]. */
    val adIndexInBreak: Int = 0,
    /**
     * Number of ads in [currentBreak], for "ad 2 of 3" labels — remember [adIndexInBreak] is 0-based, so
     * display it as `adIndexInBreak + 1`. `0` when not in a break.
     */
    val adCountInBreak: Int = 0,
    /** Playhead offset into [currentAd]. */
    val adPositionMs: Long = 0,
    /** Time left in [currentAd]. */
    val adRemainingMs: Long = 0,
    /** True once the current ad's skip offset is reached. */
    val canSkip: Boolean = false,
    /** Break start positions (ms) — for rendering ad markers on the scrubber. */
    val cuePoints: List<Long> = emptyList(),
)

/** Ad lifecycle events — for analytics/QoS adapters (PAL, OMID, Mux) to observe uniformly per platform. */
sealed interface AdEvent {
    /**
     * The playhead entered a break. Always followed immediately by an [AdStarted] for its first ad.
     * Re-emitted if the viewer seeks back into a break that is still unwatched.
     */
    data class BreakStarted(
        /** The break just entered. */
        val adBreak: AdBreak,
    ) : AdEvent

    /** The playhead moved onto a new creative, either at break start or on the boundary between two ads. */
    data class AdStarted(
        /** The creative now playing. */
        val ad: Ad,
        /** 0-based position of [ad] within its break — display as `indexInBreak + 1`. */
        val indexInBreak: Int,
        /** Total ads in the break, for "ad 1 of 3" reporting. */
        val countInBreak: Int,
    ) : AdEvent

    /**
     * A 25%/50%/75% progress beacon for [ad]. Each quartile fires **at most once per ad play** — the set
     * resets when the ad changes, so re-watching a break fires them again.
     */
    data class Quartile(
        /** The creative the beacon belongs to. */
        val ad: Ad,
        /** Which threshold was crossed. There is no "complete" entry — use [AdCompleted] for 100%. */
        val quartile: AdQuartile,
    ) : AdEvent

    /**
     * [ad] finished — emitted when the playhead moves off it, the break is skipped, or the break is
     * finalised. **Not** a guarantee the creative was watched to the end: a seek or skip out of the break
     * also completes the ad it was on.
     */
    data class AdCompleted(
        /** The creative that just ended. */
        val ad: Ad,
    ) : AdEvent

    /**
     * The break is done and marked watched, so seeks may now pass through it freely. Fires whether it was
     * played through, skipped, or jumped over by a coarse position poll or seek.
     */
    data class BreakCompleted(
        /** The break that just completed. */
        val adBreak: AdBreak,
    ) : AdEvent

    /**
     * Every known break has been watched. On a live stream this is not final — [AdController.addBreak] can
     * introduce new avails afterwards, and the event fires again once those are watched too.
     */
    data object AllAdsCompleted : AdEvent
}

/** Progress thresholds reported by [AdEvent.Quartile]. */
enum class AdQuartile {
    /** 25% of [Ad.durationMs] elapsed. */
    FIRST,

    /** 50% elapsed. */
    MIDPOINT,

    /** 75% elapsed. Completion (100%) arrives as [AdEvent.AdCompleted], not as a quartile. */
    THIRD,
}
