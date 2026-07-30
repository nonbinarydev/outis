/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.source

import dev.nonbinary.outis.core.ads.AdBreak
import dev.nonbinary.outis.core.ads.AdConfig
import dev.nonbinary.outis.core.analytics.PlaybackMetadata
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf

/**
 * What to play. Honours [source], [mimeType], [headers] and [drmConfig] (Widevine/PlayReady on
 * Android + Web; see [DrmConfig] for the iOS/FairPlay caveat).
 *
 * The "load-time" fields ([preferredAudioLanguage], [preferredTextLanguage], [captionsDefault],
 * [startPositionMs], [startMuted]) are applied as the item is prepared — before first frame — so the
 * right track / position / mute state is in place without a reactive flip after playback starts.
 */
data class MediaItem(
    /**
     * Where the bytes come from — a remote [MediaSource.Url] or an on-device [MediaSource.LocalFile].
     * The only required field; everything else has a sensible default. When [mimeType] is `null` the
     * container/streaming format is inferred from this (a [MediaSource.LocalFile] is always treated as
     * progressive, never adaptive).
     */
    val source: MediaSource,
    /** `null` => detect from the URL extension. Set explicitly for extension-less / signed URLs. */
    val mimeType: MimeType? = null,
    /**
     * Request headers applied when fetching the manifest/segments (auth tokens, etc.). Honoured
     * on Android, iOS and Web.
     */
    val headers: ImmutableMap<String, String> = persistentMapOf(),
    /** DRM parameters; `null` for clear content. */
    val drmConfig: DrmConfig? = null,
    /** Optional ceiling on adaptive video selection; `null` => unconstrained. */
    val videoConstraints: VideoConstraints? = null,
    /**
     * BCP-47 language tag to pre-select the audio track at load (before first frame), e.g. `"es"`.
     * `null` => engine/stream default. Honoured on Android, iOS and Web.
     */
    val preferredAudioLanguage: String? = null,
    /**
     * BCP-47 language tag to pre-select the subtitle/text track, e.g. `"en"`. Pairs with
     * [captionsDefault], which decides whether captions are shown at all. `null` => no preference.
     * Honoured on Android, iOS and Web.
     */
    val preferredTextLanguage: String? = null,
    /**
     * Initial caption visibility — [CaptionsDefaultMode.OFF] (default), [ON][CaptionsDefaultMode.ON],
     * or [FOLLOW_SYSTEM][CaptionsDefaultMode.FOLLOW_SYSTEM].
     */
    val captionsDefault: CaptionsDefaultMode = CaptionsDefaultMode.OFF,
    /**
     * Content-time offset (ms) to begin playback at — for resume / deep-link. `null` or `0` starts at
     * the beginning (or the live edge for live). Honoured on Android, iOS and Web.
     */
    val startPositionMs: Long? = null,
    /**
     * Begin playback muted regardless of the player's current volume — the autoplay-muted pattern for
     * feeds/reels. Not needed merely to autoplay on the web: the web engine already retries muted when a
     * browser blocks unmuted autoplay, so leave this off unless a muted start is the intended experience.
     * Only ever forces mute **on**; it never unmutes. Honoured on Android, iOS and Web.
     */
    val startMuted: Boolean = false,
    /**
     * Loop this item — restart from the beginning when it reaches the end, instead of ending. Honoured
     * on all three (Media3 repeat-one, AVPlayer seek-on-end, Web `<video>.loop`). Ignored for live.
     */
    val loop: Boolean = false,
    /**
     * Ad insertion for this item; `null` for ad-free content. For [AdConfig.ServerSide] the stream plays
     * unchanged (it's already stitched) and the SDK tracks the cue-points over it via an `AdController`.
     */
    val adConfig: AdConfig? = null,
    /**
     * Title / subtitle / artwork for display purposes only — it never affects playback. `null` when the
     * app supplies its own chrome or has nothing to show.
     */
    val metadata: MediaMetadata? = null,
    /**
     * Also extract per-chapter preview images (MP4 chapter **image** track, e.g. Subler-written JPEGs) into
     * [dev.nonbinary.outis.core.chapters.Chapter.thumbnail]. Off by default — it reads the image samples,
     * which is extra IO/memory. No effect when the file has no image track (Matroska, title-only MP4); titles
     * are unaffected either way. Honoured on Android and iOS (local files); ignored on Web (no chapters there).
     */
    val chapterThumbnails: Boolean = false,
    /**
     * WebVTT **chapters sidecar** URL. When set, its cues populate
     * [dev.nonbinary.outis.core.PlayerState.chapters] — the only chapter source that works for streamed
     * sources (HLS/DASH) and on Web, since it needs no local container. When null, the engine falls back
     * to chapters embedded in a local MP4/M4V/MKV (Android + iOS local files only). A `.vtt` here takes
     * precedence over any embedded chapters.
     */
    val chaptersUrl: String? = null,
    /**
     * Vendor-neutral QoS/analytics metadata, consumed by an analytics adapter (`outis-analytics-mux`).
     * `null` when nothing is wired — the field costs nothing and the SDK itself never reads it.
     * Distinct from [metadata], which is *display* metadata (title/artwork); this is what a QoS backend
     * slices sessions by.
     */
    val analytics: PlaybackMetadata? = null,
)

/** The SSAI ad breaks on this item, or empty if it has none. */
fun MediaItem.serverSideBreaks(): List<AdBreak> = (adConfig as? AdConfig.ServerSide)?.breaks ?: emptyList()

/** Initial caption/subtitle visibility for a [MediaItem]. */
enum class CaptionsDefaultMode {
    /** Captions hidden until the user selects a text track (the default). */
    OFF,

    /** Show captions at start — [MediaItem.preferredTextLanguage] if set, else the stream default. */
    ON,

    /**
     * Follow the OS caption setting (Android `CaptioningManager`, iOS Media Accessibility). Treated as
     * [OFF] on Web, which has no universal caption-accessibility signal.
     */
    FOLLOW_SYSTEM,
}

/**
 * Upper bounds on adaptive (HLS/DASH) video rendition selection — the player won't pick a rung above
 * these. Useful for data saving, or to steer ABR away from a specific high rung a platform decoder
 * struggles with. `null` fields are unbounded.
 *
 * Honoured on Android (Media3 `TrackSelectionParameters`), iOS (`AVPlayerItem.preferredMaximumResolution`
 * / `preferredPeakBitRate`) and Web (Shaka `restrictions`). Resolution caps need **both** [maxWidth] and
 * [maxHeight] (Media3/AVPlayer take a size, not a single dimension).
 */
data class VideoConstraints(
    /**
     * Maximum rendition width in **pixels**; `null` => unbounded. Only takes effect together with
     * [maxHeight] — a width on its own is ignored.
     */
    val maxWidth: Int? = null,
    /**
     * Maximum rendition height in **pixels**; `null` => unbounded. Only takes effect together with
     * [maxWidth] — e.g. `1280 x 720` to cap at 720p.
     */
    val maxHeight: Int? = null,
    /** Peak bitrate ceiling, **bits per second**. */
    val maxBitrateBps: Int? = null,
)

/** Optional display metadata for the overlay and OS media sessions. */
data class MediaMetadata(
    /** Primary display line (programme / film name). `null` => nothing to show, not an empty title. */
    val title: String? = null,
    /**
     * Secondary display line — series/episode, channel, description. Nothing to do with subtitle
     * **tracks**; for those see [MediaItem.preferredTextLanguage].
     */
    val subtitle: String? = null,
    /** URL of a poster/still to show alongside the title; `null` => no artwork. Never used as a video source. */
    val artworkUrl: String? = null,
)
