/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

// macOS has no AVAudioSession: every app's audio mixes, so there is nothing to claim or hand back.

internal actual fun activatePlaybackAudioSession(audioConfig: AudioConfig?) = Unit

internal actual fun deactivatePlaybackAudioSession() = Unit
