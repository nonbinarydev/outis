/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.ads.AdState
import dev.nonbinary.outis.core.chapters.Chapter
import dev.nonbinary.outis.core.chapters.ChapterExtractor
import dev.nonbinary.outis.core.chapters.ChapterReader
import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.plugin.PlayerHost
import dev.nonbinary.outis.core.source.CaptionsDefaultMode
import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.track.MediaTrack
import dev.nonbinary.outis.core.track.TrackType
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionGroup
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.asset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.error
import platform.AVFoundation.extendedLanguageTag
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.muted
import platform.AVFoundation.playbackBufferEmpty
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.seekableTimeRanges
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.selectedMediaOptionInMediaSelectionGroup
import platform.AVFoundation.setAutomaticallyPreservesTimeOffsetFromLive
import platform.AVFoundation.setConfiguredTimeOffsetFromLive
import platform.AVFoundation.setMuted
import platform.AVFoundation.setPreferredForwardBufferDuration
import platform.AVFoundation.setPreferredMaximumResolution
import platform.AVFoundation.setPreferredPeakBitRate
import platform.AVFoundation.setRate
import platform.AVFoundation.setVolume
import platform.AVFoundation.status
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSUnderlyingErrorKey
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIViewController
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.O_RDONLY
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.lseek
import platform.posix.open
import platform.posix.read
import kotlin.native.ref.WeakReference

/**
 * Keeps every live engine strongly reachable until [AVPlayerEngine.release] is called. The observer
 * closures hold only a [WeakReference] to the engine (to break the retain cycle), so without this an
 * engine whose `VideoPlayer` reference is dropped could be GC'd while its AVPlayer keeps playing —
 * a zombie. Entries are added/removed on the main thread only. **Callers MUST call release().**
 */
private val liveEngines = mutableSetOf<AVPlayerEngine>()

/**
 * iOS [VideoPlayer] backed by AVFoundation's [AVPlayer].
 *
 * **Threading.** AVPlayer is main-thread affine. The engine is constructed on the main thread
 * (asserted), every public transport call is marshalled to main via [onMain], and the periodic
 * time observer + notifications + the seek completion handler all resolve on the main thread — so
 * `released`/`player`/`state`/`events` are touched on one thread (no atomics needed).
 *
 * **State** is derived by polling in the periodic time observer (no KVO): AVPlayer.timeControlStatus
 * + AVPlayerItem.status drive [PlayerState]; seek uses the completion handler; end + failure surface
 * via NSNotificationCenter and AVPlayerItem.status==Failed.
 *
 * **Memory.** Observer/notification closures capture only a [WeakReference] back to the engine
 * (AVFoundation retains the closures); [liveEngines] keeps the engine alive until [release].
 */
internal class AVPlayerEngine(
    private val config: PlayerConfig,
) : VideoPlayer {

    private val _state = MutableStateFlow(PlayerState(volume = config.initialVolume))
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val _native = MutableStateFlow<Any?>(null)
    override val nativePlayerHandle: Any? get() = _native.value

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // All of the following are touched only on the main thread.
    private var player: AVPlayer? = null
    private var timeObserver: Any? = null
    private var endObserver: NSObjectProtocol? = null
    private var failObserver: NSObjectProtocol? = null
    private var released = false

    private var playWhenReady = false
    private var desiredRate: Float = 1f
    private var lastPlaybackState = PlaybackState.IDLE
    private var wasBuffering = false
    private var wasPlaying = false
    private var firstFrameEmitted = false
    private var endedFlag = false
    private var errorEmitted = false
    private var tracksLoaded = false
    private var tracksLoadAttempts = 0
    private var startSeekTargetMs: Long? = null // applied once the item is first ready (resume / deep-link)
    private var stallStartedElapsedMs: Long = 0L // monotonic; 0 = not currently stalled
    private var lastStallRecoveryElapsedMs: Long = 0L // cooldown so the stall nudge can't loop
    private var audioGroup: AVMediaSelectionGroup? = null
    private var textGroup: AVMediaSelectionGroup? = null
    private val trackOptions = mutableMapOf<String, Pair<AVMediaSelectionGroup, AVMediaSelectionOption>>()
    private var fairPlay: FairPlayContentKeyManager? = null // lazily created for the first FairPlay item

    // The :ui AVPlayerViewController: anchors CSAI IMA's ad-display container, and is the view-level
    // object Mux's iOS SDK monitors — surfaced to analytics components as nativePresentationHandle.
    private val _presentation = MutableStateFlow<UIViewController?>(null)

    private val components = mutableListOf<PlayerComponent>()
    private val host = object : PlayerHost {
        override val state: StateFlow<PlayerState> get() = this@AVPlayerEngine.state
        override val events: SharedFlow<PlayerEvent> get() = this@AVPlayerEngine.events
        override val scope: CoroutineScope get() = this@AVPlayerEngine.scope
        override val nativePlayerHandle: StateFlow<Any?> get() = _native
        override val nativePresentationHandle: StateFlow<Any?> get() = _presentation
    }

    init {
        check(NSThread.isMainThread()) {
            "AVPlayerEngine must be constructed on the main thread (AVPlayer is main-thread affine)."
        }
        liveEngines.add(this)
        activateAudioSession()
        val avPlayer = AVPlayer()
        avPlayer.setVolume(config.initialVolume)
        installTimeObserver(avPlayer)
        player = avPlayer
        _native.value = avPlayer
        config.components.forEach(::addComponent)
        emit { p, t -> PlayerEvent.NativePlayerAttached(avPlayer, p, t) }
    }

    // ---- transport (marshalled to main) ----

    // Loading an item on AVFoundation is a sequence of independent optional steps — DRM, headers,
    // start position, preferred audio/text language, external subtitles, ad container. Each is one
    // branch, and splitting them into helpers would hide the order they must run in.
    @Suppress("CyclomaticComplexMethod")
    override fun setMediaItem(item: MediaItem, autoPlay: Boolean) = onMain {
        val avPlayer = player ?: return@onMain
        removeItemObservers()
        endedFlag = false
        errorEmitted = false
        firstFrameEmitted = false
        wasBuffering = false
        wasPlaying = false
        tracksLoaded = false
        tracksLoadAttempts = 0
        trackOptions.clear()
        audioGroup = null
        textGroup = null
        lastPlaybackState = PlaybackState.BUFFERING
        startSeekTargetMs = item.startPositionMs?.takeIf { it > 0 }
        stallStartedElapsedMs = 0L

        val url = when (val source = item.source) {
            is MediaSource.Url -> NSURL(string = source.url)
            is MediaSource.LocalFile -> NSURL.fileURLWithPath(source.path)
        }
        // Manifest/segment request headers. AVFoundation reads them from the (private but long-stable)
        // "AVURLAssetHTTPHeaderFieldsKey" option; the public constant isn't exposed to Kotlin/Native, so
        // we pass the literal key. App-Store-safe alternative if needed: an AVAssetResourceLoaderDelegate
        // that issues the requests with these headers.
        val assetOptions: Map<Any?, *>? =
            if (item.headers.isEmpty()) {
                null
            } else {
                mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to item.headers.toMap())
            }
        val asset = AVURLAsset(uRL = url, options = assetOptions)
        // FairPlay: a fresh key manager per item — releasing the previous one drops any stale in-flight
        // key work, so a slow license failure for the old asset can't be misattributed to this one.
        // Register the asset for content-key delivery before the item starts loading. (iOS has no
        // Widevine/PlayReady CDM, so other schemes aren't applied and will fail to play.)
        // DrmConfig.widevineLevel is intentionally ignored: FairPlay exposes no client-settable security
        // level (the device + license server decide), and there's no in-app Widevine to force a level on.
        fairPlay?.release()
        fairPlay = null
        val drm = item.drmConfig
        if (drm?.scheme == DrmScheme.FAIRPLAY && drm.certificateUrl != null) {
            val manager = FairPlayContentKeyManager { err -> onMain { emitErrorOnce(err) } }
            fairPlay = manager
            manager.prepare(asset, drm)
        }
        val playerItem = AVPlayerItem(asset = asset)
        // Per-item video ceiling (defaults are unbounded; a fresh item each time, so no reset needed).
        item.videoConstraints?.let { c ->
            if (c.maxWidth != null && c.maxHeight != null) {
                playerItem.setPreferredMaximumResolution(CGSizeMake(c.maxWidth.toDouble(), c.maxHeight.toDouble()))
            }
            c.maxBitrateBps?.let { playerItem.setPreferredPeakBitRate(it.toDouble()) }
        }
        // Forward-buffer hint — the only BufferConfig knob AVPlayer exposes (the rest are no-ops here).
        config.bufferConfig?.let { playerItem.setPreferredForwardBufferDuration(it.maxBufferMs / 1000.0) }
        // Live target offset behind the edge (catch-up speed is automatic on AVPlayer).
        config.liveConfig?.targetOffsetMs?.let { ms ->
            playerItem.setAutomaticallyPreservesTimeOffsetFromLive(true)
            playerItem.setConfiguredTimeOffsetFromLive(CMTimeMakeWithSeconds(ms / 1000.0, 600))
        }
        installItemObservers(playerItem)
        avPlayer.replaceCurrentItemWithPlayerItem(playerItem)

        playWhenReady = autoPlay
        if (autoPlay) avPlayer.setRate(desiredRate)
        // startMuted only ever forces mute on; never unmutes an already-muted player.
        val muted = item.startMuted || _state.value.isMuted
        if (item.startMuted) avPlayer.setMuted(true)

        _state.update {
            it.copy(
                mediaItem = item,
                error = null,
                pendingSeekTargetMs = null,
                positionMs = 0,
                bufferedPositionMs = 0,
                durationMs = null,
                isLive = false,
                isSeekable = false,
                videoSize = null,
                playWhenReady = autoPlay,
                isMuted = muted,
                playbackState = PlaybackState.BUFFERING,
                audioTracks = persistentListOf(),
                textTracks = persistentListOf(),
                selectedAudioTrackId = null,
                selectedTextTrackId = null,
                chapters = persistentListOf(),
            )
        }
        emit { p, t -> PlayerEvent.MediaItemTransition(item, p, t) }
        emit { p, t -> PlayerEvent.BufferingStarted(p, t) }
        extractChaptersFor(item)
    }

    override fun play() = onMain {
        val avPlayer = player ?: return@onMain
        playWhenReady = true
        avPlayer.setRate(desiredRate)
        _state.update { it.copy(playWhenReady = true) }
        reconcile()
    }

    override fun pause() = onMain {
        val avPlayer = player ?: return@onMain
        playWhenReady = false
        avPlayer.setRate(0f)
        // Update isPlaying synchronously rather than waiting for the next observer tick (which won't
        // fire again once the timeline is frozen at rate 0).
        val wasIt = wasPlaying
        wasPlaying = false
        _state.update { it.copy(playWhenReady = false, isPlaying = false) }
        if (wasIt) emit { p, t -> PlayerEvent.IsPlayingChanged(false, p, t) }
    }

    override fun seekTo(positionMs: Long) = onMain {
        val avPlayer = player ?: return@onMain
        endedFlag = false // a seek means we're no longer at end-of-stream
        _state.update { it.copy(positionMs = positionMs, pendingSeekTargetMs = positionMs) }
        emit { p, t -> PlayerEvent.SeekStarted(positionMs, p, t) }
        val target = CMTimeMakeWithSeconds(positionMs / 1000.0, 600)
        val zero = CMTimeMake(0, 1)
        val weakSelf = WeakReference(this)
        avPlayer.seekToTime(target, toleranceBefore = zero, toleranceAfter = zero) { _ ->
            // Completion may arrive on any queue; hop to main.
            val self = weakSelf.get() ?: return@seekToTime
            self.onMain {
                if (self.released) return@onMain
                self._state.update { it.copy(pendingSeekTargetMs = null) }
                self.reconcile()
                self.emit { p, t -> PlayerEvent.SeekCompleted(p, t) }
            }
        }
    }

    override fun setPlaybackSpeed(speed: Float) = onMain {
        val avPlayer = player ?: return@onMain
        desiredRate = speed
        if (playWhenReady) avPlayer.setRate(speed)
        _state.update { it.copy(playbackSpeed = speed) }
    }

    override fun setVolume(volume: Float) = onMain {
        player?.setVolume(volume)
        _state.update { it.copy(volume = volume) }
    }

    override fun setMuted(muted: Boolean) = onMain {
        player?.setMuted(muted)
        _state.update { it.copy(isMuted = muted) }
    }

    /**
     * Push ad state from an external client-side-ad (CSAI) coordinator — e.g. an IMA iOS adapter — into
     * [PlayerState.adState] so the shared chrome blocks seeking during ads. The iOS counterpart of the
     * Media3 engine deriving ad state internally on Android: here the content [player] is paused/resumed by
     * the coordinator (IMA iOS uses content pause/resume, not a stitched timeline), so no position guarding
     * is needed. `null` clears it (content resumed). Driven via the public [updateAdState] bridge.
     */
    internal fun setAdState(adState: AdState?) = onMain {
        _state.update { it.copy(adState = adState) }
    }

    /** Register/clear the :ui ad-container view controller (the AVPlayerViewController). Main-thread only. */
    internal fun setAdContainer(controller: UIViewController?) = onMain { _presentation.value = controller }

    /** The registered ad-container view controller, for an iOS IMA adapter's IMAAdDisplayContainer. */
    internal fun adContainer(): UIViewController? = _presentation.value

    override fun stop() = onMain {
        val avPlayer = player ?: return@onMain
        avPlayer.setRate(0f)
        removeItemObservers()
        avPlayer.replaceCurrentItemWithPlayerItem(null)
        playWhenReady = false
        endedFlag = false
        errorEmitted = false
        tracksLoaded = false
        tracksLoadAttempts = 0
        trackOptions.clear()
        audioGroup = null
        textGroup = null
        startSeekTargetMs = null
        stallStartedElapsedMs = 0L
        wasPlaying = false
        wasBuffering = false
        lastPlaybackState = PlaybackState.IDLE
        _state.update {
            it.copy(
                playbackState = PlaybackState.IDLE,
                isPlaying = false,
                playWhenReady = false,
                mediaItem = null,
                positionMs = 0,
                bufferedPositionMs = 0,
                durationMs = null,
                isLive = false,
                isSeekable = false,
                pendingSeekTargetMs = null,
                videoSize = null,
                error = null,
                audioTracks = persistentListOf(),
                textTracks = persistentListOf(),
                selectedAudioTrackId = null,
                selectedTextTrackId = null,
            )
        }
    }

    override fun release() = onMain {
        if (released) return@onMain // idempotent; the flag is touched only on main
        released = true
        liveEngines.remove(this)
        // Hand the audio session back so other apps' audio can resume — but only once no engine is
        // left using it (multiple players share the one process-wide session).
        if (liveEngines.isEmpty()) AVAudioSession.sharedInstance().setActive(false, error = null)
        components.toList().forEach { it.detach() }
        components.clear()
        fairPlay?.release()
        fairPlay = null
        _presentation.value = null
        val avPlayer = player
        if (avPlayer != null) {
            timeObserver?.let { avPlayer.removeTimeObserver(it) }
            timeObserver = null
            removeItemObservers()
            avPlayer.setRate(0f)
            avPlayer.replaceCurrentItemWithPlayerItem(null)
        }
        player = null
        _native.value = null
        scope.cancel()
    }

    override fun addComponent(component: PlayerComponent) = onMain {
        if (released) return@onMain
        components += component
        component.attach(host)
    }

    override fun removeComponent(component: PlayerComponent) = onMain {
        if (components.remove(component)) component.detach()
    }

    override fun selectTrack(track: MediaTrack) = onMain {
        val item = player?.currentItem ?: return@onMain
        val entry = trackOptions[track.id] ?: return@onMain
        item.selectMediaOption(entry.second, inMediaSelectionGroup = entry.first)
        refreshTrackSelection(item)
    }

    override fun clearTextTrack() = onMain {
        val item = player?.currentItem ?: return@onMain
        val group = textGroup ?: return@onMain
        item.selectMediaOption(null, inMediaSelectionGroup = group)
        refreshTrackSelection(item)
    }

    // ---- observers ----

    private fun installTimeObserver(avPlayer: AVPlayer) {
        val weakSelf = WeakReference(this)
        val interval = CMTimeMakeWithSeconds(config.positionPollIntervalMs / 1000.0, 600)
        timeObserver = avPlayer.addPeriodicTimeObserverForInterval(interval, dispatch_get_main_queue()) { _ ->
            weakSelf.get()?.let { if (!it.released) it.reconcile() }
        }
    }

    private fun installItemObservers(item: AVPlayerItem) {
        val weakSelf = WeakReference(this)
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        endObserver = center.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = queue,
        ) { _: NSNotification? ->
            weakSelf.get()?.onEndReached()
        }
        failObserver = center.addObserverForName(
            name = AVPlayerItemFailedToPlayToEndTimeNotification,
            `object` = item,
            queue = queue,
        ) { notification: NSNotification? ->
            weakSelf.get()?.onItemFailed(notification)
        }
    }

    private fun removeItemObservers() {
        val center = NSNotificationCenter.defaultCenter
        endObserver?.let { center.removeObserver(it) }
        endObserver = null
        failObserver?.let { center.removeObserver(it) }
        failObserver = null
    }

    private fun onEndReached() {
        if (released) return
        val avPlayer = player
        if (avPlayer != null && _state.value.mediaItem?.loop == true) {
            // Loop: jump back to the start and keep playing instead of ending (AVPlayer has no repeat mode).
            val zero = CMTimeMake(0, 1)
            avPlayer.seekToTime(zero, toleranceBefore = zero, toleranceAfter = zero) { _ -> }
            if (playWhenReady) avPlayer.setRate(desiredRate)
            _state.update { it.copy(positionMs = 0) }
            return
        }
        endedFlag = true
        reconcile()
    }

    private fun onItemFailed(notification: NSNotification?) {
        if (released) return
        val nsError = notification?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError
        emitErrorOnce(
            PlayerError(
                category = PlayerError.Category.SOURCE,
                code = nsError?.code?.toString(),
                message = describeError(nsError) ?: "Failed to play to end",
                nativeCause = nsError,
            ),
        )
    }

    /**
     * Build a diagnostic string from an [NSError]: domain, code, message — plus any underlying error.
     * AVFoundation wraps the real CoreMedia/decoder failure in `NSUnderlyingErrorKey`, and that code
     * (e.g. a `CoreMediaErrorDomain` value) is usually what actually pinpoints the problem.
     */
    private fun describeError(e: NSError?): String? {
        if (e == null) return null
        val base = "${e.domain} ${e.code}: ${e.localizedDescription}"
        val underlying = e.userInfo[NSUnderlyingErrorKey] as? NSError
        return if (underlying != null) {
            "$base — underlying ${underlying.domain} ${underlying.code}: ${underlying.localizedDescription}"
        } else {
            base
        }
    }

    private fun emitErrorOnce(error: PlayerError) {
        if (errorEmitted) return
        errorEmitted = true
        wasPlaying = false
        wasBuffering = false
        lastPlaybackState = PlaybackState.IDLE
        _state.update { it.copy(error = error, playbackState = PlaybackState.IDLE, isPlaying = false) }
        emit { p, t -> PlayerEvent.FatalError(error, p, t) }
    }

    // ---- track selection (AVMediaSelectionGroup) ----

    private fun loadTracks(item: AVPlayerItem) {
        trackOptions.clear()
        audioGroup = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible)
        textGroup = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible)
        applyTrackPreferences(item)
        val audio = buildTracks(item, audioGroup, TrackType.AUDIO)
        val text = buildTracks(item, textGroup, TrackType.TEXT)
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

    /**
     * Parse embedded chapters (mp4/m4v QuickTime/Nero) off the main thread with the shared
     * [ChapterExtractor] over the local file, and publish them. AVFoundation can't open mkv, so there are
     * never mkv chapters to read here. No-op for remote sources.
     */
    private fun extractChaptersFor(item: MediaItem) {
        val path = when (val s = item.source) {
            is MediaSource.LocalFile -> s.path
            is MediaSource.Url -> if (s.url.startsWith("file://")) s.url.removePrefix("file://") else return
        }
        // Off the main thread: title parsing reads only small ranges, but thumbnail extraction
        // (MediaItem.chapterThumbnails) can read multi-MB image samples, which must not block the UI.
        scope.launch(Dispatchers.Default) {
            val chapters = runCatching {
                val fd = open(path, O_RDONLY)
                if (fd < 0) return@runCatching emptyList<Chapter>()
                val size = lseek(fd, 0, SEEK_END)
                val result = ChapterExtractor.extract(
                    PosixChapterReader(fd, size),
                    includeThumbnails = item.chapterThumbnails
                )
                close(fd)
                result
            }.getOrElse { emptyList() }
            if (chapters.isEmpty()) return@launch
            onMain {
                // Only apply if this is still the current item (a newer setMediaItem must not be clobbered).
                if (!released && _state.value.mediaItem === item) {
                    _state.update { it.copy(chapters = chapters.toPersistentList()) }
                }
            }
        }
    }

    private fun buildTracks(item: AVPlayerItem, group: AVMediaSelectionGroup?, type: TrackType): List<MediaTrack> {
        if (group == null) return emptyList()
        val selected = item.selectedMediaOptionInMediaSelectionGroup(group)
        return (group.options ?: emptyList<Any?>()).mapIndexedNotNull { index, any ->
            val option = any as? AVMediaSelectionOption ?: return@mapIndexedNotNull null
            val id = "${type.name}:$index"
            trackOptions[id] = group to option
            MediaTrack(
                id = id,
                type = type,
                label = option.displayName,
                language = option.extendedLanguageTag,
                isSelected = option == selected,
            )
        }
    }

    private fun refreshTrackSelection(item: AVPlayerItem) {
        val audioSel = audioGroup?.let { item.selectedMediaOptionInMediaSelectionGroup(it) }
        val textSel = textGroup?.let { item.selectedMediaOptionInMediaSelectionGroup(it) }
        _state.update { st ->
            st.copy(
                audioTracks = st.audioTracks.map {
                    it.copy(
                        isSelected = trackOptions[it.id]?.second == audioSel
                    )
                }.toPersistentList(),
                textTracks = st.textTracks.map {
                    it.copy(
                        isSelected = trackOptions[it.id]?.second == textSel
                    )
                }.toPersistentList(),
                selectedAudioTrackId = st.audioTracks.firstOrNull { trackOptions[it.id]?.second == audioSel }?.id,
                selectedTextTrackId = st.textTracks.firstOrNull { trackOptions[it.id]?.second == textSel }?.id,
            )
        }
        emit { p, t -> PlayerEvent.TracksChanged(_state.value.audioTracks, _state.value.textTracks, p, t) }
    }

    /**
     * Apply the [MediaItem]'s load-time language + caption preferences to the selection groups, once,
     * as tracks load — so the right audio/subtitle is selected before first frame instead of after.
     */
    private fun applyTrackPreferences(playerItem: AVPlayerItem) {
        val mi = _state.value.mediaItem ?: return
        mi.preferredAudioLanguage?.let { lang ->
            audioGroup?.let { g ->
                optionForLanguage(g, lang)?.let { playerItem.selectMediaOption(it, inMediaSelectionGroup = g) }
            }
        }
        val tg = textGroup ?: return
        when (mi.captionsDefault) {
            // OFF: hide captions. ON: the preferred language, else the group default. FOLLOW_SYSTEM:
            // leave AVPlayer's automatic selection (it already honours the OS caption setting), but
            // still bias to a requested language if one resolves.
            CaptionsDefaultMode.OFF -> playerItem.selectMediaOption(null, inMediaSelectionGroup = tg)
            CaptionsDefaultMode.ON ->
                playerItem.selectMediaOption(
                    mi.preferredTextLanguage?.let { optionForLanguage(tg, it) }
                        ?: (tg.options?.firstOrNull() as? AVMediaSelectionOption),
                    inMediaSelectionGroup = tg,
                )
            CaptionsDefaultMode.FOLLOW_SYSTEM ->
                mi.preferredTextLanguage?.let { optionForLanguage(tg, it) }
                    ?.let { playerItem.selectMediaOption(it, inMediaSelectionGroup = tg) }
        }
    }

    /**
     * Whether two lower-cased BCP-47 tags name the same language, tolerating a region or variant on
     * either side — `en` matches `en-GB`, and `en-GB` matches `en`. Both are compared as prefixes so
     * neither the track nor the request has to be the more specific one.
     */
    private fun languageTagMatches(tag: String, want: String): Boolean =
        tag == want || tag.startsWith("$want-") || want.startsWith("$tag-")

    /** First selection option in [group] whose BCP-47 language tag matches [bcp47] (prefix-tolerant). */
    private fun optionForLanguage(group: AVMediaSelectionGroup, bcp47: String): AVMediaSelectionOption? {
        val want = bcp47.lowercase()
        return (group.options ?: emptyList<Any?>()).firstNotNullOfOrNull { any ->
            val opt = any as? AVMediaSelectionOption ?: return@firstNotNullOfOrNull null
            val tag = opt.extendedLanguageTag?.lowercase()
            if (tag != null && languageTagMatches(tag, want)) opt else null
        }
    }

    // ---- state reconciliation (polled on the main queue) ----

    // Maps the whole of AVPlayer/AVPlayerItem state onto one PlayerState in a single pass: status,
    // rate, buffering, seekable/loaded ranges, tracks and errors. The branch count mirrors the number
    // of native properties, and reading them together is what makes the snapshot coherent.
    //
    // This suppression is a stopgap, not a claim that the function is irreducible. Three contiguous
    // blocks — the stall self-heal, the lazy track load, and the derived-event emission — are pure
    // code motion into private methods and would bring this well under the threshold. What is missing
    // is a way to catch a regression: there is no iosTest source set and no CI, and a mistake here
    // shows up as state flapping or unpaired BufferingStarted/Ended rather than a compile error.
    // ShakaEngine.reconcile is deliberately the line-for-line mirror of this one, so refactor both
    // together or not at all.
    @Suppress("CyclomaticComplexMethod")
    private fun reconcile() {
        val avPlayer = player ?: return
        val item = avPlayer.currentItem

        val posSeconds = CMTimeGetSeconds(avPlayer.currentTime())
        val durSeconds = item?.let { CMTimeGetSeconds(it.duration) }
        val durationMs = durSeconds?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 }
            ?.let { (it * 1000).toLong() }

        val itemReady = item != null && item.status == AVPlayerItemStatusReadyToPlay
        val itemFailed = item != null && item.status == AVPlayerItemStatusFailed
        // Only treat a drained look-ahead buffer as buffering when we actually intend to play —
        // otherwise a deliberately-paused (rate 0) ready item flaps to BUFFERING.
        val buffering = avPlayer.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate ||
            (playWhenReady && item?.playbackBufferEmpty == true)
        val playing = avPlayer.timeControlStatus == AVPlayerTimeControlStatusPlaying

        // Surface a load/decode failure (bad URL, 404, unsupported codec) — these set status==Failed
        // and never fire the FailedToPlayToEndTime notification.
        if (itemFailed && !errorEmitted) {
            val nsError = item?.error
            emitErrorOnce(
                PlayerError(
                    category = PlayerError.Category.SOURCE,
                    code = nsError?.code?.toString(),
                    message = describeError(nsError) ?: "Playback failed",
                    nativeCause = nsError,
                ),
            )
            return
        }

        val playbackState = when {
            item == null || itemFailed -> PlaybackState.IDLE
            endedFlag -> PlaybackState.ENDED
            buffering -> PlaybackState.BUFFERING
            itemReady -> PlaybackState.READY
            else -> PlaybackState.BUFFERING
        }

        val positionMs =
            if (posSeconds.isNaN() || posSeconds < 0) _state.value.positionMs else (posSeconds * 1000).toLong()

        _state.update {
            it.copy(
                playbackState = playbackState,
                isPlaying = playing,
                positionMs = positionMs,
                bufferedPositionMs = positionMs, // v1 approximation; precise buffered TBD
                durationMs = durationMs,
                // Live ⟺ READY with an indefinite duration AND a seekable window. A ready VOD resolves a
                // finite duration; a live stream's stays indefinite but exposes a sliding seekable range.
                // Requiring the seekable range too closes the brief startup window where an HLS VOD is
                // already READY but its duration hasn't loaded yet (it has no seekable range there either),
                // which would otherwise flicker isLive=true for a tick.
                isLive = itemReady && durationMs == null && item?.seekableTimeRanges?.isNotEmpty() == true,
                isSeekable = itemReady,
            )
        }

        // Apply a requested start position once the item is first ready, then clear it (resume / deep-link).
        if (itemReady && startSeekTargetMs != null) {
            val target = startSeekTargetMs!!
            startSeekTargetMs = null
            seekTo(target)
        }

        // Load audio/subtitle selection groups once they're actually available (retry while ready
        // but the groups haven't loaded; give up after a few ticks if the asset declares none).
        if (itemReady && !tracksLoaded && item != null) {
            tracksLoadAttempts++
            val hasGroups =
                item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible) != null ||
                    item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible) != null
            if (hasGroups || tracksLoadAttempts >= 8) {
                tracksLoaded = true
                loadTracks(item)
            }
        }

        // Derived events.
        if (itemReady && !firstFrameEmitted) {
            firstFrameEmitted = true
            emit { p, t -> PlayerEvent.FirstFrameRendered(p, t) }
        }
        if (buffering && !wasBuffering) emit { p, t -> PlayerEvent.BufferingStarted(p, t) }
        if (!buffering && wasBuffering) emit { p, t -> PlayerEvent.BufferingEnded(p, t) }
        wasBuffering = buffering

        // Stall self-heal: if we've been buffering while intending to play for too long, nudge AVPlayer
        // (re-seek to the current time forces a fresh buffering attempt). Cooldown-guarded so it can't
        // loop. iOS-only — Android/Web engines self-recover from stalls natively.
        if (buffering && playWhenReady) {
            val now = monotonicMillis()
            if (stallStartedElapsedMs == 0L) {
                stallStartedElapsedMs = now
            } else if (now - stallStartedElapsedMs >= STALL_RECOVERY_AFTER_MS &&
                now - lastStallRecoveryElapsedMs >= STALL_RECOVERY_COOLDOWN_MS
            ) {
                lastStallRecoveryElapsedMs = now
                stallStartedElapsedMs = 0L
                val zero = CMTimeMake(0, 1)
                avPlayer.seekToTime(avPlayer.currentTime(), toleranceBefore = zero, toleranceAfter = zero) { _ -> }
                emit { p, t -> PlayerEvent.PlaybackRecovered(RecoveryReason.STALL, p, t) }
            }
        } else {
            stallStartedElapsedMs = 0L
        }

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

    private inline fun emit(make: (positionMs: Long, elapsedRealtimeMs: Long) -> PlayerEvent) {
        _events.tryEmit(make(_state.value.positionMs, monotonicMillis()))
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread()) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) { block() }
        }
    }

    /**
     * Put the shared audio session into the `playback` category so media audio plays through the
     * hardware mute switch and isn't treated as transient/ambient — the iOS counterpart of the audio
     * handling Media3 gives us on Android. Best-effort: errors (e.g. another app holding exclusive
     * audio) are ignored rather than failing player construction.
     */
    private fun activateAudioSession() {
        val session = AVAudioSession.sharedInstance()
        // mixWithOthers plays alongside other apps' audio instead of interrupting it.
        if (config.audioConfig?.mixWithOthers == true) {
            session.setCategory(
                AVAudioSessionCategoryPlayback,
                withOptions = AVAudioSessionCategoryOptionMixWithOthers,
                error = null,
            )
        } else {
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        }
        session.setActive(true, error = null)
    }
}

/**
 * Push client-side-ad (CSAI) state into [player]'s [PlayerState.adState], or `null` to clear it once the ad
 * break ends. Called by an iOS IMA adapter that owns the Google IMA SDK: that SDK is UIView/
 * UIViewController-centric and pulled in via CocoaPods/cinterop, so it lives in the integration layer (the
 * app or a dedicated module) rather than the pod-free core — this bridge is how it drives the shared chrome,
 * the iOS parallel of Android's `VideoPlayer.setAdViewProvider`. The coordinator pauses/resumes the content
 * player itself (IMA iOS' content pause/resume model). A no-op for any [VideoPlayer] that isn't the AVPlayer engine.
 */
fun VideoPlayer.updateAdState(adState: AdState?) {
    (this as? AVPlayerEngine)?.setAdState(adState)
}

/**
 * Register the iOS ad-container surface — the `AVPlayerViewController` created by the `:ui` PlayerSurface —
 * with the engine, or `null` to detach it. An iOS IMA adapter reads it back via [adContainer] to anchor
 * IMA's ad UI (`IMAAdDisplayContainer`: its view + presenting view controller) over the video. The iOS
 * parallel of Android's `VideoPlayer.setAdViewProvider`; a no-op for any non-AVPlayer [VideoPlayer].
 */
fun VideoPlayer.setAdContainer(controller: UIViewController?) {
    (this as? AVPlayerEngine)?.setAdContainer(controller)
}

/** The registered ad-container view controller (see [setAdContainer]), or `null`. Used by the iOS IMA adapter. */
fun VideoPlayer.adContainer(): UIViewController? = (this as? AVPlayerEngine)?.adContainer()

/** A [ChapterReader] over a local file via POSIX read/lseek, so the shared [ChapterExtractor] runs on iOS too. */
private class PosixChapterReader(
    private val fd: Int,
    override val size: Long,
) : ChapterReader {
    override fun readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset >= size || length <= 0) return ByteArray(0)
        val n = minOf(length.toLong(), size - offset).toInt()
        lseek(fd, offset, SEEK_SET)
        val out = ByteArray(n)
        val got = out.usePinned { pinned -> read(fd, pinned.addressOf(0), n.toULong()) }
        return when {
            got <= 0L -> ByteArray(0)
            got.toInt() < n -> out.copyOf(got.toInt())
            else -> out
        }
    }
}

private const val STALL_RECOVERY_AFTER_MS = 20_000L // buffer this long (while intending to play) before nudging
private const val STALL_RECOVERY_COOLDOWN_MS = 15_000L // minimum gap between stall nudges

/** Monotonic clock in ms for event timing (no wall-clock meaning). */
private fun monotonicMillis(): Long = (CACurrentMediaTime() * 1000).toLong()
