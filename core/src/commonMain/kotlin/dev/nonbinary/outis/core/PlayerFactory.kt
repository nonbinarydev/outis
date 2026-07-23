/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import dev.nonbinary.outis.core.plugin.PlayerComponent

/**
 * Construction-time configuration. Deliberately small and additive — new knobs arrive with
 * defaults so adding one never breaks callers.
 */
data class PlayerConfig(
    /**
     * Starting output volume in the range `0f`(silent)`..1f`(unattenuated), applied linearly by the
     * engine. This is the **player's own** gain, independent of the device/system volume, and it is
     * only the initial value — later changes go through the player's volume control.
     */
    val initialVolume: Float = 1f,
    /**
     * Position sampling cadence. No engine (Media3/AVPlayer/Shaka) exposes a continuous progress
     * stream, so position is always polled; ~250ms is the right cadence, not a compromise.
     */
    val positionPollIntervalMs: Long = 250,
    /** Buffering / load-control tuning; `null` => engine defaults. See [BufferConfig]. */
    val bufferConfig: BufferConfig? = null,
    /**
     * Initial ABR bandwidth estimate (**bits/sec**) seeded before any throughput is measured, so the
     * first variant on a cold start isn't a blind guess. `null` => engine default. Honoured on Android
     * (Media3 `DefaultBandwidthMeter`) and Web (Shaka `abr.defaultBandwidthEstimate`); **a no-op on iOS**
     * (AVPlayer exposes no initial-estimate API).
     */
    val initialBitrateBps: Int? = null,
    /**
     * Network retry / timeout policy for manifest/segment/license requests; `null` => engine
     * defaults. See [RetryConfig].
     */
    val retryConfig: RetryConfig? = null,
    /** Audio-session / focus behaviour; `null` => engine defaults. See [AudioConfig]. */
    val audioConfig: AudioConfig? = null,
    /**
     * Live-stream tuning (target latency, catch-up speed, low-latency mode); `null` => engine
     * defaults. See [LiveConfig].
     */
    val liveConfig: LiveConfig? = null,
    /** Components registered at construction. Empty in v1. */
    val components: List<PlayerComponent> = emptyList(),
)

/**
 * Live-stream tuning, applied at construction via [PlayerConfig.liveConfig]; `null` => engine defaults.
 * **Android (Media3)** honours all of [targetOffsetMs] + catch-up [minPlaybackSpeed]/[maxPlaybackSpeed]
 * (via `MediaItem.LiveConfiguration`). **iOS (AVPlayer)** honours [targetOffsetMs]
 * (`AVPlayerItem.configuredTimeOffsetFromLive`); catch-up speed is automatic. **Web (Shaka)** honours
 * [lowLatencyMode] (`streaming.lowLatencyMode`); its latency target is manifest-driven.
 *
 * Low-latency (LL-HLS / LL-DASH) is otherwise manifest-driven on Android + iOS — [lowLatencyMode]
 * is only the explicit toggle Web needs.
 */
data class LiveConfig(
    /** Target playback offset behind the live edge, ms. (Android + iOS.) */
    val targetOffsetMs: Long? = null,
    /** Minimum playback speed used to catch up to the live edge. (Android only.) */
    val minPlaybackSpeed: Float? = null,
    /** Maximum playback speed used to catch up to the live edge. (Android only.) */
    val maxPlaybackSpeed: Float? = null,
    /** Enable low-latency streaming. (Web `streaming.lowLatencyMode`; manifest-driven on Android/iOS.) */
    val lowLatencyMode: Boolean = false,
)

/**
 * Network retry / timeout policy, applied at construction via [PlayerConfig.retryConfig]; `null` keeps
 * each engine's defaults (Web's stock 30s timeout). Honoured on **Android** (Media3 HTTP connect/read
 * timeouts + a `DefaultLoadErrorHandlingPolicy` retry count) and **Web** (Shaka `retryParameters`).
 * **iOS (AVPlayer) is a no-op** — `AVURLAsset` exposes no granular retry/timeout API.
 */
data class RetryConfig(
    /** Connection timeout, ms. (Android `setConnectTimeoutMs`, Shaka `connectionTimeout`.) */
    val connectTimeoutMs: Int = 30_000,
    /** Read / overall request timeout, ms. (Android `setReadTimeoutMs`, Shaka `timeout`.) */
    val readTimeoutMs: Int = 30_000,
    /**
     * Number of **re**-tries after the first attempt. (Android load-error retry count; Shaka
     * `maxAttempts` = this + 1.)
     */
    val maxRetries: Int = 3,
    /** Base back-off delay between retries, ms. (Shaka `baseDelay`.) */
    val baseDelayMs: Int = 1_000,
    /** Exponential back-off multiplier. (Shaka `backoffFactor`.) */
    val backoffFactor: Double = 2.0,
    /** Follow HTTP↔HTTPS redirects — some CDN/token gateways need it. (Android only.) */
    val allowCrossProtocolRedirects: Boolean = false,
)

/**
 * Audio-session / focus behaviour, applied at construction via [PlayerConfig.audioConfig]; `null` keeps
 * each engine's defaults. The cross-engine surface is uneven: **Android (Media3)** honours
 * [handleAudioFocus] + [pauseOnBecomingNoisy] (and tags playback with media `AudioAttributes`). **iOS
 * (AVPlayer)** honours [mixWithOthers] (the `AVAudioSession` category) — interruptions/focus are handled
 * natively. **Web** has no audio-session concept, so every field is a no-op there.
 */
data class AudioConfig(
    /** Pause / duck when another app takes audio focus or an interruption occurs. (Android.) */
    val handleAudioFocus: Boolean = true,
    /** Pause when audio becomes "noisy" — e.g. headphones unplugged. (Android.) */
    val pauseOnBecomingNoisy: Boolean = true,
    /** Mix with other apps' audio instead of interrupting them. (iOS `AVAudioSession.mixWithOthers`.) */
    val mixWithOthers: Boolean = false,
)

/**
 * Buffering / load-control tuning, applied at construction via [PlayerConfig.bufferConfig]. Field names
 * and defaults mirror Media3's `DefaultLoadControl`. Leave [PlayerConfig.bufferConfig] **null** to keep
 * each engine's own stock behaviour; a non-null config applies these values verbatim. Useful to deepen
 * buffers for low-end devices / poor networks (e.g. a CTV set-top box) without forking per-device configs.
 *
 * Cross-engine support (honest): **Android (Media3)** honours every field (the defaults match its stock
 * `DefaultLoadControl`). **Web (Shaka)** maps [maxBufferMs]→`bufferingGoal`,
 * [bufferForPlaybackMs]→`rebufferingGoal`, [backBufferMs]→`bufferBehind` — but these Media3-shaped
 * defaults are **not** Shaka's own (Shaka's stock `bufferBehind` is ~30s, here 0), so set fields you
 * care about deliberately. **iOS (AVPlayer)** maps only [maxBufferMs]→`preferredForwardBufferDuration`;
 * the other fields are no-ops there. (Enforced at construction: `bufferForPlayback*Ms ≤ minBufferMs ≤
 * maxBufferMs`, all ≥ 0 — a bad config throws `IllegalArgumentException` early, not deep in ExoPlayer.)
 */
data class BufferConfig(
    /** Minimum buffer to keep, ms. */
    val minBufferMs: Int = 50_000,
    /** Maximum buffer to load ahead, ms. (Shaka `bufferingGoal`, iOS `preferredForwardBufferDuration`.) */
    val maxBufferMs: Int = 50_000,
    /** Buffer required before playback starts, ms. (Shaka `rebufferingGoal`.) */
    val bufferForPlaybackMs: Int = 2_500,
    /** Buffer required to resume after a rebuffer, ms. */
    val bufferForPlaybackAfterRebufferMs: Int = 5_000,
    /** Already-played media retained behind the position for instant back-seek, ms. (Shaka `bufferBehind`.) */
    val backBufferMs: Int = 0,
) {
    init {
        // Enforce Media3's ordering here (cross-platform, at the call site) so a bad config fails with a
        // clear message instead of a deep IllegalArgumentException inside ExoPlayer construction — and so
        // a partial override (e.g. minBufferMs below the default bufferForPlaybackMs=2500) is caught.
        require(minBufferMs in 0..maxBufferMs) {
            "BufferConfig requires 0 <= minBufferMs <= maxBufferMs (got min=$minBufferMs, max=$maxBufferMs)"
        }
        require(bufferForPlaybackMs in 0..minBufferMs && bufferForPlaybackAfterRebufferMs in 0..minBufferMs) {
            "BufferConfig requires bufferForPlayback*Ms in 0..minBufferMs " +
                "(got $bufferForPlaybackMs / $bufferForPlaybackAfterRebufferMs, minBufferMs=$minBufferMs)"
        }
        require(backBufferMs >= 0) { "BufferConfig.backBufferMs must be >= 0 (got $backBufferMs)" }
    }
}

/**
 * Create a platform [VideoPlayer]. A function that reads like a constructor, exposing the
 * platform factory through an `expect` seam.
 *
 * **Call this on the main / UI thread.** The native engines are bound to the platform main thread
 * (ExoPlayer is pinned to the main `Looper`; AVPlayer/Shaka are likewise main-thread affine), so
 * construction must happen there. Once created, the interface methods may be called from any thread.
 *
 * @param context platform application context ([AppContext]); only Android needs real data.
 */
fun VideoPlayer(context: AppContext, config: PlayerConfig = PlayerConfig()): VideoPlayer =
    createPlatformPlayer(context, config)

/** Per-platform construction. Actuals land with each engine (PR2 Android, PR4 iOS, PR5 Web). */
internal expect fun createPlatformPlayer(context: AppContext, config: PlayerConfig): VideoPlayer
