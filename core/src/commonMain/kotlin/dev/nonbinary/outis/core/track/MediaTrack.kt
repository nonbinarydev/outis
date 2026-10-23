/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core.track

/**
 * A selectable audio, text (subtitle/caption) or video track.
 *
 * [id] is an **opaque, engine-stable token** — never a platform handle (Media3 `TrackGroup`,
 * AVPlayer option, …). The UI uses it only to match selection against
 * [dev.nonbinary.outis.core.PlayerState.selectedAudioTrackId],
 * [dev.nonbinary.outis.core.PlayerState.selectedTextTrackId] or
 * [dev.nonbinary.outis.core.PlayerState.selectedVideoTrackId]; the engine resolves it back to the
 * native track internally. Ids are valid only for the currently-loaded item.
 */
data class MediaTrack(
    /**
     * Opaque, engine-stable token identifying this track — see the class documentation. **Only unique
     * within one [type] for the currently-loaded item**, so match on [id] *and* [type], never [id] alone.
     */
    val id: String,
    /** Which selection group this track belongs to; the engine routes selection by it. */
    val type: TrackType,
    /** Human-readable label if the manifest provides one. */
    val label: String? = null,
    /** BCP-47 language tag (e.g. `"en"`, `"pt-BR"`) if known. */
    val language: String? = null,
    /**
     * `true` when this is the track the engine is currently rendering. Never set it yourself to drive
     * selection — it is a **read-only snapshot** the engine re-emits after
     * `selectTrack`/`clearTextTrack` actually take effect (selection is not applied optimistically).
     * At most one track per [type] carries `true`; text tracks may have none, meaning captions are off.
     */
    val isSelected: Boolean = false,
    /** The track the manifest marks as default. */
    val isDefault: Boolean = false,
)

/** Which selection group a [MediaTrack] belongs to. */
enum class TrackType {
    /** An audio rendition — typically one per language or mix (stereo, 5.1, descriptive audio). */
    AUDIO,

    /** A subtitle or closed-caption rendition. Unlike [AUDIO], text can be switched off entirely. */
    TEXT,

    /**
     * An alternate video rendition — a remaster beside an original transfer — not a rung of the bitrate
     * ladder, which the engine adapts within on its own. Reported and selectable on Android and web; iOS
     * reports none yet.
     */
    VIDEO,
}
