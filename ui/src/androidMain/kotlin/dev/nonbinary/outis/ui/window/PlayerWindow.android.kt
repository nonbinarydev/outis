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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import dev.nonbinary.outis.core.VideoPlayer

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
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, applicationInfo.uid, packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, applicationInfo.uid, packageName)
        }
        // MODE_DEFAULT means "fall back to the platform default", which for PIP is allowed; only
        // an explicit IGNORED/ERRORED should hide the button.
        mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
    } catch (_: Exception) {
        false
    }
}

private fun Activity.enterPip(player: VideoPlayer, onUnavailable: (() -> Unit)?): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        onUnavailable?.invoke()
        return false
    }
    return try {
        val builder = PictureInPictureParams.Builder()
        val size = player.state.value.videoSize
        if (size != null && size.width > 0 && size.height > 0) {
            val ratio = size.width.toDouble() / size.height.toDouble()
            if (ratio in MIN_PIP_ASPECT_RATIO..MAX_PIP_ASPECT_RATIO) {
                builder.setAspectRatio(Rational(size.width, size.height))
            }
        }
        // enterPictureInPictureMode returns false when the system silently declines (multi-window,
        // OEM policy, transient state) — honour it instead of always reporting success.
        val entered = enterPictureInPictureMode(builder.build())
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
