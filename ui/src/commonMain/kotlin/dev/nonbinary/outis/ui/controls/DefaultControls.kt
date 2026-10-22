/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi

private val Scrim = Color(0x66000000)

/**
 * The default overlay — written entirely against the public scope, so it's an honest copy/fork target.
 * Top row = subtitle/audio/PIP/fullscreen; centre = buffering or big play; bottom = scrubber + transport.
 * Adds a scrim, grabs D-pad focus on appear (TV), and re-arms auto-hide on focus movement.
 */
@ExperimentalPlayerUiApi
@Composable
fun PlayerControlsScope.DefaultControls(modifier: Modifier = Modifier) {
    ControlsScaffold(modifier)
}

/** [DefaultControls] with each region overridable — retheme while keeping layout/focus/scrim/auto-hide. */
@ExperimentalPlayerUiApi
@Composable
fun PlayerControlsScope.ControlsScaffold(
    modifier: Modifier = Modifier,
    top: @Composable RowScope.() -> Unit = {
        SubtitleButton()
        AudioButton()
        PipButton()
        FullscreenButton()
    },
    center: @Composable BoxScope.() -> Unit = {
        if (state.isWaitingToPlay) {
            BufferingIndicator(Modifier.align(Alignment.Center))
        } else {
            BigPlayButton(Modifier.align(Alignment.Center))
        }
    },
    bottom: @Composable RowScope.() -> Unit = {
        PlayPauseButton()
        MuteButton()
        TimeLabel(Modifier.padding(start = 8.dp))
        Spacer(Modifier.weight(1f))
        PlaybackSpeedButton()
    },
) {
    if (!state.controlsVisible) return
    // White content over the scrim by default; reused on a light surface, blocks pick up the theme colour.
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Scrim)
                .focusRequester(focusRequester)
                .focusGroup()
                // Focus movement re-arms auto-hide (it counts as interaction) but never PINS the overlay.
                .onFocusChanged { if (it.hasFocus) state.notifyInteraction() },
        ) {
            // Top-left: the HDR/DV badge (self-hides for SDR), mirroring where the demo burns its info board.
            VideoRangeBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                content = top,
            )
            center()
            Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)) {
                Scrubber(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, content = bottom)
            }
        }
    }
    // Land a D-pad on a control when the overlay appears: focus the group, which forwards to its first
    // focusable child — robust even if `bottom` is overridden (the requester is on the root, not a child).
    LaunchedEffect(state.controlsVisible) {
        if (state.controlsVisible) runCatching { focusRequester.requestFocus() }
    }
}
