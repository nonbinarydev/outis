/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import platform.Foundation.NSURL

// Swift sees neither Kotlin's default arguments nor its flows. These are the calls the Swift package's
// `Outis` module wraps; each one must keep working when a config or model field is added, so they build
// through Kotlin's defaults rather than passing every field.

/** A player with the default [PlayerConfig]. Call on the main thread. */
@ExperimentalPlayerApi
public fun makeVideoPlayer(): VideoPlayer = VideoPlayer(AppContext(), PlayerConfig())

/**
 * A player that samples its position every [positionPollIntervalMs] instead of the default 250 ms.
 * Call on the main thread.
 */
@ExperimentalPlayerApi
public fun makeVideoPlayer(positionPollIntervalMs: Long): VideoPlayer =
    VideoPlayer(AppContext(), PlayerConfig(positionPollIntervalMs = positionPollIntervalMs))

/**
 * A [MediaItem] for [url] with every other field at its default. A file URL becomes a
 * [MediaSource.LocalFile] of its decoded path; anything else a [MediaSource.Url].
 *
 * In a sandboxed app the file must stay reachable while it plays: a file from an open panel is, for
 * the rest of the launch, and a resolved security-scoped bookmark is between the app's own
 * `startAccessingSecurityScopedResource()` and `stopAccessingSecurityScopedResource()`.
 */
@ExperimentalPlayerApi
public fun mediaItemOf(url: NSURL): MediaItem {
    val path = url.path
    val source =
        if (url.isFileURL() && path != null) {
            MediaSource.LocalFile(path)
        } else {
            MediaSource.Url(url.absoluteString.orEmpty())
        }
    return MediaItem(source)
}

/** An observation started by [observeState] or [observeEvents]. */
@ExperimentalPlayerApi
public class PlayerObservation internal constructor(
    private val job: Job,
) {
    /** Stops the callbacks. Safe to call more than once. */
    public fun cancel() {
        job.cancel()
    }
}

/**
 * Calls [onEach] on the main thread with the current [PlayerState], then with each new one, until the
 * returned observation is cancelled. States are conflated: a slow callback sees the latest, not every one.
 */
@ExperimentalPlayerApi
public fun VideoPlayer.observeState(onEach: (PlayerState) -> Unit): PlayerObservation = observe(state, onEach)

/** Calls [onEach] on the main thread with every [PlayerEvent] from now on, until the observation is cancelled. */
@ExperimentalPlayerApi
public fun VideoPlayer.observeEvents(onEach: (PlayerEvent) -> Unit): PlayerObservation = observe(events, onEach)

private fun <T> observe(flow: Flow<T>, onEach: (T) -> Unit): PlayerObservation =
    PlayerObservation(CoroutineScope(Dispatchers.Main).launch { flow.collect { onEach(it) } })
