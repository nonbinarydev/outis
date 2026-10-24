/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.formatTime

private val AdChip = Color(0x99000000)
private const val MILLIS_PER_SECOND = 1000

// Clears the default overlay's scrubber and transport row while the controls are showing.
private val AboveControls = 108.dp

/**
 * "Ad 1 of 2 · 00:07" while a server-side ad plays: its place in the break and the time left in it. Draws
 * nothing outside a break, or when the state has no [dev.nonbinary.outis.core.ads.AdController]. The time
 * counts down at the player's position-poll cadence.
 */
@ExperimentalPlayerUiApi
@Composable
public fun AdCountdown(state: PlayerControlsState, modifier: Modifier = Modifier) {
    val ad = state.adState?.takeIf { it.isInAdBreak } ?: return
    val place = if (ad.adCountInBreak > 1) "Ad ${ad.adIndexInBreak + 1} of ${ad.adCountInBreak}" else "Ad"
    Text(
        text = "$place · ${formatTime(ad.adRemainingMs)}",
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .background(AdChip, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * Skips a skippable server-side ad through [PlayerControlsState.skipAd]: "Skip in 3" until the ad's skip
 * offset has played, then "Skip ad". Draws nothing for an unskippable ad, outside a break, or when the
 * state has no [dev.nonbinary.outis.core.ads.AdController].
 */
@ExperimentalPlayerUiApi
@Composable
public fun SkipAdButton(state: PlayerControlsState, modifier: Modifier = Modifier) {
    val ad = state.adState?.takeIf { it.isInAdBreak } ?: return
    val skipOffsetMs = ad.currentAd?.skipOffsetMs ?: return
    val content = LocalContentColor.current
    OutlinedButton(
        onClick = state::skipAd,
        enabled = ad.canSkip,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, content.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = AdChip,
            contentColor = content,
            disabledContainerColor = AdChip,
            disabledContentColor = content.copy(alpha = 0.7f),
        ),
        modifier = modifier.controlFocusRing(),
    ) {
        if (ad.canSkip) {
            Text("Skip ad")
            Icon(Icons.Filled.SkipNext, contentDescription = null, modifier = Modifier.size(18.dp))
        } else {
            val waitMs = (skipOffsetMs - ad.adPositionMs).coerceAtLeast(0L)
            Text("Skip in ${(waitMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND}")
        }
    }
}

/**
 * The ad layer [DefaultControls] draws during a server-side break: [AdCountdown] bottom-left and
 * [SkipAdButton] bottom-right, above the scrubber while the controls show. It stays up when the controls
 * auto-hide, as ad UI should.
 */
@ExperimentalPlayerUiApi
@Composable
public fun PlayerControlsScope.AdOverlay(modifier: Modifier = Modifier) {
    if (!state.isInAdBreak) return
    val bottom = if (state.controlsVisible) AboveControls else 12.dp
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = bottom)) {
            AdCountdown(Modifier.align(Alignment.BottomStart))
            SkipAdButton(Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** [AdCountdown] against the scope's own [PlayerControlsScope.state]. Behaviour is identical. */
@ExperimentalPlayerUiApi @Composable
public fun PlayerControlsScope.AdCountdown(modifier: Modifier = Modifier) {
    AdCountdown(state, modifier)
}

/** [SkipAdButton] against the scope's own [PlayerControlsScope.state]. Behaviour is identical. */
@ExperimentalPlayerUiApi @Composable
public fun PlayerControlsScope.SkipAdButton(modifier: Modifier = Modifier) {
    SkipAdButton(state, modifier)
}
