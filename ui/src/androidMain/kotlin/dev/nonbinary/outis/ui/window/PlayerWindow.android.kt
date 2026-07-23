/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.window

import android.app.Activity
import android.app.AppOpsManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.ui.PlayerSurfaceBounds
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Android rejects a picture-in-picture aspect ratio outside 1:2.39 .. 2.39:1, so a source outside
// that range is entered without an explicit ratio rather than throwing. The bounds are reciprocal,
// so the minimum is derived rather than written out — 0.42 rounded the wrong way and was fractionally
// tighter than the platform's own limit.
private const val MAX_PIP_ASPECT_RATIO = 2.39
private const val MIN_PIP_ASPECT_RATIO = 1.0 / MAX_PIP_ASPECT_RATIO

/**
 * Wires a [PlayerWindow] to the host Activity on Android:
 * - `isPipSupported` = the system PIP feature **AND** the app's AppOps permission (so a revoked
 *   permission hides the button instead of leaving it dead),
 * - `onEnterPip` enters PIP with the video's aspect ratio and returns whether it actually started,
 * - `isInPip` reflects the live mode via the Activity's picture-in-picture mode listener,
 * - fullscreen is delegated to [onToggleFullscreen], with the state tracked locally.
 *
 * The host still must declare `android:supportsPictureInPicture="true"` and the appropriate
 * `android:configChanges` on its Activity — a library cannot inject those.
 */
@Composable
fun rememberPlayerWindow(
    player: VideoPlayer,
    onToggleFullscreen: ((Boolean) -> Unit)? = null,
    onPipUnavailable: (() -> Unit)? = null,
): PlayerWindow {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var isInPip by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    val pipSupported = remember(activity) { activity != null && activity.isPipAllowed() }

    DisposableEffect(activity) {
        val componentActivity = activity as? ComponentActivity
        if (componentActivity == null) {
            onDispose { }
        } else {
            // The dedicated listener, not a LifecycleEventObserver reading isInPictureInPictureMode.
            // Entering PIP dispatches ON_PAUSE as well, and the order of that against the mode flag is
            // not guaranteed — so a lifecycle observer can sample the flag before it flips and report
            // the previous mode. This callback carries the new value with it, so there is nothing to
            // sample and no ordering to depend on. Hosts that lay out differently in PIP (filling the
            // tile rather than centring within it) are relying on this being right the first time.
            //
            // PictureInPictureModeChangedInfo comes from androidx.core.app, not androidx.activity:
            // ComponentActivity implements androidx.core.app.PictureInPictureProvider, and the info
            // type sits with the provider rather than with the Activity.
            val onPipChanged = Consumer<PictureInPictureModeChangedInfo> { info ->
                isInPip = info.isInPictureInPictureMode
            }
            componentActivity.addOnPictureInPictureModeChangedListener(onPipChanged)
            onDispose { componentActivity.removeOnPictureInPictureModeChangedListener(onPipChanged) }
        }
    }

    // Auto-enter has to be armed *before* the user leaves, so the Activity's standing params are kept
    // in step with playback rather than being built at the moment PIP is requested.
    //
    // Collected in an effect rather than read through collectAsState: PlayerState carries the playback
    // position, which changes on every poll, so observing it as Compose state would recompose this
    // (and everything reading the returned PlayerWindow) several times a second to watch two fields
    // that rarely change. distinctUntilChanged then reduces that to the transitions that matter.
    LaunchedEffect(activity, pipSupported) {
        val host = activity
        if (!pipSupported || host == null) return@LaunchedEffect
        player.state
            .map { it.isPlaying }
            .distinctUntilChanged()
            .collect { isPlaying -> host.updatePipParams(player, autoEnter = isPlaying) }
    }

    return PlayerWindow(
        isFullscreen = isFullscreen,
        isInPip = isInPip,
        isPipSupported = pipSupported,
        onToggleFullscreen = onToggleFullscreen?.let { callback ->
            {
                    desired ->
                isFullscreen = desired
                callback(desired)
            }
        },
        onEnterPip = if (pipSupported && activity != null) {
            { activity.enterPip(player, onPipUnavailable) }
        } else {
            null
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Activity.isPipAllowed(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
    return try {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        // One unconditional call, deliberately. This used to branch on API 29 to reach
        // unsafeCheckOpNoThrow, because checkOpNoThrow was deprecated in favour of it — but API 36
        // reversed that: unsafeCheckOpNoThrow is now the deprecated one and its own docs point back
        // here. checkOpNoThrow has existed since API 19 and this function has already returned for
        // anything below API 26, so there is no version left for a branch to serve.
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, applicationInfo.uid, packageName)
        // MODE_DEFAULT means "fall back to the platform default", which for PIP is allowed; only
        // an explicit IGNORED/ERRORED should hide the button.
        mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
    } catch (_: Exception) {
        false
    }
}

/**
 * The params describing this player's PIP tile: the video's aspect ratio, and the on-screen rectangle
 * the system animates *from*.
 *
 * Without a `sourceRectHint` the shrink animation starts from the whole window and visibly jumps; the
 * rectangle comes from the surface itself, recorded by `PlayerSurface` as it is laid out.
 */
private fun Activity.pipParams(player: VideoPlayer): PictureInPictureParams.Builder {
    val builder = PictureInPictureParams.Builder()
    val size = player.state.value.videoSize
    if (size != null && size.width > 0 && size.height > 0) {
        val ratio = size.width.toDouble() / size.height.toDouble()
        if (ratio in MIN_PIP_ASPECT_RATIO..MAX_PIP_ASPECT_RATIO) {
            builder.setAspectRatio(Rational(size.width, size.height))
        }
    }
    PlayerSurfaceBounds.of(player)?.let(builder::setSourceRectHint)
    return builder
}

/**
 * Keeps the Activity's standing PIP params current, and turns **auto-enter** on only while something is
 * actually playing.
 *
 * Auto-enter is what makes the home gesture drop a playing video into a PIP tile instead of backgrounding
 * it, and it cannot be requested at the moment of entry — it is a property the Activity carries in
 * advance, which is why this is a standing subscription rather than part of [enterPip]. Gating it on
 * playback matters: left permanently on, backgrounding a *paused* player would also open a PIP tile,
 * which is not what a user leaving a paused video expects.
 *
 * API 31+ only. Below that, auto-enter does not exist and PIP stays button-driven.
 */
private fun Activity.updatePipParams(player: VideoPlayer, autoEnter: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    try {
        setPictureInPictureParams(pipParams(player).setAutoEnterEnabled(autoEnter).build())
    } catch (_: IllegalStateException) {
        // The Activity is finishing or otherwise refuses params; nothing to recover, PIP simply
        // stays unavailable. Never surfaced to the user — no request was made.
    } catch (_: IllegalArgumentException) {
    }
}

private fun Activity.enterPip(player: VideoPlayer, onUnavailable: (() -> Unit)?): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        onUnavailable?.invoke()
        return false
    }
    return try {
        // enterPictureInPictureMode returns false when the system silently declines (multi-window,
        // OEM policy, transient state) — honour it instead of always reporting success.
        val entered = enterPictureInPictureMode(pipParams(player).build())
        if (!entered) onUnavailable?.invoke()
        entered
    } catch (_: IllegalStateException) {
        onUnavailable?.invoke()
        false
    } catch (_: IllegalArgumentException) {
        onUnavailable?.invoke()
        false
    }
}
