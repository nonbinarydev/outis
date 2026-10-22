/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nonbinary.outis.core.VideoRange
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi

/**
 * A small pill naming the current HDR flavour — "HDR10", "HLG", or "Dolby Vision" — from
 * [PlayerControlsState.videoRange]. Renders **nothing** for [VideoRange.SDR], so it appears only when it has
 * something to say, and updates live as adaptive streaming / the device negotiates HDR ⇄ SDR mid-playback.
 *
 * A reference implementation: apps on [DefaultControls] get it for free (top-left), and apps with custom
 * chrome can drop it in, or read [PlayerControlsState.videoRange] and render their own.
 */
@ExperimentalPlayerUiApi
@Composable
fun PlayerControlsScope.VideoRangeBadge(modifier: Modifier = Modifier) {
    val label = when (state.videoRange) {
        VideoRange.SDR -> return
        VideoRange.HDR10 -> "HDR10"
        VideoRange.HLG -> "HLG"
        VideoRange.DOLBY_VISION -> "Dolby Vision"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
