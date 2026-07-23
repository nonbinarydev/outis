/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import android.content.Context
import android.media.MediaDrm
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.accessibility.CaptioningManager
import android.widget.FrameLayout
import androidx.media3.common.AdOverlayInfo
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.ima.ImaAdsLoader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import dev.nonbinary.outis.core.ads.Ad
import dev.nonbinary.outis.core.ads.AdConfig
import dev.nonbinary.outis.core.ads.AdState
import dev.nonbinary.outis.core.chapters.ChapterExtractor
import dev.nonbinary.outis.core.chapters.ChapterReader
import dev.nonbinary.outis.core.plugin.PlayerComponent
import dev.nonbinary.outis.core.plugin.PlayerHost
import dev.nonbinary.outis.core.source.CaptionsDefaultMode
import dev.nonbinary.outis.core.source.DrmConfig
import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.LicenseRequest
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.core.source.WidevineLevel
import dev.nonbinary.outis.core.track.MediaTrack
import dev.nonbinary.outis.core.track.TrackType
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.source.MediaSource as ExoMediaSource
import com.google.ads.interactivemedia.v3.api.AdEvent as ImaAdEvent

/**
 * Android [VideoPlayer] backed by Media3 / ExoPlayer.
 *
 * Owns one ExoPlayer, registers a single [Player.Listener] (→ state) and one [AnalyticsListener]
 * (→ QoS events), runs one position ticker, and exposes the raw player via [nativePlayerHandle].
 *
 * **Threading.** ExoPlayer is pinned to the main [Looper] and Media3 *throws* on access from any
 * other thread. So:
 * - The engine MUST be constructed on the main thread (asserted in `init`).
 * - Every public transport call is marshalled to main via [onPlayerThread]; the listeners and the
 *   ticker already run there. Hence the `state`/`events` producers are effectively single-threaded.
 * - [emit] never touches the player (it reads the last-known position from [state]), so it is safe
 *   to call from any of those main-thread sites without a wrong-thread crash.
 *
 * The cross-thread guard flags ([player], [released]) are `@Volatile` so a background-thread
 * [release]/transport call publishes visibly to the main-thread ticker and posted tasks.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class ExoPlayerEngine(
    context: Context,
    private val config: PlayerConfig,
    /**
     * Where blocking container parsing runs. Injected rather than hardcoded so a test can pass a
     * deterministic dispatcher; the default is the only value production uses.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : VideoPlayer {

    private val _state = MutableStateFlow(PlayerState(volume = config.initialVolume))
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(replay = 0, extraBufferCapacity = 64)
    override val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    private val _native = MutableStateFlow<Any?>(null)
    override val nativePlayerHandle: Any? get() = _native.value

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captioningManager = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager

    // id -> native track override, rebuilt on every onTracksChanged. Main-thread only.
    private val trackOverrides = mutableMapOf<String, TrackSelectionOverride>()

    @Volatile private var player: ExoPlayer? = null

    @Volatile private var released = false

    // ---- Client-side ad insertion (IMA/CSAI). The loader is created lazily on the first ClientSide item;
    // the ad surface (the :ui Media3 PlayerView, itself an AdViewProvider) is pushed in via
    // setAdViewProvider. IMA owns ad playback + its skip/countdown UI; we mirror its lifecycle into
    // PlayerState.adState so the chrome blocks seeking during ads. Main-thread only. ----
    @Volatile private var imaAdsLoader: ImaAdsLoader? = null

    @Volatile private var imaPlayerBound = false
    private val adViewProvider = DelegatingAdViewProvider()
    private val imaAdEventListener = ImaAdEvent.AdEventListener { onImaAdEvent(it) }

    // Main-thread-only state.
    private var lastPlaybackState = Player.STATE_IDLE
    private var lastUnmutedVolume = config.initialVolume

    private val componentsLock = Any()
    private val components = mutableListOf<PlayerComponent>()
    private val host = object : PlayerHost {
        override val state: StateFlow<PlayerState> get() = this@ExoPlayerEngine.state
        override val events: SharedFlow<PlayerEvent> get() = this@ExoPlayerEngine.events
        override val scope: CoroutineScope get() = this@ExoPlayerEngine.scope
        override val nativePlayerHandle: StateFlow<Any?> get() = _native
    }

    // ---- transport (all marshalled to the player thread) ----

    override fun setMediaItem(item: MediaItem, autoPlay: Boolean) = onPlayerThread { exo ->
        trackOverrides.clear()
        // Reset every item-scoped field so the new item never shows the previous item's
        // duration/liveness/seekability/size/tracks (re-derived once the new timeline resolves).
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
                isMuted = item.startMuted || it.isMuted,
                playbackState = PlaybackState.BUFFERING,
                audioTracks = persistentListOf(),
                textTracks = persistentListOf(),
                selectedAudioTrackId = null,
                selectedTextTrackId = null,
                chapters = persistentListOf(),
            )
        }
        // Media3 surfaces no chapter API, so parse embedded chapters from the local container off-thread
        // (mp4/m4v QuickTime+Nero, mkv Matroska) and publish them to PlayerState. Remote sources skip this.
        extractChaptersAsync(item)
        // Fail fast on a DRM scheme this device can't satisfy, rather than handing ExoPlayer an
        // undecryptable source that limps along as black/silent playback. FairPlay is Apple-only (no
        // Android CDM at all); Widevine/PlayReady need a device CDM that many phones lack.
        unsupportedDrmLabel(item.drmConfig)?.let { scheme ->
            exo.stop()
            exo.clearMediaItems()
            val drmError = PlayerError(PlayerError.Category.DRM, message = "$scheme DRM isn't supported on this device")
            _state.update { it.copy(error = drmError, playbackState = PlaybackState.IDLE, isPlaying = false) }
            emit { p, t -> PlayerEvent.FatalError(drmError, p, t) }
            return@onPlayerThread
        }
        // Per-item video ceiling: cap ABR (or clear the cap for an unconstrained item).
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().run {
            val c = item.videoConstraints
            if (c?.maxWidth != null && c.maxHeight != null) {
                setMaxVideoSize(c.maxWidth, c.maxHeight)
            } else {
                clearVideoSizeConstraints()
            }
            setMaxVideoBitrate(c?.maxBitrateBps ?: Int.MAX_VALUE)
            // Load-time language preferences. buildUpon() inherits the prior item's params, so we MUST
            // pass these every item (null included) — Media3's setPreferred*Language(null) is what clears
            // a previous preference.
            setPreferredAudioLanguage(item.preferredAudioLanguage)
            setPreferredTextLanguage(item.preferredTextLanguage)
            // Initial caption visibility: disable the TEXT track type unless captions should show.
            val captionsOn = when (item.captionsDefault) {
                CaptionsDefaultMode.ON -> true
                CaptionsDefaultMode.OFF -> false
                CaptionsDefaultMode.FOLLOW_SYSTEM -> captioningManager?.isEnabled == true
            }
            setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsOn)
            build()
        }
        // Per-item MediaSource so request headers + retry/timeouts belong to THIS item, not a shared
        // factory. Start position (resume / deep-link); C.TIME_UNSET starts at the default position.
        // Bind the IMA loader to the player BEFORE its ad media source is set (Media3's canonical order).
        if (item.adConfig is AdConfig.ClientSide) bindImaAdsLoader(exo)
        exo.setMediaSource(buildMediaSource(item), item.startPositionMs ?: C.TIME_UNSET)
        exo.playWhenReady = autoPlay
        exo.repeatMode = if (item.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exo.prepare()
        // startMuted is already reflected in isMuted above (forces mute on, never unmutes); mirror it on
        // the player. A previously-muted player keeps volume 0 across items, so no else-branch is needed.
        if (item.startMuted) exo.volume = 0f
    }

    /**
     * Build a MediaSource for [item] with a **per-item** HTTP stack — request headers, timeouts and
     * cross-protocol redirects belong to this item, not a shared/global factory, so they stay correct
     * once a playlist/preload API queues items with different headers. DRM is handled by
     * [DefaultMediaSourceFactory] from the item's `DrmConfiguration` (incl. its license-request headers).
     */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun buildMediaSource(item: MediaItem): ExoMediaSource {
        val httpFactory = DefaultHttpDataSource.Factory()
        if (item.headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(item.headers)
        config.retryConfig?.let { r ->
            httpFactory
                .setConnectTimeoutMs(r.connectTimeoutMs)
                .setReadTimeoutMs(r.readTimeoutMs)
                .setAllowCrossProtocolRedirects(r.allowCrossProtocolRedirects)
        }
        val factory = DefaultMediaSourceFactory(httpFactory)
        config.retryConfig?.let { factory.setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(it.maxRetries)) }
        // An interceptor (templated URL, challenge-in-query, wrapped response) or a forced Widevine L3
        // level can't be expressed by the declarative DrmConfiguration — drive those with a custom
        // DrmSessionManagerProvider, which overrides the MediaItem's DrmConfiguration when set.
        item.drmConfig?.customDrmSessionManagerProvider()?.let { factory.setDrmSessionManagerProvider(it) }
        // Client-side ad insertion: hand the IMA ads loader + the ad-overlay surface to the factory. The
        // ad tag itself rides on the MediaItem's AdsConfiguration (see toExoMediaItem).
        if (item.adConfig is AdConfig.ClientSide) {
            factory.setLocalAdInsertionComponents({ checkNotNull(imaAdsLoader) }, adViewProvider)
        }
        return factory.createMediaSource(item.toExoMediaItem(config.liveConfig))
    }

    override fun play() = onPlayerThread { it.play() }

    override fun pause() = onPlayerThread { it.pause() }

    override fun seekTo(positionMs: Long) = onPlayerThread { exo ->
        val preSeek = exo.currentPosition
        // Stamp positionMs with the true pre-seek position (not a ≤250ms-stale ticker sample) so
        // SeekStarted is accurate, and latch the target for the scrubber.
        _state.update { it.copy(positionMs = preSeek, pendingSeekTargetMs = positionMs) }
        emit { p, t -> PlayerEvent.SeekStarted(positionMs, p, t) }
        exo.seekTo(positionMs)
        // A no-op seek (player IDLE, or position unchanged after clamping) produces no further
        // callbacks, so resolve it now. A real seek resolves only once the player is next READY —
        // i.e. actually settled at the target after any re-buffer — NOT at request time (ExoPlayer
        // masks currentPosition to the target synchronously, so resolving here would be premature).
        if (exo.playbackState == Player.STATE_IDLE || exo.currentPosition == preSeek) resolveSeek()
    }

    override fun setPlaybackSpeed(speed: Float) = onPlayerThread { it.setPlaybackSpeed(speed) }

    override fun setVolume(volume: Float) = onPlayerThread { exo ->
        // Volume and mute are independent: changing volume never flips the mute toggle.
        lastUnmutedVolume = volume
        if (!_state.value.isMuted) exo.volume = volume
        _state.update { it.copy(volume = volume) }
    }

    override fun setMuted(muted: Boolean) = onPlayerThread { exo ->
        exo.volume = if (muted) 0f else lastUnmutedVolume
        _state.update { it.copy(isMuted = muted) }
    }

    override fun stop() = onPlayerThread { exo ->
        exo.stop()
        exo.clearMediaItems()
        trackOverrides.clear()
        _state.update {
            it.copy(
                playbackState = PlaybackState.IDLE,
                isPlaying = false,
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

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    override fun release() {
        if (released) return
        released = true
        val teardown = Runnable {
            // Detach components while the scope is still alive (matches removeComponent), then cancel.
            val snapshot = synchronized(componentsLock) {
                val copy = components.toList()
                components.clear()
                copy
            }
            snapshot.forEach { it.detach() }
            imaAdsLoader?.let {
                it.setPlayer(null)
                it.release()
            }
            imaAdsLoader = null
            imaPlayerBound = false
            adViewProvider.delegate = null
            player?.let { exo ->
                exo.removeListener(playerListener)
                exo.removeAnalyticsListener(analyticsListener)
                exo.release()
            }
            player = null
            _native.value = null
            scope.cancel()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) teardown.run() else mainHandler.post(teardown)
    }

    override fun addComponent(component: PlayerComponent) {
        synchronized(componentsLock) {
            if (released) return
            components += component
        }
        component.attach(host)
    }

    override fun removeComponent(component: PlayerComponent) {
        val present = synchronized(componentsLock) { components.remove(component) }
        if (present) component.detach()
    }

    // detekt's UnreachableCode fires on the elvis below when run with type resolution. It is wrong:
    // the flagged line is the FIRST statement in the lambda, so nothing can precede it. The cause is
    // TrackSelectionOverride being a Media3 (Java) platform type — the analysis treats the map lookup
    // as non-null and concludes the `?: return` branch is dead. Track selection works on device.
    @Suppress("UnreachableCode")
    override fun selectTrack(track: MediaTrack) = onPlayerThread { exo ->
        val override = trackOverrides[track.id] ?: return@onPlayerThread
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            // Re-enable the TEXT type FIRST if subtitles were previously off (clearTextTrack or
            // captionsDefault=OFF), so the override below actually takes effect.
            .apply { if (track.type == TrackType.TEXT) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false) }
            .setOverrideForType(override)
            .build()
        // Selection is not optimistic: onTracksChanged re-emits with the new isSelected flags.
    }

    override fun clearTextTrack() = onPlayerThread { exo ->
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    // ---- listeners (dispatched by Media3 on the main Looper) ----

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            syncState()
            when (playbackState) {
                Player.STATE_BUFFERING -> emit { p, t -> PlayerEvent.BufferingStarted(p, t) }
                Player.STATE_READY ->
                    if (lastPlaybackState == Player.STATE_BUFFERING) emit { p, t -> PlayerEvent.BufferingEnded(p, t) }
                Player.STATE_ENDED -> emit { p, t -> PlayerEvent.Ended(p, t) }
            }
            emit { p, t -> PlayerEvent.PlaybackStateChanged(mapPlaybackState(playbackState), p, t) }
            // A pending seek has settled once the player reaches READY again (after any re-buffer).
            if (playbackState == Player.STATE_READY) resolveSeek()
            lastPlaybackState = playbackState
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncState()
            emit { p, t -> PlayerEvent.IsPlayingChanged(isPlaying, p, t) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncState()

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = syncState()

        // setPlaybackSpeed goes straight to ExoPlayer and does not touch _state, so without this the
        // speed ExoPlayer is actually using never reaches PlayerState. syncState() already reads
        // playbackParameters.speed; nothing was calling it. Playback changed speed correctly while the
        // controls stayed pinned to the last synced value — and a seek would belatedly correct them,
        // because resolveSeek() is the only other path that syncs.
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) =
            syncState()

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // Refresh position promptly, but DON'T complete the seek here: the SEEK discontinuity is
            // dispatched at request time (position masked to the target before re-buffering). The
            // seek completes on the next READY (onPlaybackStateChanged / the ticker), once settled.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) syncState()
        }

        override fun onMediaItemTransition(mediaItem: ExoMediaItem?, reason: Int) {
            // v1 is single-item, so the loaded item is whatever the app last set via setMediaItem.
            syncState()
            emit { p, t -> PlayerEvent.MediaItemTransition(_state.value.mediaItem, p, t) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val exo = player
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && exo != null) {
                // Live fell behind its window — re-snap to the live edge and re-prepare instead of
                // surfacing a fatal error (Arc's pattern). Playback continues; report it as recovered.
                exo.seekToDefaultPosition()
                exo.prepare()
                emit { p, t -> PlayerEvent.PlaybackRecovered(RecoveryReason.BEHIND_LIVE_WINDOW, p, t) }
                return
            }
            val mapped = error.toPlayerError()
            // Make the error-bearing snapshot self-consistent without depending on callback ordering.
            _state.update { it.copy(error = mapped, playbackState = PlaybackState.IDLE, isPlaying = false) }
            emit { p, t -> PlayerEvent.FatalError(mapped, p, t) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            trackOverrides.clear()
            val audio = mutableListOf<MediaTrack>()
            val text = mutableListOf<MediaTrack>()
            tracks.groups.forEachIndexed { groupIndex, group ->
                val type = when (group.type) {
                    C.TRACK_TYPE_AUDIO -> TrackType.AUDIO
                    C.TRACK_TYPE_TEXT -> TrackType.TEXT
                    else -> return@forEachIndexed
                }
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue // drop unplayable tracks
                    val format = group.getTrackFormat(i)
                    val id = "${type.name}:$groupIndex:$i"
                    trackOverrides[id] = TrackSelectionOverride(group.mediaTrackGroup, i)
                    val track = MediaTrack(
                        id = id,
                        type = type,
                        label = format.label,
                        language = format.language,
                        isSelected = group.isTrackSelected(i),
                        isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    )
                    (if (type == TrackType.AUDIO) audio else text).add(track)
                }
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
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) =
            emit { p, t -> PlayerEvent.FirstFrameRendered(p, t) }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) = emit { p, t -> PlayerEvent.BandwidthSample(bitrateEstimate, p, t) }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) =
            emit { p, t -> PlayerEvent.DroppedFrames(droppedFrames, p, t) }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) = emit { p, t -> PlayerEvent.BitrateChanged(format.toVideoFormat(), p, t) }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ExoPlayerEngine must be constructed on the main thread (ExoPlayer is pinned to the main Looper)."
        }
        val exo = buildExoPlayer(context, config)
        exo.addListener(playerListener)
        exo.addAnalyticsListener(analyticsListener)
        player = exo
        _native.value = exo
        lastPlaybackState = exo.playbackState
        config.components.forEach(::addComponent)
        startTicker()
        emit { p, t -> PlayerEvent.NativePlayerAttached(exo, p, t) }
    }

    // ---- internals (all main-thread) ----

    private fun startTicker() {
        scope.launch {
            while (isActive) {
                val exo = player
                if (!released && exo != null) {
                    // Skip while an IMA ad plays — currentPosition is AD time then, but positionMs is content time.
                    if (exo.playbackState != Player.STATE_IDLE && !exo.isPlayingAd) {
                        _state.update {
                            it.copy(
                                positionMs = exo.currentPosition,
                                bufferedPositionMs = exo.bufferedPosition
                            )
                        }
                    }
                    // Resolve a pending seek once the player is READY again (settled). Covers
                    // in-buffer seeks that trigger no state-change callback; position-agnostic so a
                    // clamped seek still resolves. READY ⇒ buffered at the (post-seek) position.
                    if (_state.value.pendingSeekTargetMs != null && exo.playbackState == Player.STATE_READY) {
                        resolveSeek()
                    }
                }
                delay(config.positionPollIntervalMs.milliseconds)
            }
        }
    }

    /** Clear an in-flight seek and announce completion. Idempotent: no-op when nothing is pending. */
    private fun resolveSeek() {
        if (_state.value.pendingSeekTargetMs == null) return
        _state.update { it.copy(pendingSeekTargetMs = null) }
        syncState()
        emit { p, t -> PlayerEvent.SeekCompleted(p, t) }
    }

    /** Rebuild the player-derived fields from the current ExoPlayer snapshot; our own fields are preserved. */
    private fun syncState() {
        val exo = player ?: return
        val idle = exo.playbackState == Player.STATE_IDLE
        // During an IMA ad, currentPosition reports AD time; keep the last content position instead.
        val holdPosition = idle || exo.isPlayingAd
        _state.update { prev ->
            prev.copy(
                playbackState = mapPlaybackState(exo.playbackState),
                isPlaying = exo.isPlaying,
                playWhenReady = exo.playWhenReady,
                positionMs = if (holdPosition) prev.positionMs else exo.currentPosition,
                bufferedPositionMs = if (holdPosition) prev.bufferedPositionMs else exo.bufferedPosition,
                durationMs = exo.duration.takeIf { it != C.TIME_UNSET },
                isLive = exo.isCurrentMediaItemLive,
                isSeekable = exo.isCurrentMediaItemSeekable,
                playbackSpeed = exo.playbackParameters.speed,
                videoSize = exo.videoSize.let {
                    if (it.width > 0 && it.height > 0) VideoSize(it.width, it.height) else prev.videoSize
                },
            )
        }
    }

    /** Stamp an event with the last-known content position + a monotonic clock. Never touches the player. */
    private inline fun emit(make: (positionMs: Long, elapsedRealtimeMs: Long) -> PlayerEvent) {
        _events.tryEmit(make(_state.value.positionMs, SystemClock.elapsedRealtime()))
    }

    private fun onPlayerThread(block: (ExoPlayer) -> Unit) {
        if (released) return
        val task = Runnable { if (!released) player?.let(block) }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else mainHandler.post(task)
    }

    // ---- chapters (Media3 has no chapter API; parse the local container ourselves) ----

    /** Parse embedded chapters from a local file off-thread and publish them — no-op for remote sources. */
    private fun extractChaptersAsync(item: MediaItem) {
        val path = localFilePath(item) ?: return
        scope.launch(ioDispatcher) {
            val chapters = runCatching {
                RandomAccessFile(File(path), "r").use { raf ->
                    ChapterExtractor.extract(
                        object : ChapterReader {
                            override val size: Long get() = raf.length()
                            override fun readAt(offset: Long, length: Int): ByteArray {
                                val total = raf.length()
                                if (offset < 0 || offset >= total || length <= 0) return ByteArray(0)
                                val n = minOf(length.toLong(), total - offset).toInt()
                                val buf = ByteArray(n)
                                raf.seek(offset)
                                raf.readFully(buf)
                                return buf
                            }
                        },
                        includeThumbnails = item.chapterThumbnails
                    )
                }
            }.getOrDefault(emptyList())
            if (chapters.isEmpty() || released) return@launch
            // Only apply if this is still the current item (a newer setMediaItem must not be clobbered).
            _state.update { if (it.mediaItem === item) it.copy(chapters = chapters.toPersistentList()) else it }
        }
    }

    private fun localFilePath(item: MediaItem): String? = when (val s = item.source) {
        is MediaSource.LocalFile -> s.path
        is MediaSource.Url -> if (s.url.startsWith("file://")) s.url.removePrefix("file://") else null
    }

    // ---- IMA / CSAI ----

    /** Lazily build the per-engine IMA ads loader (once) and bind it to [exo] (once). */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun bindImaAdsLoader(exo: ExoPlayer) {
        val loader = imaAdsLoader ?: ImaAdsLoader.Builder(appContext)
            .setAdEventListener(imaAdEventListener)
            .setAdErrorListener { Log.w(IMA_TAG, "ad error: ${it.error.errorCodeNumber} ${it.error.message}") }
            .build()
            .also { imaAdsLoader = it }
        if (!imaPlayerBound) {
            loader.setPlayer(exo)
            imaPlayerBound = true
        }
    }

    /** Push the :ui ad-overlay surface (the Media3 PlayerView) into the loader, or null to detach it. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    internal fun setAdViewProvider(provider: AdViewProvider?) {
        adViewProvider.delegate = provider
    }

    /** Mirror IMA's ad lifecycle into [PlayerState.adState] so the chrome can block seeking during ads. */
    private fun onImaAdEvent(event: ImaAdEvent) {
        when (event.type) {
            ImaAdEvent.AdEventType.CONTENT_PAUSE_REQUESTED ->
                _state.update { it.copy(adState = AdState(isInAdBreak = true)) }
            ImaAdEvent.AdEventType.STARTED ->
                _state.update { it.copy(adState = imaAdState(event)) }
            ImaAdEvent.AdEventType.CONTENT_RESUME_REQUESTED, ImaAdEvent.AdEventType.ALL_ADS_COMPLETED ->
                _state.update { it.copy(adState = null) }
            else -> Unit
        }
    }

    private fun imaAdState(event: ImaAdEvent): AdState {
        val ad = event.ad
        val pod = ad?.adPodInfo
        val current = ad?.let {
            Ad(
                id = it.adId ?: "",
                durationMs = (it.duration * 1000).toLong(),
                title = it.title,
                skipOffsetMs = it.skipTimeOffset.takeIf { offset -> offset >= 0.0 }
                    ?.let { offset -> (offset * 1000).toLong() },
            )
        }
        return AdState(
            isInAdBreak = true,
            currentAd = current,
            adIndexInBreak = (pod?.adPosition ?: 1) - 1,
            adCountInBreak = pod?.totalAds ?: 0,
            adRemainingMs = current?.durationMs ?: 0,
        )
    }

    /** An [AdViewProvider] that forwards to the surface's PlayerView once attached; a no-op until then. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private inner class DelegatingAdViewProvider : AdViewProvider {
        @Volatile var delegate: AdViewProvider? = null
        private val empty by lazy { FrameLayout(appContext) }
        override fun getAdViewGroup(): ViewGroup = delegate?.adViewGroup ?: empty
        override fun getAdOverlayInfos(): List<AdOverlayInfo> = delegate?.adOverlayInfos ?: emptyList()
    }
}

/**
 * Hand the ad-overlay surface — the Media3 `PlayerView`, which implements [AdViewProvider] — to the
 * player's IMA ad loader, or `null` to detach it. Called by the `:ui` Android surface; a no-op
 * for any [VideoPlayer] that isn't the Media3 engine.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
fun VideoPlayer.setAdViewProvider(provider: AdViewProvider?) {
    (this as? ExoPlayerEngine)?.setAdViewProvider(provider)
}

private const val IMA_TAG = "PlayerIMA"

private fun mapPlaybackState(state: Int): PlaybackState = when (state) {
    Player.STATE_IDLE -> PlaybackState.IDLE
    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
    Player.STATE_READY -> PlaybackState.READY
    Player.STATE_ENDED -> PlaybackState.ENDED
    else -> PlaybackState.IDLE
}

// A flat translation of optional MediaItem fields onto the Media3 builder. Every branch is an
// independent "if the caller set this, set that" — the count reflects how many fields exist.
@Suppress("CyclomaticComplexMethod")
private fun MediaItem.toExoMediaItem(liveConfig: LiveConfig?): ExoMediaItem {
    val uri = when (val s = source) {
        is MediaSource.Url -> s.url
        is MediaSource.LocalFile -> s.path
    }
    return ExoMediaItem.Builder()
        .setUri(uri)
        .apply { mimeType?.let { setMimeType(it.toExoMimeType()) } }
        .apply { drmConfig?.toExoDrmConfiguration()?.let { setDrmConfiguration(it) } }
        .apply {
            (adConfig as? AdConfig.ClientSide)?.let {
                setAdsConfiguration(ExoMediaItem.AdsConfiguration.Builder(Uri.parse(it.adTagUri)).build())
            }
        }
        .apply {
            liveConfig?.let { lc ->
                setLiveConfiguration(
                    ExoMediaItem.LiveConfiguration.Builder()
                        .apply { lc.targetOffsetMs?.let { setTargetOffsetMs(it) } }
                        .apply { lc.minPlaybackSpeed?.let { setMinPlaybackSpeed(it) } }
                        .apply { lc.maxPlaybackSpeed?.let { setMaxPlaybackSpeed(it) } }
                        .build(),
                )
            }
        }
        .build()
}

private fun MimeType.toExoMimeType(): String = when (this) {
    MimeType.MP4 -> MimeTypes.VIDEO_MP4
    MimeType.HLS -> MimeTypes.APPLICATION_M3U8
    MimeType.DASH -> MimeTypes.APPLICATION_MPD
}

/**
 * Map [DrmConfig] onto a Media3 [ExoMediaItem.DrmConfiguration]; ExoPlayer's default media-source
 * factory turns it into a `DefaultDrmSessionManager` automatically. FairPlay is an Apple key system,
 * not an Android one, so it produces no configuration here.
 */
private fun DrmConfig.toExoDrmConfiguration(): ExoMediaItem.DrmConfiguration? {
    val uuid = when (scheme) {
        DrmScheme.WIDEVINE -> C.WIDEVINE_UUID
        DrmScheme.PLAYREADY -> C.PLAYREADY_UUID
        DrmScheme.FAIRPLAY -> return null
    }
    return ExoMediaItem.DrmConfiguration.Builder(uuid)
        .setLicenseUri(licenseServerUrl)
        .setLicenseRequestHeaders(licenseRequestHeaders)
        .setMultiSession(multiSession)
        .build()
}

/**
 * The display name of [drm]'s scheme if this device can't satisfy it (so the caller can fail fast with a
 * clear error), or null when it's playable. FairPlay has no Android CDM at all; Widevine and PlayReady
 * are queried against the platform via [MediaDrm.isCryptoSchemeSupported].
 */
private fun unsupportedDrmLabel(drm: DrmConfig?): String? = when (drm?.scheme) {
    null -> null
    DrmScheme.FAIRPLAY -> "FairPlay"
    DrmScheme.WIDEVINE -> if (MediaDrm.isCryptoSchemeSupported(C.WIDEVINE_UUID)) null else "Widevine"
    DrmScheme.PLAYREADY -> if (MediaDrm.isCryptoSchemeSupported(C.PLAYREADY_UUID)) null else "PlayReady"
}

/**
 * Build a [DrmSessionManagerProvider] when the declarative `DrmConfiguration` can't express what's
 * needed — i.e. a license request/response interceptor is set, **or** Widevine [WidevineLevel.L3] is
 * forced. Set on the per-item media-source factory it overrides the MediaItem's `DrmConfiguration`, so
 * once we take this path we own the whole exchange (license URL, headers, multiSession, CDM provider).
 * Returns null for FairPlay (no Android FPS) or when neither override is needed (the declarative path is
 * correct).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun DrmConfig.customDrmSessionManagerProvider(): DrmSessionManagerProvider? {
    val hasInterceptor = licenseRequestInterceptor != null || licenseResponseInterceptor != null
    // Only L3 is software-forceable; AUTO/L1 leave the CDM to negotiate (see exoMediaDrmProvider).
    val forcesLevel = scheme == DrmScheme.WIDEVINE && widevineLevel == WidevineLevel.L3
    if (!hasInterceptor && !forcesLevel) return null
    val uuid = when (scheme) {
        DrmScheme.WIDEVINE -> C.WIDEVINE_UUID
        DrmScheme.PLAYREADY -> C.PLAYREADY_UUID
        DrmScheme.FAIRPLAY -> return null
    }
    // With an interceptor we drive the license exchange ourselves; otherwise (L3-only) keep Media3's
    // stock HTTP callback so the exchange the declarative path used is preserved verbatim.
    val callback: MediaDrmCallback =
        if (hasInterceptor) {
            InterceptingMediaDrmCallback(
                licenseServerUrl,
                licenseRequestHeaders,
                licenseRequestInterceptor,
                licenseResponseInterceptor
            )
        } else {
            HttpMediaDrmCallback(licenseServerUrl, false, DefaultHttpDataSource.Factory())
                .apply { licenseRequestHeaders.forEach { (k, v) -> setKeyRequestProperty(k, v) } }
        }
    val sessionManager = DefaultDrmSessionManager.Builder()
        .setUuidAndExoMediaDrmProvider(uuid, exoMediaDrmProvider())
        .setMultiSession(multiSession)
        .build(callback)
    return DrmSessionManagerProvider { sessionManager }
}

/**
 * The [ExoMediaDrm.Provider] for this config. For Widevine [WidevineLevel.L3] it returns a provider that
 * forces `securityLevel=L3` on a fresh [FrameworkMediaDrm] before any session opens — the fix for older
 * devices whose L1 certificate has expired. Everything else (AUTO, L1, non-Widevine) uses the stock
 * provider: L1 can't be software-forced *up*, and `setPropertyString` on a non-Widevine plugin throws,
 * so we never touch it there. On failure (no CDM, property rejected) we fall back to a working CDM so
 * playback still attempts rather than hard-failing.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun DrmConfig.exoMediaDrmProvider(): ExoMediaDrm.Provider {
    if (scheme != DrmScheme.WIDEVINE || widevineLevel != WidevineLevel.L3) {
        return FrameworkMediaDrm.DEFAULT_PROVIDER
    }
    // newInstance directly (not DEFAULT_PROVIDER, which would hand back a DummyExoMediaDrm whose
    // setPropertyString silently no-ops, leaving L3 un-forced). Any failure falls back to the stock
    // provider: a missing CDM becomes a DummyExoMediaDrm (the same clean DRM error as the declarative
    // path), a rejected property an un-forced but working CDM. If newInstance succeeds but the property
    // set throws, release that instance first so its native MediaDrm handle isn't leaked.
    return ExoMediaDrm.Provider { uuid ->
        var created: FrameworkMediaDrm? = null
        try {
            val drm = FrameworkMediaDrm.newInstance(uuid)
            created = drm
            drm.setPropertyString("securityLevel", "L3")
            drm
        } catch (_: Exception) {
            created?.release()
            FrameworkMediaDrm.DEFAULT_PROVIDER.acquireExoMediaDrm(uuid)
        }
    }
}

/**
 * A Media3 [MediaDrmCallback] that runs the [DrmConfig] license interceptors: builds the key request from
 * the CDM challenge (default = POST the challenge to the license URL), POSTs it, then runs the response
 * through the response interceptor before handing the key to the CDM. Provisioning is the standard
 * signed-request POST. Used only when an interceptor is set; otherwise Media3's stock HttpMediaDrmCallback
 * runs from the declarative DrmConfiguration. `executeKeyRequest` runs on a background license thread, so
 * the blocking HTTP is fine.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private class InterceptingMediaDrmCallback(
    private val licenseUrl: String,
    private val defaultHeaders: Map<String, String>,
    private val requestInterceptor: ((LicenseRequest) -> LicenseRequest)?,
    private val responseInterceptor: ((ByteArray) -> ByteArray)?,
) : MediaDrmCallback {

    override fun executeProvisionRequest(uuid: UUID, request: ExoMediaDrm.ProvisionRequest): MediaDrmCallback.Response {
        val url = request.defaultUrl + "&signedRequest=" + String(request.data, Charsets.UTF_8)
        return MediaDrmCallback.Response.Builder(post(url, ByteArray(0), emptyMap())).build()
    }

    override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): MediaDrmCallback.Response {
        val initial = LicenseRequest(
            url = request.licenseServerUrl.takeIf { it.isNotEmpty() } ?: licenseUrl,
            body = request.data,
            headers = defaultHeaders,
        )
        val req = requestInterceptor?.invoke(initial) ?: initial
        val raw = post(req.url, req.body, req.headers)
        val license = responseInterceptor?.invoke(raw) ?: raw
        return MediaDrmCallback.Response.Builder(license).build()
    }

    private fun post(url: String, body: ByteArray, headers: Map<String, String>): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val bytes = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) throw IOException("DRM license HTTP $status from $url")
            return bytes
        } finally {
            conn.disconnect()
        }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun Format.toVideoFormat(): VideoFormat = VideoFormat(
    bitrate = bitrate.takeIf { it != Format.NO_VALUE },
    width = width.takeIf { it != Format.NO_VALUE },
    height = height.takeIf { it != Format.NO_VALUE },
    codecs = codecs,
    frameRate = frameRate.takeIf { it != Format.NO_VALUE.toFloat() },
)

private fun PlaybackException.toPlayerError(): PlayerError {
    val category = when (errorCode) {
        // 1000s band is "miscellaneous" — classify the actionable ones explicitly.
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> PlayerError.Category.SOURCE
        PlaybackException.ERROR_CODE_TIMEOUT -> PlayerError.Category.NETWORK
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        -> PlayerError.Category.SOURCE
        in 2000..2999 -> PlayerError.Category.NETWORK
        in 3000..3999 -> PlayerError.Category.SOURCE
        in 4000..4999 -> PlayerError.Category.DECODER
        in 5000..5999 -> PlayerError.Category.RENDERER
        in 6000..6999 -> PlayerError.Category.DRM
        else -> PlayerError.Category.UNKNOWN
    }
    return PlayerError(category = category, code = errorCodeName, message = message, nativeCause = this)
}
