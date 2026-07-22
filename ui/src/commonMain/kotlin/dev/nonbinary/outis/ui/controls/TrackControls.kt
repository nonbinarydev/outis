/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nonbinary.outis.core.track.MediaTrack
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import kotlinx.collections.immutable.ImmutableList

private fun trackLabel(track: MediaTrack): String = track.label ?: track.language ?: track.id

/**
 * Icon button opening a dropdown of the available text tracks, plus an "Off" entry that clears the
 * selection.
 *
 * **Self-hiding**: renders nothing at all when [PlayerControlsState.textTracks] is empty, so it costs
 * no layout space on assets without subtitles. Tracks are labelled by `label`, falling back to
 * `language` and finally the track id, so an unlabelled manifest still produces a readable menu.
 *
 * While the menu is open it latches the overlay visible (auto-hide is paused) and releases the latch
 * on dismissal — **and on disposal**, so a control removed mid-menu cannot pin the overlay forever.
 * The icon is tinted with the primary colour whenever a text track is selected.
 */
@ExperimentalPlayerUiApi
@Composable
fun SubtitleButton(state: PlayerControlsState, modifier: Modifier = Modifier) {
    if (state.textTracks.isEmpty()) return // self-hide: nothing to choose
    var open by remember { mutableStateOf(false) }
    val token = remember { Any() }
    LaunchedEffect(open) { if (open) state.keepVisible(token) else state.releaseVisible(token) }
    DisposableEffect(Unit) { onDispose { state.releaseVisible(token) } } // release latch if removed while open
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.controlFocusRing()) {
            Icon(
                imageVector = Icons.Filled.Subtitles,
                contentDescription = "Subtitles",
                tint = if (state.selectedTextTrackId != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalContentColor.current
                },
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TrackMenuItem(label = "Off", selected = state.selectedTextTrackId == null) {
                state.player.clearTextTrack()
                state.notifyInteraction()
                open = false
            }
            state.textTracks.forEach { track ->
                TrackMenuItem(label = trackLabel(track), selected = track.id == state.selectedTextTrackId) {
                    state.player.selectTrack(track)
                    state.notifyInteraction()
                    open = false
                }
            }
        }
    }
}

/**
 * Icon button opening a dropdown of the available audio tracks.
 *
 * **Self-hiding**: renders nothing when there are fewer than two audio tracks — a single track is no
 * choice at all. There is deliberately no "Off" entry, unlike [SubtitleButton]: audio cannot be
 * deselected, only swapped. Menu visibility latching and disposal behaviour match [SubtitleButton].
 */
@ExperimentalPlayerUiApi
@Composable
fun AudioButton(state: PlayerControlsState, modifier: Modifier = Modifier) {
    if (state.audioTracks.size < 2) return // self-hide: no real choice
    var open by remember { mutableStateOf(false) }
    val token = remember { Any() }
    LaunchedEffect(open) { if (open) state.keepVisible(token) else state.releaseVisible(token) }
    DisposableEffect(Unit) { onDispose { state.releaseVisible(token) } }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.controlFocusRing()) {
            Icon(Icons.Filled.Audiotrack, contentDescription = "Audio track")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.audioTracks.forEach { track ->
                TrackMenuItem(label = trackLabel(track), selected = track.id == state.selectedAudioTrackId) {
                    state.player.selectTrack(track)
                    state.notifyInteraction()
                    open = false
                }
            }
        }
    }
}

/** A standalone, selectable list of tracks for fully-custom shells (e.g. a TV side panel). */
@ExperimentalPlayerUiApi
@Composable
fun TrackList(
    tracks: ImmutableList<MediaTrack>,
    selectedId: String?,
    onSelect: (MediaTrack) -> Unit,
    modifier: Modifier = Modifier,
    onOff: (() -> Unit)? = null,
) {
    Column(modifier) {
        if (onOff != null) TrackRow(label = "Off", selected = selectedId == null, onClick = onOff)
        tracks.forEach { track ->
            TrackRow(label = trackLabel(track), selected = track.id == selectedId) { onSelect(track) }
        }
    }
}

@Composable
private fun TrackMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        trailingIcon = { if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected") },
    )
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected")
    }
}

/** Scope-member form of [SubtitleButton], taking the [state] from the surrounding controls lambda. */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.SubtitleButton(modifier: Modifier = Modifier) {
    SubtitleButton(state, modifier)
}

/** Scope-member form of [AudioButton], taking the [state] from the surrounding controls lambda. */
@ExperimentalPlayerUiApi @Composable
fun PlayerControlsScope.AudioButton(modifier: Modifier = Modifier) {
    AudioButton(state, modifier)
}
