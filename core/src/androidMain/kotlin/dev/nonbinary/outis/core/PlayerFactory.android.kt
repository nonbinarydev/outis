/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

internal actual fun createPlatformPlayer(context: AppContext, config: PlayerConfig): VideoPlayer =
    ExoPlayerEngine(context.applicationContext, config)

/**
 * Single construction point for the [ExoPlayer] — **player-level** config only: load-control (buffers),
 * the cold-start bandwidth meter, and audio attributes / focus. Per-item concerns (request headers,
 * retry/timeouts, DRM) are built per [MediaItem] in [ExoPlayerEngine]'s `buildMediaSource`, which is the
 * seam the roadmap injects through:
 * - **Ads:** an ad-tag `AdsConfiguration` on each `ExoMediaItem` (in `toExoMediaItem`) plus
 *   `DefaultMediaSourceFactory.setLocalAdInsertionComponents(...)` with an `ImaAdsLoader`.
 * - **DRM:** `MediaItem.drmConfig` → a `DrmConfiguration` on each `ExoMediaItem`, which
 *   `DefaultMediaSourceFactory` turns into a `DrmSessionManager` automatically.
 *
 * The player is pinned to the main [Looper]; all access is marshalled there by [ExoPlayerEngine],
 * which therefore must be constructed on the main thread.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun buildExoPlayer(context: Context, config: PlayerConfig): ExoPlayer =
    ExoPlayer.Builder(context)
        .setLooper(Looper.getMainLooper())
        .apply {
            // Buffer/load-control tuning (Media3 honours every field).
            config.bufferConfig?.let { b ->
                setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(
                            b.minBufferMs,
                            b.maxBufferMs,
                            b.bufferForPlaybackMs,
                            b.bufferForPlaybackAfterRebufferMs,
                        )
                        // Second argument is retainBackBufferFromKeyframe: false keeps the whole back
                        // buffer rather than only what follows the last keyframe. (Media3 is Java, so
                        // the argument cannot be named at the call site.)
                        .setBackBuffer(b.backBufferMs, false)
                        .build(),
                )
            }
            // ABR cold-start seed.
            config.initialBitrateBps?.let { bps ->
                setBandwidthMeter(
                    DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(bps.toLong()).build(),
                )
            }
            // Audio focus + becoming-noisy handling, tagged with media audio attributes.
            config.audioConfig?.let { a ->
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    a.handleAudioFocus,
                )
                setHandleAudioBecomingNoisy(a.pauseOnBecomingNoisy)
            }
        }
        .build()
        .apply { volume = config.initialVolume }
