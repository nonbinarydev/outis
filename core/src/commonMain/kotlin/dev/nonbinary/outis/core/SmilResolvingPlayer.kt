/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.net.HttpFetch
import dev.nonbinary.outis.core.net.httpGet
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.SmilOutcome
import dev.nonbinary.outis.core.source.SmilSelector
import dev.nonbinary.outis.core.source.resolveSmil
import dev.nonbinary.outis.core.source.smilUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Resolves SMIL sources before [engine] sees them (ADR-0007), and passes everything else straight through.
 *
 * A SMIL item stops the current one and is fetched on [dispatcher]. Until it resolves, [play], [pause] and
 * [seekTo] set the play intent and start position it will load with instead of reaching the engine.
 * Resolution finishes on [dispatcher] — the main thread, where every engine applies a call inline — so an
 * engine call made from another thread after the pending load is claimed always lands after it.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SmilResolvingPlayer(
    val engine: PlatformEngine,
    private val selector: SmilSelector,
    private val timeoutMs: Int,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val fetch: HttpFetch = ::httpGet,
) : VideoPlayer by engine {
    private data class Pending(val job: Job, val autoPlay: Boolean, val startPositionMs: Long?)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val pending = AtomicReference<Pending?>(null)

    override fun setMediaItem(item: MediaItem, autoPlay: Boolean) {
        val smilUrl = item.smilUrl()
        if (smilUrl == null) {
            pending.exchange(null)?.job?.cancel()
            engine.setMediaItem(item, autoPlay)
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) { resolve(item, smilUrl) }
        pending.exchange(Pending(job, autoPlay, item.startPositionMs))?.job?.cancel()
        job.start()
    }

    override fun play() {
        if (!retarget { it.copy(autoPlay = true) }) engine.play()
    }

    override fun pause() {
        if (!retarget { it.copy(autoPlay = false) }) engine.pause()
    }

    override fun seekTo(positionMs: Long) {
        if (!retarget { it.copy(startPositionMs = positionMs) }) engine.seekTo(positionMs)
    }

    override fun stop() {
        pending.exchange(null)?.job?.cancel()
        engine.stop()
    }

    override fun release() {
        pending.exchange(null)?.job?.cancel()
        scope.cancel()
        engine.release()
    }

    private suspend fun resolve(item: MediaItem, smilUrl: String) {
        val job = currentCoroutineContext().job
        if (pending.load()?.job !== job) return
        engine.stop()
        val outcome = resolveSmil(item, smilUrl, selector, timeoutMs, fetch)
        val load = claim(job) ?: return
        when (outcome) {
            is SmilOutcome.Resolved ->
                engine.setMediaItem(outcome.item.copy(startPositionMs = load.startPositionMs), load.autoPlay)
            is SmilOutcome.Failed -> engine.failLoad(item, outcome.error)
        }
    }

    /** Apply [change] to the pending load; `false` when nothing is pending. */
    private inline fun retarget(change: (Pending) -> Pending): Boolean {
        while (true) {
            val current = pending.load() ?: return false
            if (pending.compareAndSet(current, change(current))) return true
        }
    }

    /** Take the pending load if it is still [job]'s, leaving nothing pending; `null` once superseded. */
    private fun claim(job: Job): Pending? {
        while (true) {
            val current = pending.load()
            if (current?.job !== job) return null
            if (pending.compareAndSet(current, null)) return current
        }
    }
}
