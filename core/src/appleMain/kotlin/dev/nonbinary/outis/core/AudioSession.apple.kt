/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

/** Claims the process's audio for media playback as each engine starts. Main thread only. */
internal expect fun activatePlaybackAudioSession(audioConfig: AudioConfig?)

/** Hands the audio back once the last engine is released. Main thread only. */
internal expect fun deactivatePlaybackAudioSession()
