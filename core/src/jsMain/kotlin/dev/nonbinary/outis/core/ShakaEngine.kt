/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.ads.Ad
import dev.nonbinary.outis.core.ads.AdConfig
import dev.nonbinary.outis.core.ads.AdState
import dev.nonbinary.outis.core.chapters.loadSidecarChapters
import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.plugin.PlayerHost
import dev.nonbinary.outis.core.source.CaptionsDefaultMode
import dev.nonbinary.outis.core.source.DrmConfig
import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.LicenseRequest
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.core.source.VideoConstraints
import dev.nonbinary.outis.core.source.WidevineLevel
import dev.nonbinary.outis.core.thumbnails.loadThumbnails
import dev.nonbinary.outis.core.track.MediaTrack
import dev.nonbinary.outis.core.track.TrackType
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
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
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLMediaElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.MediaError
import org.w3c.dom.events.EventListener
import kotlin.math.abs

/** Read raw bytes from a Shaka request/response body (an ArrayBuffer or an ArrayBufferView). */
private fun jsBodyToByteArray(body: dynamic): ByteArray {
    if (body == null) return ByteArray(0)
    val viewBuffer = body.buffer // an ArrayBufferView (Uint8Array, …) has .buffer; an ArrayBuffer does not
    val i8 = if (viewBuffer != null) {
        Int8Array(
            viewBuffer.unsafeCast<ArrayBuffer>(),
            body.byteOffset.unsafeCast<Int>(),
            body.byteLength.unsafeCast<Int>(),
        )
    } else {
        Int8Array(body.unsafeCast<ArrayBuffer>())
    }
    return ByteArray(i8.length) { i8[it] }
}

/** A fresh ArrayBuffer holding these bytes, for use as a Shaka request/response body. */
private fun ByteArray.toJsBody(): ArrayBuffer {
    val i8 = Int8Array(size)
    for (i in indices) i8[i] = this[i]
    return i8.buffer
}

/**
 * EME `(videoRobustness, audioRobustness)` for a Widevine level. Single strings — EME `robustness` is a
 * `DOMString`, and Shaka 4.11 assigns it as-is (an array would be stringified to a comma-joined token and
 * rejected). L1 = hardware-backed, L3 = software; [WidevineLevel.AUTO] returns `""` (empty), which Shaka
 * omits so the browser/CDM negotiates — and, written every time, resets any level a prior item left in
 * Shaka's merged config.
 */
private fun widevineRobustness(level: WidevineLevel): Pair<String, String> = when (level) {
    WidevineLevel.AUTO -> "" to ""
    WidevineLevel.L1 -> "HW_SECURE_DECODE" to "HW_SECURE_CRYPTO"
    WidevineLevel.L3 -> "SW_SECURE_DECODE" to "SW_SECURE_CRYPTO"
}

/** Read a Shaka request headers object into a Kotlin map (so an interceptor sees Shaka's own headers). */
private fun jsHeadersToMap(headers: dynamic): Map<String, String> {
    if (headers == null) return emptyMap()
    val keys: dynamic = js("Object").keys(headers)
    val len = keys.length.unsafeCast<Int>()
    val map = LinkedHashMap<String, String>()
    for (i in 0 until len) {
        val k = keys[i].unsafeCast<String>()
        map[k] = headers[k].unsafeCast<String>()
    }
    return map
}

/** Build a JS headers object from a Kotlin map. */
private fun jsHeadersOf(map: Map<String, String>): dynamic {
    val obj = js("({})")
    map.forEach { (k, v) -> obj[k] = v }
    return obj
}

// Lower-case deliberately: this mirrors the `shaka` global exactly as the Shaka Player JS API
// documents it, so every member access below reads the same as the upstream docs.
@Suppress("ClassNaming")
@JsModule("shaka-player")
@JsNonModule
private external object shaka {
    object polyfill {
        fun installAll()
    }

    object net {
        object NetworkingEngine {
            object RequestType {
                val LICENSE: Int
            }
        }
    }

    class Player {
        fun attach(mediaElement: HTMLMediaElement): kotlin.js.Promise<dynamic>
        fun load(uri: String, startTime: Double? = definedExternally): kotlin.js.Promise<dynamic>
        fun unload(): kotlin.js.Promise<dynamic>
        fun destroy(): kotlin.js.Promise<dynamic>
        fun addEventListener(type: String, listener: (event: dynamic) -> Unit)
        fun getVariantTracks(): Array<dynamic>
        fun getTextTracks(): Array<dynamic>
        fun selectTextTrack(track: dynamic)
        fun setTextTrackVisibility(on: Boolean) // synchronous in 4.x
        fun selectAudioLanguage(language: String)
        fun selectVariantsByLabel(label: String) // picks a same-language audio stream; keeps video ABR
        fun configure(config: dynamic)
        fun getNetworkingEngine(): dynamic

        // shaka.ads.AdManager — used dynamically (initClientSide/requestClientSideAds/events)
        fun getAdManager(): dynamic
    }

    /** Client-side-ads (IMA HTML5) event-name constants on `shaka.ads.AdManager`. */
    object ads {
        object AdManager {
            val AD_STARTED: String
            val AD_CONTENT_PAUSE_REQUESTED: String
            val AD_CONTENT_RESUME_REQUESTED: String
            val ALL_ADS_COMPLETED: String
            val AD_ERROR: String
        }
    }
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
// Larger than the LargeClass threshold because it carries what is split across two files on the
// other platforms: the player wiring, the DRM request/response filters, the ad manager bridge and
// the state reconciler all bind to the same `shaka.Player` instance and its listener lifecycle.
@Suppress("LargeClass")
internal class ShakaEngine(private val config: PlayerConfig) : VideoPlayer {

    private val _state = MutableStateFlow(PlayerState(volume = config.initialVolume))
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val video = (document.createElement("video") as HTMLVideoElement).also {
        it.controls = false
        it.setAttribute("playsinline", "true")
    }
    private var shakaPlayer = shaka.Player()
    private var shakaBroken = false // a failed Shaka load (e.g. DRM in Safari) wedges the Player; recreate before reuse

    // CSAI: IMA (via Shaka's ad manager) renders its ad UI into this div; the engine keeps it aligned over
    // the <video> and interactive only during ad breaks (see syncAdContainer). Created once, removed on release.
    private val adContainer = (document.createElement("div") as HTMLElement)
    private var adsInitedForPlayer = false // shaka.ads.AdManager.initClientSide done for the current Player instance
    private val _native = MutableStateFlow<Any?>(video)
    override val nativePlayerHandle: Any? get() = _native.value

    private val scope: CoroutineScope = MainScope()

    private var released = false
    private var playWhenReady = false
    private var usingShaka = false
    private var loadGeneration = 0
    private var lastPlaybackState = PlaybackState.IDLE
    private var wasBuffering = false
    private var wasPlaying = false
    private var firstFrameEmitted = false
    private var errorEmitted = false
    private var shakaBuffering = false
    private var intervalId = 0

    private var licenseHeaders: Map<String, String> = emptyMap() // staged per item; injected into LICENSE requests
    private var licenseRequestInterceptor: ((LicenseRequest) -> LicenseRequest)? = null // staged per item
    private var licenseResponseInterceptor: ((ByteArray) -> ByteArray)? = null // staged per item

    // staged per item; injected into manifest/segment requests
    private var mediaHeaders: Map<String, String> = emptyMap()
    private var pendingStartListener: EventListener? = null // one-shot loadedmetadata seek for native start position
    private var loadChain: dynamic = js("Promise.resolve()") // serializes loads so switches never overlap

    private val textTrackById = mutableMapOf<String, Any?>() // shaka text-track objects (dynamic can't be a type arg)

    /** How to re-select an audio stream when same-language streams must be told apart (label first). */
    private class ShakaAudioSelection(val language: String, val label: String?)

    private val audioSelectionById = mutableMapOf<String, ShakaAudioSelection>()
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
        shaka.polyfill.installAll()
        video.volume = config.initialVolume.toDouble()
        with(adContainer.style) {
            position = "fixed"
            margin = "0"
            zIndex = "2" // above the Compose canvas (z-index 1) during ads
            setProperty("overflow", "hidden")
            setProperty("pointer-events", "none") // inert until an ad break (syncAdContainer flips it)
        }
        document.body?.appendChild(adContainer)
        wireShaka(shakaPlayer)
        shakaPlayer.attach(video).catch { it }
        videoEvents.forEach { video.addEventListener(it, videoListener) }
        intervalId = window.setInterval({ if (!released) reconcile() }, config.positionPollIntervalMs.toInt())
        config.components.forEach(::addComponent)
        emit { p, t -> PlayerEvent.NativePlayerAttached(video, p, t) }
    }

    /** Register the license-header request filter and Shaka event listeners on [p] (init + recreate). */
    // One registration per Shaka event plus its guard; linear, and kept in one place so the
    // listener set can be read against the matching removal in release().
    @Suppress("CyclomaticComplexMethod")
    private fun wireShaka(p: shaka.Player) {
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
        p.configure(retryCfg)
        // Buffer/load-control tuning (ms → seconds) + ABR cold-start seed. configure() deep-merges, so
        // these layer onto the retry config above without clobbering it.
        config.bufferConfig?.let { b ->
            val streaming: dynamic = js("({})")
            streaming.bufferingGoal = b.maxBufferMs / 1000.0
            streaming.rebufferingGoal = b.bufferForPlaybackMs / 1000.0
            streaming.bufferBehind = b.backBufferMs / 1000.0
            val cfg: dynamic = js("({})")
            cfg.streaming = streaming
            p.configure(cfg)
        }
        config.initialBitrateBps?.let { bps ->
            val abr: dynamic = js("({})")
            abr.defaultBandwidthEstimate = bps
            val cfg: dynamic = js("({})")
            cfg.abr = abr
            p.configure(cfg)
        }
        if (config.liveConfig?.lowLatencyMode == true) {
            val streaming: dynamic = js("({})")
            streaming.lowLatencyMode = true
            val cfg: dynamic = js("({})")
            cfg.streaming = streaming
            p.configure(cfg)
        }
        // Inject DRM license-request headers (auth tokens) into Shaka's LICENSE requests. The header
        // set is staged per media item by configureDrm (empty for clear content), so one filter suffices.
        p.getNetworkingEngine().registerRequestFilter({ type: Int, request: dynamic ->
            if (type == shaka.net.NetworkingEngine.RequestType.LICENSE) {
                licenseHeaders.forEach { (key, value) -> request.headers[key] = value }
                // Rewrite the license request (URL/body/headers) for non-standard servers — e.g. moving the
                // challenge into the query string. Shaka does the HTTP; we only transform the request.
                licenseRequestInterceptor?.let { transform ->
                    // Feed the interceptor the *actual* outgoing headers (Shaka's own — Content-Type etc. —
                    // plus the licenseHeaders applied above), and let its returned set be authoritative by
                    // replacing the header object. This matches Android/iOS, where the interceptor fully owns
                    // the request and can add, change, or drop headers.
                    val out = transform(
                        LicenseRequest(
                            request.uris[0] as String,
                            jsBodyToByteArray(request.body),
                            jsHeadersToMap(request.headers),
                        ),
                    )
                    request.uris = arrayOf(out.url)
                    request.body = out.body.toJsBody()
                    request.headers = jsHeadersOf(out.headers)
                }
            } else {
                // Manifest/segment/media requests carry the MediaItem auth headers (distinct scope from
                // the DRM-license headers above).
                mediaHeaders.forEach { (key, value) -> request.headers[key] = value }
            }
        })
        // Unwrap the license response (e.g. JSON envelope → base64-decode) before the CDM sees it.
        p.getNetworkingEngine().registerResponseFilter({ type: Int, response: dynamic ->
            if (type == shaka.net.NetworkingEngine.RequestType.LICENSE) {
                licenseResponseInterceptor?.let { transform ->
                    response.data = transform(jsBodyToByteArray(response.data)).toJsBody()
                }
            }
        })
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
        adsInitedForPlayer = false // the fresh Player has no ad manager wired yet
        val old = shakaPlayer
        val fresh = shaka.Player()
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
        audioSelectionById.clear()
        playWhenReady = autoPlay
        mediaHeaders = item.headers
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
                audioTracks = persistentListOf(), textTracks = persistentListOf(),
                selectedAudioTrackId = null, selectedTextTrackId = null,
                chapters = persistentListOf(),
                thumbnails = persistentListOf(),
            )
        }
        emit { p, t -> PlayerEvent.MediaItemTransition(item, p, t) }
        emit { p, t -> PlayerEvent.BufferingStarted(p, t) }

        // WebVTT chapters sidecar — the only chapter source on Web (no local-container parsing here).
        item.chaptersUrl?.let { sidecar ->
            scope.launch {
                val chapters = loadSidecarChapters(sidecar)
                if (!released && generation == loadGeneration && chapters.isNotEmpty()) {
                    _state.update { it.copy(chapters = chapters.toPersistentList()) }
                }
            }
        }
        // WebVTT trickplay-thumbnails sidecar.
        item.thumbnailsUrl?.let { sidecar ->
            scope.launch {
                val thumbs = loadThumbnails(sidecar)
                if (!released && generation == loadGeneration && thumbs.isNotEmpty()) {
                    _state.update { it.copy(thumbnails = thumbs.toPersistentList()) }
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
        usingShaka = true
        configureDrm(item.drmConfig)
        configureRestrictions(item.videoConstraints)
        configureLanguagePreferences(item)
        // Start position (resume / deep-link): Shaka's load() takes a start time in seconds.
        return shakaPlayer.load(url, item.startPositionMs?.let { it / 1000.0 }).then<dynamic>(
            { _ ->
                if (!released && generation == loadGeneration) {
                    if (playWhenReady) playWithAutoplayFallback()
                    loadTracks()
                    applyCaptionsDefault(item) // after loadTracks so its textchanged re-read sees the visibility
                    reconcile()
                    // Client-side ads (CSAI) stitch over the Shaka (MSE) stream via the ad manager.
                    (item.adConfig as? AdConfig.ClientSide)?.let { requestClientSideAds(it.adTagUri) }
                }
                Unit
            },
            { _ ->
                if (!released && generation == loadGeneration) {
                    emitErrorOnce(PlayerError(PlayerError.Category.SOURCE, message = "Failed to load media"))
                }
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
        video.asDynamic().play().catch({ _: dynamic ->
            if (!video.muted) {
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
        video.pause()
        if (usingShaka) shakaPlayer.unload().catch { it } else video.removeAttribute("src")
        usingShaka = false
        playWhenReady = false
        wasPlaying = false
        wasBuffering = false
        shakaBuffering = false
        lastPlaybackState = PlaybackState.IDLE
        textTrackById.clear()
        audioSelectionById.clear()
        _state.update {
            it.copy(
                playbackState = PlaybackState.IDLE, isPlaying = false, playWhenReady = false,
                mediaItem = null, positionMs = 0, bufferedPositionMs = 0, durationMs = null,
                isLive = false, isSeekable = false, pendingSeekTargetMs = null, videoSize = null, error = null,
                audioTracks = persistentListOf(), textTracks = persistentListOf(),
                selectedAudioTrackId = null, selectedTextTrackId = null,
            )
        }
    }

    // ---- client-side ads (CSAI) via Shaka's IMA HTML5 ad manager ----

    /**
     * Stitch client-side ads from [adTagUri] over the just-loaded content via Shaka's ad manager (which
     * wraps the IMA HTML5 SDK — `google.ima`, loaded as a global by the host page's `ima3.js` script), and
     * map its ad lifecycle into [PlayerState.adState] so the shared chrome blocks seeking, mirroring
     * Android/iOS. `initClientSide` runs once per Player; subsequent items just request again.
     */
    private fun requestClientSideAds(adTagUri: String) {
        val adManager = shakaPlayer.getAdManager()
        if (!adsInitedForPlayer) {
            adManager.initClientSide(adContainer, video)
            wireAdEvents(adManager)
            adsInitedForPlayer = true
        }
        val request = js("new google.ima.AdsRequest()")
        request.adTagUrl = adTagUri
        adManager.requestClientSideAds(request)
    }

    private fun wireAdEvents(adManager: dynamic) {
        adManager.addEventListener(shaka.ads.AdManager.AD_CONTENT_PAUSE_REQUESTED) { _: dynamic ->
            if (!released) _state.update { it.copy(adState = AdState(isInAdBreak = true)) }
        }
        adManager.addEventListener(shaka.ads.AdManager.AD_STARTED) { e: dynamic ->
            if (!released) _state.update { it.copy(adState = adStateFromShaka(e.ad)) }
        }
        val clear = { _: dynamic -> if (!released) _state.update { it.copy(adState = null) } }
        adManager.addEventListener(shaka.ads.AdManager.AD_CONTENT_RESUME_REQUESTED, clear)
        adManager.addEventListener(shaka.ads.AdManager.ALL_ADS_COMPLETED, clear)
        adManager.addEventListener(shaka.ads.AdManager.AD_ERROR, clear)
    }

    /** Map a Shaka `shaka.extern.IAd` to [AdState]. */
    private fun adStateFromShaka(ad: dynamic): AdState {
        if (ad == null) return AdState(isInAdBreak = true)
        val durationSec: Double = ad.getDuration()
        val remainingSec: Double = ad.getRemainingTime()
        val skippable: Boolean = ad.isSkippable()
        val title: String? = ad.getTitle()
        val adId: String? = ad.getAdId()
        val seqLen: Int = ad.getSequenceLength()
        val posInSeq: Int = ad.getPositionInSequence()
        val canSkipNow: Boolean = ad.canSkipNow()
        return AdState(
            isInAdBreak = true,
            currentAd = Ad(
                id = adId ?: "",
                durationMs = (durationSec * 1000).toLong(),
                title = title,
                skipOffsetMs = if (skippable) {
                    val secs: Double = ad.getTimeUntilSkippable()
                    (secs.coerceAtLeast(0.0) * 1000).toLong()
                } else {
                    null
                },
            ),
            adIndexInBreak = (posInSeq - 1).coerceAtLeast(0),
            adCountInBreak = seqLen,
            adRemainingMs = (remainingSec.coerceAtLeast(0.0) * 1000).toLong(),
            canSkip = skippable && canSkipNow,
        )
    }

    /**
     * Keep the ad container aligned over the `<video>` and interactive **only during an ad break** — IMA's
     * skip/click-through must be reachable above the Compose canvas (z-index 1), but outside ads the
     * container must be inert so the overlay receives touches. Cheap; runs on the existing reconcile poll.
     */
    private fun syncAdContainer() {
        val s = adContainer.style
        if (_state.value.adState?.isInAdBreak == true) {
            val r = video.getBoundingClientRect()
            s.position = "fixed"
            s.left = "${r.left}px"
            s.top = "${r.top}px"
            s.width = "${r.width}px"
            s.height = "${r.height}px"
            s.zIndex = "2" // re-assert in case the surface raised body children to z-index 1
            s.setProperty("pointer-events", "auto")
        } else {
            s.setProperty("pointer-events", "none")
        }
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
        adContainer.parentNode?.removeChild(adContainer)
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
            TrackType.AUDIO -> audioSelectionById[track.id]?.let { sel ->
                // Label picks the exact stream when languages collide (stereo vs 5.1 vs commentary) and
                // keeps video ABR, unlike selectVariantTrack; fall back to language when there's no label.
                if (sel.label != null) {
                    shakaPlayer.selectVariantsByLabel(sel.label)
                } else {
                    shakaPlayer.selectAudioLanguage(sel.language)
                }
                // On HLS, selecting audio can re-enable text visibility; re-assert "off" if subtitles are off.
                if (_state.value.selectedTextTrackId == null) shakaPlayer.setTextTrackVisibility(false)
                _state.update { st ->
                    st.copy(
                        audioTracks = st.audioTracks.map { it.copy(isSelected = it.id == track.id) }.toPersistentList(),
                        selectedAudioTrackId = track.id,
                    )
                }
            }

            TrackType.TEXT -> textTrackById[track.id]?.let { shakaTrack ->
                shakaPlayer.selectTextTrack(shakaTrack)
                shakaPlayer.setTextTrackVisibility(true)
                _state.update { st ->
                    st.copy(
                        textTracks = st.textTracks.map { it.copy(isSelected = it.id == track.id) }.toPersistentList(),
                        selectedTextTrackId = track.id,
                    )
                }
            }

            TrackType.VIDEO -> Unit
        }
        // The variantchanged/textchanged events re-emit the authoritative lists; the above is optimistic.
        emit { p, t -> PlayerEvent.TracksChanged(_state.value.audioTracks, _state.value.textTracks, p, t) }
    }

    override fun clearTextTrack() {
        if (released) return
        if (usingShaka) shakaPlayer.setTextTrackVisibility(false)
        _state.update { st ->
            st.copy(
                textTracks = st.textTracks.map { it.copy(isSelected = false) }.toPersistentList(),
                selectedTextTrackId = null,
            )
        }
        emit { p, t -> PlayerEvent.TracksChanged(_state.value.audioTracks, _state.value.textTracks, p, t) }
    }

    /**
     * Point Shaka's CDM at the license server before `load`, and stage the license-request headers
     * that the `init` request filter injects into LICENSE requests. The header set is reset each call
     * (empty for clear content). Note Shaka's `configure()` **merges** — it never deletes keys — so a
     * value a prior item set survives unless this call overwrites it; we therefore rewrite the fields a
     * later item could otherwise inherit (`servers[keySystem]`, and for Widevine the robustness — `""`
     * for AUTO) rather than relying on the rebuilt-each-call `servers`/`advanced` locals to clear them.
     * Entries for key systems the current item doesn't use linger in the merged config but are inert (no
     * matching content). FairPlay (Safari) additionally needs the application certificate, which Shaka
     * fetches from `serverCertificateUri`; [DrmScheme.CLEARKEY] instead carries its keys inline via
     * `clearKeys` (no server). A Widevine [DrmConfig.widevineLevel] maps to EME video/audio
     * robustness on the key system's `advanced` entry. (`multiSession` is an Android-only flag — not
     * applied here.)
     */
    private fun configureDrm(drm: DrmConfig?) {
        licenseHeaders = drm?.licenseRequestHeaders ?: emptyMap()
        licenseRequestInterceptor = drm?.licenseRequestInterceptor
        licenseResponseInterceptor = drm?.licenseResponseInterceptor
        val servers: dynamic = js("({})")
        val advanced: dynamic = js("({})")
        val clearKeys: dynamic = js("({})")
        if (drm != null && drm.scheme == DrmScheme.CLEARKEY) {
            // Keys supplied inline (hex keyId:key) — no license server. Shaka takes them via `clearKeys`.
            drm.clearKeys.forEach { (keyId, key) -> clearKeys[keyId] = key }
        } else if (drm != null) {
            val keySystem = when (drm.scheme) {
                DrmScheme.WIDEVINE -> "com.widevine.alpha"
                DrmScheme.PLAYREADY -> "com.microsoft.playready"
                DrmScheme.FAIRPLAY -> "com.apple.fps"
                DrmScheme.CLEARKEY -> error("ClearKey is handled above")
            }
            servers[keySystem] = drm.licenseServerUrl
            // FairPlay requires the FPS application certificate; let Shaka fetch it.
            if (drm.scheme == DrmScheme.FAIRPLAY && drm.certificateUrl != null) {
                val fps: dynamic = js("({})")
                fps.serverCertificateUri = drm.certificateUrl
                advanced[keySystem] = fps
            }
            // Widevine security level → EME robustness. L1 demands hardware (HW_SECURE_*, fails if the
            // browser has no hardware CDM); L3 forces software (SW_SECURE_*); AUTO writes "" so the
            // browser/CDM negotiates. Always set it (even for AUTO): configure() merges and never deletes
            // keys, so writing "" each time clears any HW/SW level a prior item left behind. No analog for
            // PlayReady (different tokens) or FairPlay, so the knob is Widevine-only here too.
            if (drm.scheme == DrmScheme.WIDEVINE) {
                val (videoRob, audioRob) = widevineRobustness(drm.widevineLevel)
                val wv: dynamic = js("({})")
                wv.videoRobustness = videoRob
                wv.audioRobustness = audioRob
                advanced[keySystem] = wv
            }
        }
        val drmConfig: dynamic = js("({})")
        drmConfig.servers = servers
        drmConfig.advanced = advanced
        drmConfig.clearKeys = clearKeys
        val config: dynamic = js("({})")
        config.drm = drmConfig
        shakaPlayer.configure(config)
    }

    /**
     * Cap adaptive selection via Shaka `restrictions` (filters out rungs above the limits, for ABR and
     * manual alike). Always reset (to `Infinity` when unconstrained) so a capped item doesn't leave a
     * stale ceiling on the next source.
     */

    /** Pre-select audio/subtitle language before load so the right track is active at first frame. */
    private fun configureLanguagePreferences(item: MediaItem) {
        val audio = item.preferredAudioLanguage
        val text = item.preferredTextLanguage
        if (audio == null && text == null) return
        val config: dynamic = js("({})")
        if (audio != null) config.preferredAudioLanguage = audio
        if (text != null) config.preferredTextLanguage = text
        shakaPlayer.configure(config)
    }

    /** Initial caption visibility after load. FOLLOW_SYSTEM has no universal web signal, so it maps to OFF. */
    private fun applyCaptionsDefault(item: MediaItem) {
        shakaPlayer.setTextTrackVisibility(item.captionsDefault == CaptionsDefaultMode.ON)
    }

    private fun configureRestrictions(constraints: VideoConstraints?) {
        val restrictions: dynamic = js("({})")
        restrictions.maxWidth = constraints?.maxWidth ?: js("Infinity")
        restrictions.maxHeight = constraints?.maxHeight ?: js("Infinity")
        restrictions.maxBandwidth = constraints?.maxBitrateBps ?: js("Infinity")
        val config: dynamic = js("({})")
        config.restrictions = restrictions
        shakaPlayer.configure(config)
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
    // Shaka player to build a single coherent PlayerState snapshot.
    @Suppress("CyclomaticComplexMethod")
    private fun reconcile() {
        if (released) return
        syncAdContainer()
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
        val bufferedMs = bufferedEndMs(positionMs)

        _state.update {
            it.copy(
                playbackState = playbackState,
                isPlaying = playing,
                positionMs = positionMs,
                bufferedPositionMs = bufferedMs,
                durationMs = durationMs,
                isLive = isLive,
                isSeekable = video.seekable.length > 0,
                videoRange = currentVideoRange(it.videoRange),
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

    /** Colour range of Shaka's active variant — DV from the codec, HDR from Shaka's `hdr` field ('PQ'/'HLG'). */
    private fun currentVideoRange(fallback: VideoRange): VideoRange {
        val active = shakaPlayer.getVariantTracks().firstOrNull { it.active == true } ?: return fallback
        val codec = (active.videoCodec as? String).orEmpty()
        return when {
            codec.startsWith("dvh1") || codec.startsWith("dvhe") -> VideoRange.DOLBY_VISION
            (active.hdr as? String) == "PQ" -> VideoRange.HDR10
            (active.hdr as? String) == "HLG" -> VideoRange.HLG
            else -> VideoRange.SDR
        }
    }

    private fun bufferedEndMs(fallback: Long): Long {
        val ranges = video.buffered
        return if (ranges.length > 0) (ranges.end(ranges.length - 1) * 1000).toLong() else fallback
    }

    private fun loadTracks() {
        if (released || !usingShaka) return
        textTrackById.clear()
        audioSelectionById.clear()

        val variants = shakaPlayer.getVariantTracks()
        val audio = mutableListOf<MediaTrack>()
        val seenAudio = mutableSetOf<String>()
        for (i in 0 until variants.size) {
            val variant = variants[i]
            val language = variant.language as? String ?: continue
            val label = variant.label as? String
            // Several audio streams can share a language (e.g. stereo + 5.1 + commentary, all "en"),
            // differing only by label/channels. Key on the distinct audio stream (audioId), not language,
            // so same-language streams don't collapse into a single selectable entry.
            val audioKey = (variant.audioId as? Number)?.toString() ?: label ?: language
            if (!seenAudio.add(audioKey)) continue
            val id = "AUDIO:$audioKey"
            audioSelectionById[id] = ShakaAudioSelection(language, label)
            audio += MediaTrack(
                id = id,
                type = TrackType.AUDIO,
                label = label,
                language = language,
                isSelected = variant.active == true,
            )
        }

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
                audioTracks = audio.toPersistentList(),
                textTracks = text.toPersistentList(),
                selectedAudioTrackId = audio.firstOrNull { t -> t.isSelected }?.id,
                selectedTextTrackId = text.firstOrNull { t -> t.isSelected }?.id,
            )
        }
        emit { p, t -> PlayerEvent.TracksChanged(_state.value.audioTracks, _state.value.textTracks, p, t) }
    }

    private fun onShakaError(detail: dynamic) {
        val category = when (detail?.category) {
            1 -> PlayerError.Category.NETWORK
            3 -> PlayerError.Category.DECODER
            4 -> PlayerError.Category.SOURCE
            6 -> PlayerError.Category.DRM
            else -> PlayerError.Category.UNKNOWN
        }
        emitErrorOnce(PlayerError(category, code = (detail?.code as? Int)?.toString(), message = "Shaka error"))
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
