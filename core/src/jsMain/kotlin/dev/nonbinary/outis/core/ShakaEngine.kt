/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.ads.AdConfig
import dev.nonbinary.outis.core.chapters.declaredChapters
import dev.nonbinary.outis.core.chapters.loadSidecarChapters
import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.plugin.PlayerHost
import dev.nonbinary.outis.core.source.CaptionsDefaultMode
import dev.nonbinary.outis.core.source.KeySystems
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.core.source.VideoConstraints
import dev.nonbinary.outis.core.source.unsupportedDrmError
import dev.nonbinary.outis.core.thumbnails.loadThumbnails
import dev.nonbinary.outis.core.track.MediaTrack
import dev.nonbinary.outis.core.track.TrackType
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.MediaError
import org.w3c.dom.events.EventListener
import kotlin.math.abs

/**
 * A Shaka `AudioPreference`. `channelCount = 2` keeps Shaka's own default of preferring stereo, which a
 * written preference list would otherwise replace. `spatialAudio` is left unset on purpose: any value,
 * `false` included, filters on it.
 */
private fun audioPreference(language: String): dynamic {
    val pref: dynamic = js("({ role: '', label: '', channelCount: 2, codec: '' })")
    pref.language = language
    return pref
}

/** A Shaka `TextPreference` for [language]. */
private fun textPreference(language: String): dynamic {
    val pref: dynamic = js("({ role: '', format: '', forced: false })")
    pref.language = language
    return pref
}

/**
 * Web [VideoPlayer] backed by Shaka Player over an HTML `<video>` element.
 *
 * Source routing: adaptive streams (HLS/DASH) go through Shaka (MSE); a plain progressive file
 * (mp4/webm) is played directly via `video.src` (Shaka does not support single-file progressive).
 * The `<video>` element drives transport (play/pause/seek/volume/rate) and most state (its media
 * events); Shaka's `buffering`/`error`/`variantchanged`/`textchanged`/`trackschanged` layer on top.
 *
 * JS is single-threaded, so there is no marshalling. The `<video>` is exposed via
 * [nativePlayerHandle] for a web surface to mount.
 */
internal class ShakaEngine(private val config: PlayerConfig) : PlatformEngine {

    private val _state = MutableStateFlow(PlayerState(volume = config.initialVolume))
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val video = (document.createElement("video") as HTMLVideoElement).also {
        it.controls = false
        it.setAttribute("playsinline", "true")
    }
    private var shakaPlayer = newShakaPlayer()
    private var shakaBroken = false // a failed Shaka load (e.g. DRM in Safari) wedges the Player; recreate before reuse

    private val ads = ShakaAds(video) { adState -> if (!released) _state.update { it.copy(adState = adState) } }
    private val _native = MutableStateFlow<Any?>(video)
    override val nativePlayerHandle: Any? get() = _native.value

    private val scope: CoroutineScope = MainScope()

    private var released = false
    private var playWhenReady = false
    private var usingShaka = false
    private var abrPinnedToRendition = false
    private var loadGeneration = 0
    private var lastPlaybackState = PlaybackState.IDLE
    private var wasBuffering = false
    private var wasPlaying = false
    private var firstFrameEmitted = false
    private var errorEmitted = false
    private var shakaBuffering = false
    private var intervalId = 0

    private val requests = ShakaRequests()
    private var pendingStartListener: EventListener? = null // one-shot loadedmetadata seek for native start position
    private var loadChain: dynamic = js("Promise.resolve()") // serializes loads so switches never overlap

    // Shaka's own track objects, handed back to its select* calls (dynamic can't be a type argument).
    private val textTrackById = mutableMapOf<String, Any?>()
    private val audioTrackById = mutableMapOf<String, Any?>()

    /** id -> Shaka video stream id, the join key onto a variant's `videoId`. */
    private val videoSelectionById = mutableMapOf<String, Int>()
    private val components = mutableListOf<PlayerComponent>()
    private val host = object : PlayerHost {
        override val state: StateFlow<PlayerState> get() = this@ShakaEngine.state
        override val events: SharedFlow<PlayerEvent> get() = this@ShakaEngine.events
        override val scope: CoroutineScope get() = this@ShakaEngine.scope
        override val nativePlayerHandle: StateFlow<Any?> get() = _native
    }

    private val videoListener = EventListener { event -> if (!released) onVideoEvent(event.type) }
    private val videoEvents = listOf(
        "loadedmetadata", "durationchange", "timeupdate", "play", "playing", "pause",
        "waiting", "canplay", "seeking", "seeked", "ended", "ratechange", "volumechange", "stalled", "error",
    )

    init {
        video.volume = config.initialVolume.toDouble()
        wireShaka(shakaPlayer)
        shakaPlayer.attach(video).catch { it }
        videoEvents.forEach { video.addEventListener(it, videoListener) }
        intervalId = window.setInterval({ if (!released) reconcile() }, config.positionPollIntervalMs.toInt())
        config.components.forEach(::addComponent)
        emit { p, t -> PlayerEvent.NativePlayerAttached(video, p, t) }
    }

    /** Configure [p] and register the request filters and Shaka event listeners on it (init + recreate). */
    private fun wireShaka(p: shaka.Player) {
        p.applyPlayerConfig(config)
        requests.install(p)
        p.addEventListener("buffering") { e ->
            if (!released) {
                shakaBuffering = e.buffering == true
                reconcile()
            }
        }
        p.addEventListener("error") { e -> if (!released) onShakaError(e.detail) }
        // List changes vs manual selection are different Shaka events — subscribe to all that re-emit.
        p.addEventListener("trackschanged") { _ -> if (!released) loadTracks() }
        p.addEventListener("variantchanged") { _ -> if (!released) loadTracks() }
        p.addEventListener("textchanged") { _ -> if (!released) loadTracks() }
    }

    /**
     * Recreate the Shaka Player after a failure wedged it. In Safari a failed DRM setup (no Widevine
     * CDM) leaves the Player unable to load anything — only the native `video.src` path still works —
     * and `unload()` won't recover it; a fresh Player does. The `<video>` element is reused, so
     * [nativePlayerHandle] is unchanged. Returns a Promise that settles once the fresh Player is ready.
     */
    private fun recreateShaka(): dynamic {
        shakaBroken = false
        val old = shakaPlayer
        val fresh = newShakaPlayer()
        wireShaka(fresh)
        shakaPlayer = fresh
        return old.destroy().catch { it }.then({ _ ->
            if (released) js("Promise.resolve()") else fresh.attach(video)
        })
    }

    // ---- transport ----

    override fun setMediaItem(item: MediaItem, autoPlay: Boolean) {
        if (released) return
        firstFrameEmitted = false
        errorEmitted = false
        wasBuffering = true // balances the BufferingStarted emitted below (so BufferingEnded fires on ready)
        wasPlaying = false
        shakaBuffering = false
        lastPlaybackState = PlaybackState.BUFFERING
        textTrackById.clear()
        audioTrackById.clear()
        videoSelectionById.clear()
        playWhenReady = autoPlay
        requests.mediaHeaders = item.headers
        clearPendingStartListener() // drop any un-fired native start-position listener from a prior item
        val generation = ++loadGeneration

        // Clear a stale media-element error synchronously, matching the synchronous state reset below.
        // Otherwise the reconcile poll, running in the async gap before the new load resets the
        // element, sees the old video.error with errorEmitted == false and re-raises it — flashing the
        // overlay back and re-hiding the recovered <video>.
        if (video.error != null) {
            video.removeAttribute("src")
            video.load()
        }

        val url = when (val source = item.source) {
            is MediaSource.Url -> source.url
            is MediaSource.LocalFile -> source.path
            // Unreachable: SmilResolvingPlayer swaps a SMIL source for its chosen rendition first.
            is MediaSource.Smil -> source.url
        }
        if (item.startMuted) video.muted = true
        video.loop = item.loop
        _state.update {
            it.copy(
                mediaItem = item, error = null, pendingSeekTargetMs = null,
                positionMs = 0, bufferedPositionMs = 0, durationMs = null,
                isLive = false, isSeekable = false, videoSize = null,
                playWhenReady = autoPlay, isMuted = item.startMuted || it.isMuted,
                playbackState = PlaybackState.BUFFERING,
                audioTracks = emptyList(), textTracks = emptyList(), videoTracks = emptyList(),
                selectedAudioTrackId = null, selectedTextTrackId = null, selectedVideoTrackId = null,
                chapters = item.declaredChapters,
                thumbnails = emptyList(),
            )
        }
        emit { p, t -> PlayerEvent.MediaItemTransition(item, p, t) }
        emit { p, t -> PlayerEvent.BufferingStarted(p, t) }

        // A WebVTT sidecar is the only chapter source Web reads (no local-container parsing here), and only
        // when the item declares none.
        item.chaptersUrl?.takeIf { item.chapters == null }?.let { sidecar ->
            scope.launch {
                val chapters = loadSidecarChapters(sidecar)
                if (!released && generation == loadGeneration && chapters.isNotEmpty()) {
                    _state.update { it.copy(chapters = chapters.toList()) }
                }
            }
        }
        // WebVTT trickplay-thumbnails sidecar.
        item.thumbnailsUrl?.let { sidecar ->
            scope.launch {
                val thumbs = loadThumbnails(sidecar)
                if (!released && generation == loadGeneration && thumbs.isNotEmpty()) {
                    _state.update { it.copy(thumbnails = thumbs.toList()) }
                }
            }
        }

        // Serialize loads so a fast burst of switches, and recovery after a failed load (e.g. a
        // Widevine stream in Safari, which has no Widevine CDM), stay deterministic. Each step first
        // unloads — resetting Shaka and the media element — then starts the new source. Superseded
        // steps (a newer setMediaItem already bumped the generation) skip without touching Shaka, so
        // only the last of a burst loads and no two loads ever overlap.
        val progressive = isProgressive(item, url)
        loadChain = loadChain.then(
            { _ -> startSource(generation, progressive, item, url) },
            { _ -> startSource(generation, progressive, item, url) },
        )
    }

    /**
     * One serialized load step: reset Shaka (and the media element) via [shaka.Player.unload], then
     * start [item] — unless a newer setMediaItem already superseded it. Always resolves (even on load
     * failure) so the chain keeps flowing and the next source can recover.
     */
    private fun startSource(generation: Int, progressive: Boolean, item: MediaItem, url: String): dynamic {
        if (released || generation != loadGeneration) return js("Promise.resolve()")
        if (usingShaka && !shakaBroken) {
            // Hide text while the old item's tracks still exist. A media element that last showed captions
            // switches on the first subtitle track it is given next, and Shaka's native text displayer
            // adopts that as the selection — the next item would start with captions it never asked for.
            // usingShaka goes false first so the old item's text events cannot reach state.
            usingShaka = false
            shakaPlayer.selectTextTrack(null)
        }
        // Reset Shaka before the new source: recreate it if a prior load wedged it (unload() can't
        // recover a Safari DRM failure), otherwise just unload to clear the previous content.
        val reset: dynamic = if (shakaBroken) recreateShaka() else shakaPlayer.unload()
        return reset.then(
            { _ -> onReset(generation, progressive, item, url) },
            { _ -> onReset(generation, progressive, item, url) }, // proceed even if reset rejected
        )
    }

    private fun onReset(generation: Int, progressive: Boolean, item: MediaItem, url: String): dynamic {
        if (released || generation != loadGeneration) return js("Promise.resolve()")
        if (progressive) {
            usingShaka = false
            startNativePlayback(url, item.startPositionMs)
            return js("Promise.resolve()")
        }
        // Ask the browser for the key system before Shaka does, so a scheme it lacks fails as a DRM error
        // that names the scheme, before anything is fetched.
        val drm = item.drmConfig ?: return loadWithShaka(generation, item, url)
        return KeySystems.supports(drm.scheme, preflightRobustness(drm)).then<dynamic> { supported ->
            when {
                released || generation != loadGeneration -> js("Promise.resolve()")
                supported -> loadWithShaka(generation, item, url)
                else -> {
                    emitErrorOnce(unsupportedDrmError(drm))
                    js("Promise.resolve()")
                }
            }
        }
    }

    private fun loadWithShaka(generation: Int, item: MediaItem, url: String): dynamic {
        usingShaka = true
        requests.configureDrm(shakaPlayer, item.drmConfig)
        shakaPlayer.configureRestrictions(item.videoConstraints)
        shakaPlayer.configureLanguagePreferences(item)
        // ABR config is player-level and outlives an item, so a previous pin (an alternate-rendition
        // manifest, or a rendition the viewer picked) would otherwise leave the next item unable to adapt.
        abrPinnedToRendition = false
        val abrReset: dynamic = js("({ abr: { enabled: true } })")
        shakaPlayer.configure(abrReset)
        // Start position (resume / deep-link): Shaka's load() takes a start time in seconds.
        return shakaPlayer.load(url, item.startPositionMs?.let { it / 1000.0 }).then<dynamic>(
            { _ ->
                if (!released && generation == loadGeneration) {
                    if (playWhenReady) playWithAutoplayFallback()
                    loadTracks()
                    applyCaptionsDefault(item) // after loadTracks so its textchanged re-read sees the visibility
                    reconcile()
                    // Client-side ads (CSAI) stitch over the Shaka (MSE) stream via the ad manager.
                    (item.adConfig as? AdConfig.ClientSide)?.let { ads.request(shakaPlayer, it.adTagUri) }
                }
                Unit
            },
            { error ->
                if (!released && generation == loadGeneration) emitErrorOnce(loadFailure(error))
                Unit
            },
        )
    }

    /** Native (non-Shaka) playback for progressive files; called once Shaka's unload has settled. */
    private fun startNativePlayback(url: String, startPositionMs: Long?) {
        video.src = url
        // The browser's <video> can't take custom request headers, so MediaItem.headers don't apply to
        // progressive (native) playback — only to Shaka-managed HLS/DASH. Resume position still works.
        clearPendingStartListener() // a fast switch may have left a prior listener that never fired
        val startSec = startPositionMs?.takeIf { it > 0 }?.let { it / 1000.0 }
        if (startSec != null) {
            val listener = EventListener {
                video.currentTime = startSec
                clearPendingStartListener()
            }
            pendingStartListener = listener
            video.addEventListener("loadedmetadata", listener)
        }
        video.load()
        if (playWhenReady) playWithAutoplayFallback()
    }

    /** Remove any pending one-shot native start-position listener so it can't fire against a later item. */
    private fun clearPendingStartListener() {
        pendingStartListener?.let { video.removeEventListener("loadedmetadata", it) }
        pendingStartListener = null
    }

    /**
     * Autoplay that keeps sound where the browser allows it. A programmatic `play()` that is neither
     * muted nor from a user gesture is rejected (`NotAllowedError`); the web-standard response is to mute
     * and retry, so a start plays with sound where permitted and mutes only where it must — the SDK
     * handles the browser policy so the app need not force every start muted. An already-muted start
     * (`MediaItem.startMuted`, or a user mute) never reaches the fallback. Only autoplay uses this;
     * user-driven [play] carries a gesture and cannot be blocked.
     */
    private fun playWithAutoplayFallback() {
        video.asDynamic().play().catch({ e: dynamic ->
            // Only an autoplay refusal is answered by muting. `play()` rejects for unrelated reasons too
            // -- most often AbortError, when a load() supersedes a play still settling -- and muting for
            // one of those leaves the item running silently with nothing to undo it.
            if ((e?.name as? String) == "NotAllowedError" && !video.muted) {
                video.muted = true
                _state.update { it.copy(isMuted = true) }
                video.asDynamic().play()
            }
            Unit
        })
    }

    override fun play() {
        if (released) return
        playWhenReady = true
        video.play()
        _state.update { it.copy(playWhenReady = true) }
        reconcile()
    }

    override fun pause() {
        if (released) return
        playWhenReady = false
        video.pause()
        _state.update { it.copy(playWhenReady = false) }
        reconcile()
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        _state.update { it.copy(positionMs = positionMs, pendingSeekTargetMs = positionMs) }
        emit { p, t -> PlayerEvent.SeekStarted(positionMs, p, t) }
        val targetSeconds = positionMs / 1000.0
        // A no-op seek (same position, or no media) fires no 'seeked' event — resolve it immediately.
        if (video.readyState.toInt() == 0 || abs(video.currentTime - targetSeconds) < 0.001) {
            _state.update { it.copy(pendingSeekTargetMs = null) }
            emit { p, t -> PlayerEvent.SeekCompleted(p, t) }
        } else {
            video.currentTime = targetSeconds
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        if (released) return
        video.playbackRate = speed.toDouble()
        _state.update { it.copy(playbackSpeed = speed) }
    }

    override fun setVolume(volume: Float) {
        if (released) return
        video.volume = volume.toDouble()
        _state.update { it.copy(volume = volume) }
    }

    override fun setMuted(muted: Boolean) {
        if (released) return
        video.muted = muted
        _state.update { it.copy(isMuted = muted) }
    }

    override fun stop() {
        if (released) return
        ++loadGeneration // a load still in the chain must not start after this
        clearPendingStartListener()
        video.pause()
        if (usingShaka) shakaPlayer.unload().catch { it } else video.removeAttribute("src")
        usingShaka = false
        playWhenReady = false
        wasPlaying = false
        wasBuffering = false
        shakaBuffering = false
        lastPlaybackState = PlaybackState.IDLE
        textTrackById.clear()
        audioTrackById.clear()
        videoSelectionById.clear()
        _state.update {
            it.copy(
                playbackState = PlaybackState.IDLE, isPlaying = false, playWhenReady = false,
                mediaItem = null, positionMs = 0, bufferedPositionMs = 0, durationMs = null,
                isLive = false, isSeekable = false, pendingSeekTargetMs = null, videoSize = null, error = null,
                audioTracks = emptyList(), textTracks = emptyList(), videoTracks = emptyList(),
                selectedAudioTrackId = null, selectedTextTrackId = null, selectedVideoTrackId = null,
                chapters = emptyList(), thumbnails = emptyList(),
            )
        }
    }

    override fun failLoad(item: MediaItem, error: PlayerError) {
        stop()
        if (released) return
        errorEmitted = true
        _state.update { it.copy(mediaItem = item, error = error) }
        emit { p, t -> PlayerEvent.MediaItemTransition(item, p, t) }
        emit { p, t -> PlayerEvent.FatalError(error, p, t) }
    }

    override fun release() {
        if (released) return
        released = true
        window.clearInterval(intervalId)
        videoEvents.forEach { video.removeEventListener(it, videoListener) }
        components.toList().forEach { it.detach() }
        components.clear()
        scope.cancel()
        video.pause()
        ads.release()
        shakaPlayer.destroy().catch { it }
        _native.value = null
    }

    override fun addComponent(component: PlayerComponent) {
        if (released) return
        components += component
        component.attach(host)
    }

    override fun removeComponent(component: PlayerComponent) {
        if (components.remove(component)) component.detach()
    }

    override fun selectTrack(track: MediaTrack) {
        if (released || !usingShaka) return
        when (track.type) {
            TrackType.AUDIO -> audioTrackById[track.id]?.let { shakaTrack ->
                shakaPlayer.selectAudioTrack(shakaTrack)
                // A language change can switch on forced subtitles; re-assert "off" if subtitles are off.
                if (_state.value.selectedTextTrackId == null) shakaPlayer.selectTextTrack(null)
                _state.update { st ->
                    st.copy(
                        audioTracks = st.audioTracks.map { it.copy(isSelected = it.id == track.id) },
                        selectedAudioTrackId = track.id,
                    )
                }
            }

            TrackType.TEXT -> textTrackById[track.id]?.let { shakaTrack ->
                shakaPlayer.selectTextTrack(shakaTrack)
                _state.update { st ->
                    st.copy(
                        textTracks = st.textTracks.map { it.copy(isSelected = it.id == track.id) },
                        selectedTextTrackId = track.id,
                    )
                }
            }

            TrackType.VIDEO -> videoSelectionById[track.id]?.let { selectRendition(track.id, it) }
        }
        // The variantchanged/textchanged events re-emit the authoritative lists; the above is optimistic.
        emit { p, t -> _state.value.tracksChanged(p, t) }
    }

    override fun clearTextTrack() {
        if (released) return
        if (usingShaka) shakaPlayer.selectTextTrack(null)
        _state.update { st ->
            st.copy(
                textTracks = st.textTracks.map { it.copy(isSelected = false) },
                selectedTextTrackId = null,
            )
        }
        emit { p, t -> _state.value.tracksChanged(p, t) }
    }

    /**
     * Initial caption visibility after load. FOLLOW_SYSTEM has no universal web signal, so it maps to OFF.
     * ON with no preferred language shows the manifest's primary text track, else its first.
     */
    private fun applyCaptionsDefault(item: MediaItem) {
        val tracks = shakaPlayer.getTextTracks()
        if (item.captionsDefault != CaptionsDefaultMode.ON) {
            // Off also hides forced subtitles Shaka may have picked for the audio language.
            if (tracks.any { it.active == true }) shakaPlayer.selectTextTrack(null)
            return
        }
        if (tracks.none { it.active == true }) {
            val pick: Any? = tracks.firstOrNull { it.primary == true } ?: tracks.firstOrNull()
            if (pick != null) shakaPlayer.selectTextTrack(pick)
        }
    }

    // ---- events + state ----

    private fun onVideoEvent(type: String) {
        when (type) {
            "seeked" -> {
                _state.update { it.copy(pendingSeekTargetMs = null) }
                reconcile()
                emit { p, t -> PlayerEvent.SeekCompleted(p, t) }
            }

            "error" -> if (!errorEmitted) onMediaElementError()

            else -> reconcile()
        }
    }

    // The web counterpart of AVPlayerEngine.reconcile: one pass over the <video> element and the
    // Shaka player to build a single coherent PlayerState snapshot. Suppressed for as long as that one
    // is (see its comment): the two stay line-for-line mirrors, so they are decomposed together.
    @Suppress("CyclomaticComplexMethod")
    private fun reconcile() {
        if (released) return
        ads.sync(inAdBreak = _state.value.adState?.isInAdBreak == true)
        if (_state.value.mediaItem == null) {
            // Stable IDLE: nothing loaded / stopped. Don't let the catch-all below report BUFFERING.
            return
        }
        if (_state.value.error != null) {
            // Errored state is stable until the next setMediaItem/stop clears it. Don't let the poll
            // flip playbackState back to BUFFERING or re-emit events after a FatalError (a Shaka/DRM
            // error leaves video.error null, so the guard below wouldn't catch it).
            return
        }
        if (video.error != null && !errorEmitted) {
            onMediaElementError()
            return
        }
        val ended = video.ended
        val readyState = video.readyState.toInt()
        if (readyState >= HAVE_FUTURE_DATA) shakaBuffering = false // backstop for a missed buffering==false
        val duration = video.duration
        val durationMs = if (duration.isNaN() || duration.isInfinite() || duration <= 0.0) {
            null
        } else {
            (duration * 1000).toLong()
        }
        val isLive = duration.isInfinite()
        // Stall is independent of play intent (matches the Android engine); the UI gates the spinner on intent.
        val buffering = readyState < HAVE_FUTURE_DATA || shakaBuffering
        val ready = readyState >= HAVE_CURRENT_DATA

        val playbackState = when {
            video.error != null -> PlaybackState.IDLE
            ended -> PlaybackState.ENDED
            buffering -> PlaybackState.BUFFERING
            ready -> PlaybackState.READY
            else -> PlaybackState.BUFFERING
        }
        val playing = !video.paused && !ended && readyState >= HAVE_FUTURE_DATA
        val positionMs = (video.currentTime * 1000).toLong()
        val bufferedMs = video.bufferedEndMs(positionMs)

        _state.update {
            it.copy(
                playbackState = playbackState,
                isPlaying = playing,
                positionMs = positionMs,
                bufferedPositionMs = bufferedMs,
                durationMs = durationMs,
                isLive = isLive,
                isSeekable = video.seekable.length > 0,
                videoRange = shakaPlayer.activeVideoRange(it.videoRange),
            )
        }

        if (ready && !firstFrameEmitted) {
            firstFrameEmitted = true
            emit { p, t -> PlayerEvent.FirstFrameRendered(p, t) }
        }
        if (buffering && !wasBuffering) emit { p, t -> PlayerEvent.BufferingStarted(p, t) }
        if (!buffering && wasBuffering) emit { p, t -> PlayerEvent.BufferingEnded(p, t) }
        wasBuffering = buffering
        if (playing != wasPlaying) {
            wasPlaying = playing
            emit { p, t -> PlayerEvent.IsPlayingChanged(playing, p, t) }
        }
        if (playbackState != lastPlaybackState) {
            if (playbackState == PlaybackState.ENDED) emit { p, t -> PlayerEvent.Ended(p, t) }
            emit { p, t -> PlayerEvent.PlaybackStateChanged(playbackState, p, t) }
            lastPlaybackState = playbackState
        }
    }

    /** Switch to rendition [streamId], keeping the audio already playing: a variant pins both together. */
    private fun selectRendition(trackId: String, streamId: Int) {
        val variants = shakaPlayer.getVariantTracks()
        val all = (0 until variants.size).map { variants[it] }
        val currentAudio = (all.firstOrNull { it.active == true }?.audioId as? Number)?.toInt()
        val candidates = all.filter { (it.videoId as? Number)?.toInt() == streamId }
        val chosen = candidates.firstOrNull { (it.audioId as? Number)?.toInt() == currentAudio }
            ?: candidates.firstOrNull()
            ?: return
        // An explicit choice has to stick: with ABR left on, Shaka is free to move back off it.
        shakaPlayer.disableAbr()
        shakaPlayer.selectVariantTrack(chosen, true)
        if (_state.value.selectedTextTrackId == null) shakaPlayer.selectTextTrack(null)
        _state.update { st ->
            st.copy(
                videoTracks = st.videoTracks.map { it.copy(isSelected = it.id == trackId) },
                selectedVideoTrackId = trackId,
            )
        }
    }

    /**
     * Alternate video renditions, keyed by each variant's video stream and named by its `videoLabel` (the
     * variant's own `label` describes the audio). Empty unless there is a real choice -- a plain bitrate
     * ladder is the engine's business, not the user's.
     */
    private fun readRenditions(variants: Array<dynamic>): List<MediaTrack> {
        // A JS number arrives as Double here -- `as Int` would throw for EVERY manifest.
        val activeVideoId = (variants.firstOrNull { it.active == true }?.videoId as? Number)?.toInt()
        val video = variants
            .distinctBy { (it.videoId as? Number)?.toInt() }
            .mapNotNull { renditionOf(it, activeVideoId) }
        // One rendition is no choice; two unlabelled ones are a ladder we should not expose.
        if (video.size < 2 || video.all { it.label == null }) {
            videoSelectionById.clear()
            return emptyList()
        }
        if (!abrPinnedToRendition) {
            // Labelled renditions are alternate *versions* of the programme, not quality rungs, so ABR
            // must not drift between them: a variant switch rebuilds the video buffer, and the playhead
            // can be left with no video where it stands. Pin whatever Shaka already chose rather than
            // selecting a variant, which would clear the buffer mid-start-up. Unlabelled ladders keep
            // ABR, so multiple resolutions of one version still adapt.
            abrPinnedToRendition = true
            shakaPlayer.disableAbr()
        }
        return video
    }

    private fun renditionOf(variant: dynamic, activeVideoId: Int?): MediaTrack? {
        val sid = (variant.videoId as? Number)?.toInt() ?: return null
        val roles = (variant.videoRoles as? Array<dynamic>)?.mapNotNull { it as? String }.orEmpty()
        val id = "VIDEO:$sid"
        videoSelectionById[id] = sid
        return MediaTrack(
            id = id,
            type = TrackType.VIDEO,
            label = (variant.videoLabel as? String) ?: roles.firstOrNull(),
            isSelected = sid == activeVideoId,
            isDefault = roles.contains("main"),
        )
    }

    private fun loadTracks() {
        if (released || !usingShaka) return
        textTrackById.clear()
        audioTrackById.clear()
        videoSelectionById.clear()

        // Shaka folds every quality of one audio stream into a single track, so these are already the
        // distinct choices. Same-language streams (stereo, 5.1, commentary) stay apart by label, role and
        // channel count; anything still colliding gets a suffix rather than being dropped.
        val audioList = shakaPlayer.getAudioTracks()
        val audio = mutableListOf<MediaTrack>()
        for (i in 0 until audioList.size) {
            val shakaTrack = audioList[i]
            val language = shakaTrack.language as? String ?: continue
            val label = shakaTrack.label as? String
            val roles = (shakaTrack.roles as? Array<dynamic>)?.joinToString(",").orEmpty()
            val key = "AUDIO:$language|${label.orEmpty()}|$roles|${shakaTrack.channelsCount ?: ""}"
            var id = key
            var n = 2
            while (id in audioTrackById) id = "$key#${n++}"
            audioTrackById[id] = shakaTrack
            audio += MediaTrack(
                id = id,
                type = TrackType.AUDIO,
                label = label,
                language = language,
                isSelected = shakaTrack.active == true,
            )
        }

        val video = readRenditions(shakaPlayer.getVariantTracks())

        val textList = shakaPlayer.getTextTracks()
        val text = mutableListOf<MediaTrack>()
        for (i in 0 until textList.size) {
            val tt = textList[i]
            val id = "TEXT:${tt.id}"
            textTrackById[id] = tt
            text += MediaTrack(
                id = id,
                type = TrackType.TEXT,
                label = tt.label as? String,
                language = tt.language as? String,
                isSelected = tt.active == true,
            )
        }

        _state.update {
            it.copy(
                audioTracks = audio.toList(),
                textTracks = text.toList(),
                videoTracks = video.toList(),
                selectedAudioTrackId = audio.firstOrNull { t -> t.isSelected }?.id,
                selectedTextTrackId = text.firstOrNull { t -> t.isSelected }?.id,
                selectedVideoTrackId = video.firstOrNull { t -> t.isSelected }?.id,
            )
        }
        emit { p, t -> _state.value.tracksChanged(p, t) }
    }

    private fun onShakaError(detail: dynamic) {
        emitErrorOnce(shakaPlayerError(detail))
    }

    private fun onMediaElementError() {
        // A real decode/network failure sets video.error. An 'error' event with no MediaError is a
        // transient artefact of a source reset mid-switch — not fatal, so ignore it.
        val error: MediaError = video.error ?: return
        emitErrorOnce(
            PlayerError(
                category = PlayerError.Category.SOURCE,
                code = error.code.toString(),
                message = "Media error (code ${error.code})",
            ),
        )
    }

    private fun emitErrorOnce(error: PlayerError) {
        if (errorEmitted) return
        errorEmitted = true
        // A failure that happened under Shaka (adaptive/DRM) can leave the Player wedged — flag it so
        // the next load recreates it instead of trying to reuse a dead instance.
        if (usingShaka) shakaBroken = true
        wasPlaying = false
        wasBuffering = false
        lastPlaybackState = PlaybackState.IDLE
        _state.update { it.copy(error = error, playbackState = PlaybackState.IDLE, isPlaying = false) }
        emit { p, t -> PlayerEvent.FatalError(error, p, t) }
    }

    private inline fun emit(make: (positionMs: Long, elapsedRealtimeMs: Long) -> PlayerEvent) {
        _events.tryEmit(make(_state.value.positionMs, monotonicMillis()))
    }

    private companion object {
        private const val HAVE_CURRENT_DATA = 2
        private const val HAVE_FUTURE_DATA = 3
    }
}

/** Adaptive (Shaka) vs progressive (native `video.src`) — Shaka can't play single-file mp4/webm. */
private fun isProgressive(item: MediaItem, url: String): Boolean = when (item.mimeType) {
    MimeType.MP4, MimeType.WEBM -> true

    MimeType.HLS, MimeType.DASH -> false

    null -> when {
        item.source is MediaSource.LocalFile -> true
        url.endsWith(".m3u8", ignoreCase = true) || url.endsWith(".mpd", ignoreCase = true) -> false
        url.endsWith(".mp4", ignoreCase = true) || url.endsWith(".webm", ignoreCase = true) -> true
        else -> false // default to Shaka for unknown/adaptive
    }
}

/** Monotonic clock in ms (browser performance.now). */
private fun monotonicMillis(): Long = (js("performance.now()") as Double).toLong()

/** A `shaka.util.Error`, classified by its category. */
private fun shakaPlayerError(error: dynamic): PlayerError {
    val category = when (error?.category) {
        1 -> PlayerError.Category.NETWORK
        3 -> PlayerError.Category.DECODER
        4 -> PlayerError.Category.SOURCE
        6 -> PlayerError.Category.DRM
        else -> PlayerError.Category.UNKNOWN
    }
    val code = (error?.code as? Int)?.toString()
    return PlayerError(category, code = code, message = "Shaka error ${code.orEmpty()}".trim(), nativeCause = error)
}

/** Why `load()` rejected: Shaka's own error when it gave one, else a source failure. */
private fun loadFailure(error: dynamic): PlayerError = if (error?.category == null) {
    PlayerError(PlayerError.Category.SOURCE, message = "Failed to load media", nativeCause = error)
} else {
    shakaPlayerError(error)
}

/** [config]'s retry, buffering, ABR seed and low-latency settings, as Shaka configuration. */
private fun shaka.Player.applyPlayerConfig(config: PlayerConfig) {
    // Bounded manifest/segment/DRM retry + timeout so a hung server can't keep a load() pending
    // forever (it would wedge the serialized load chain). 30s timeout default; PlayerConfig.retryConfig
    // overrides timeouts + attempts + back-off.
    val r = config.retryConfig

    // A fresh retryParameters object per section (manifest/streaming/drm) — Shaka merges by value so
    // a shared object works today, but separate objects avoid any in-place-mutation aliasing later.
    fun retryParams(): dynamic {
        val rp: dynamic = js("({})")
        rp.timeout = r?.readTimeoutMs ?: 30000
        rp.connectionTimeout = r?.connectTimeoutMs ?: 30000
        if (r != null) {
            rp.maxAttempts = r.maxRetries + 1 // Shaka counts the first attempt, so attempts = retries + 1
            rp.baseDelay = r.baseDelayMs
            rp.backoffFactor = r.backoffFactor
        }
        return rp
    }
    val retryCfg: dynamic = js("({ manifest: {}, streaming: {}, drm: {} })")
    retryCfg.manifest.retryParameters = retryParams()
    retryCfg.streaming.retryParameters = retryParams()
    retryCfg.drm.retryParameters = retryParams()
    configure(retryCfg)
    // Buffer/load-control tuning (ms → seconds) + ABR cold-start seed. configure() deep-merges, so
    // these layer onto the retry config above without clobbering it.
    config.bufferConfig?.let { b ->
        val streaming: dynamic = js("({})")
        streaming.bufferingGoal = b.maxBufferMs / 1000.0
        streaming.rebufferingGoal = b.bufferForPlaybackMs / 1000.0
        streaming.bufferBehind = b.backBufferMs / 1000.0
        val cfg: dynamic = js("({})")
        cfg.streaming = streaming
        configure(cfg)
    }
    config.initialBitrateBps?.let { bps ->
        val abr: dynamic = js("({})")
        abr.defaultBandwidthEstimate = bps
        val cfg: dynamic = js("({})")
        cfg.abr = abr
        configure(cfg)
    }
    if (config.liveConfig?.lowLatencyMode == true) {
        val streaming: dynamic = js("({})")
        streaming.lowLatencyMode = true
        val cfg: dynamic = js("({})")
        cfg.streaming = streaming
        configure(cfg)
    }
}

/** Colour range of Shaka's active variant — DV from the codec, HDR from Shaka's `hdr` field ('PQ'/'HLG'). */
private fun shaka.Player.activeVideoRange(fallback: VideoRange): VideoRange {
    val active = getVariantTracks().firstOrNull { it.active == true } ?: return fallback
    val codec = (active.videoCodec as? String).orEmpty()
    return when {
        codec.startsWith("dvh1") || codec.startsWith("dvhe") -> VideoRange.DOLBY_VISION
        (active.hdr as? String) == "PQ" -> VideoRange.HDR10
        (active.hdr as? String) == "HLG" -> VideoRange.HLG
        else -> VideoRange.SDR
    }
}

private fun HTMLVideoElement.bufferedEndMs(fallback: Long): Long {
    val ranges = buffered
    return if (ranges.length > 0) (ranges.end(ranges.length - 1) * 1000).toLong() else fallback
}

/**
 * Cap adaptive selection via Shaka `restrictions` (filters out rungs above the limits, for ABR and
 * manual alike). Always reset (to `Infinity` when unconstrained) so a capped item doesn't leave a
 * stale ceiling on the next source.
 */
private fun shaka.Player.configureRestrictions(constraints: VideoConstraints?) {
    val restrictions: dynamic = js("({})")
    restrictions.maxWidth = constraints?.maxWidth ?: js("Infinity")
    restrictions.maxHeight = constraints?.maxHeight ?: js("Infinity")
    restrictions.maxBandwidth = constraints?.maxBitrateBps ?: js("Infinity")
    val config: dynamic = js("({})")
    config.restrictions = restrictions
    configure(config)
}

/**
 * Pre-select audio/subtitle language before load so the right track is active at first frame. Both lists
 * are written for every item, because configure() merges and would otherwise hand the previous item's
 * preference to this one. A preferred text track is only configured when captions start on: Shaka 5
 * shows whatever text track it selects at load.
 */
private fun shaka.Player.configureLanguagePreferences(item: MediaItem) {
    // The trailing language-less entry keeps Shaka's stereo default when the language is absent.
    val audio = listOfNotNull(item.preferredAudioLanguage, "").map(::audioPreference)
    val text = item.preferredTextLanguage
        ?.takeIf { item.captionsDefault == CaptionsDefaultMode.ON }
        ?.let { listOf(textPreference(it)) }
        .orEmpty()
    val config: dynamic = js("({})")
    config.preferredAudio = audio.toTypedArray()
    config.preferredText = text.toTypedArray()
    configure(config)
}

private fun shaka.Player.disableAbr() {
    val abr: dynamic = js("({})")
    abr.enabled = false
    val cfg: dynamic = js("({})")
    cfg.abr = abr
    configure(cfg)
}
