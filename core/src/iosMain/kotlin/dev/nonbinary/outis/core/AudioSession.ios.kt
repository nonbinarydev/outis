/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:OptIn(ExperimentalForeignApi::class)

package dev.nonbinary.outis.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive

/**
 * Put the shared audio session into the `playback` category so media audio plays through the
 * hardware mute switch and isn't treated as transient/ambient — the iOS counterpart of the audio
 * handling Media3 gives us on Android. Best-effort: errors (e.g. another app holding exclusive
 * audio) are ignored rather than failing player construction.
 */
internal actual fun activatePlaybackAudioSession(audioConfig: AudioConfig?) {
    val session = AVAudioSession.sharedInstance()
    // mixWithOthers plays alongside other apps' audio instead of interrupting it.
    if (audioConfig?.mixWithOthers == true) {
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

/** Hand the audio session back so other apps' audio can resume. */
internal actual fun deactivatePlaybackAudioSession() {
    AVAudioSession.sharedInstance().setActive(false, error = null)
}
