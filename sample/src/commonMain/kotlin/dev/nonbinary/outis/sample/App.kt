/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.PlaybackState
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.sample.catalogue.CatalogueItem
import dev.nonbinary.outis.sample.catalogue.CatalogueRepository
import dev.nonbinary.outis.sample.catalogue.CatalogueState
import dev.nonbinary.outis.sample.catalogue.toMediaItem
import dev.nonbinary.outis.sample.di.sampleModule
import dev.nonbinary.outis.sample.generated.resources.Res
import dev.nonbinary.outis.sample.generated.resources.outis_lockup
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.PlayerView
import dev.nonbinary.outis.ui.controls.DefaultControls
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject

private const val ASPECT_16_9 = 16f / 9f
private val PLAYER_MAX_WIDTH = 960.dp
private val EDGE_PADDING = 16.dp
private val LOCKUP_HEIGHT = 128.dp
private val LOCKUP_GAP = 24.dp
private val STATUS_GAP = 16.dp

/** Starts Koin around the app. `KoinApplication` remembers the container, so this runs once. */
@Composable
fun App(appContext: AppContext, modifier: Modifier = Modifier) {
    KoinApplication(application = { modules(sampleModule) }) {
        AppContent(appContext, modifier)
    }
}

@OptIn(ExperimentalPlayerUiApi::class)
@Composable
private fun AppContent(appContext: AppContext, modifier: Modifier = Modifier) {
    val player = remember { VideoPlayer(appContext) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    val repository = koinInject<CatalogueRepository>()
    val catalogue by produceState<CatalogueState>(CatalogueState.Loading, repository) {
        value = repository.load()
    }

    var selected by remember { mutableStateOf<CatalogueItem?>(null) }
    var showSource by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // First item of the first rail, once the catalogue arrives. Keyed on the state so it runs on the
    // transition to Ready rather than on every recomposition, and never overrides a user's choice.
    LaunchedEffect(catalogue) {
        val ready = catalogue as? CatalogueState.Ready ?: return@LaunchedEffect
        if (selected == null) selected = ready.catalogue.rails.firstOrNull()?.items?.firstOrNull()
    }
    LaunchedEffect(selected) {
        selected?.let { player.setMediaItem(it.toMediaItem(), autoPlay = true) }
    }

    val state by player.state.collectAsState()
    val window = rememberSampleWindow(player)
    val immersive = window.isFullscreen || window.isInPip

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val playerSize = if (immersive) {
                    DpSize(maxWidth, maxHeight)
                } else {
                    windowedPlayerSize(maxWidth - EDGE_PADDING * 2, maxHeight - EDGE_PADDING * 2)
                }
                val halfPlayer = playerSize.height / 2
                val centreY = maxHeight / 2

                // One PlayerView, sized by mode. The dialog is a sibling, so opening it never moves or
                // rebuilds the surface.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(playerSize)
                        .background(Color.Black),
                ) {
                    PlayerView(
                        player,
                        modifier = Modifier.fillMaxSize(),
                        window = window,
                        controls = { if (!window.isInPip) DefaultControls() },
                    )
                }

                if (!immersive) {
                    // Anchored to the player's edges rather than laid out in a Column, so the player
                    // stays on the centre line instead of being pushed down by the lockup above it.
                    val spaceAbove = centreY - halfPlayer
                    if (spaceAbove >= LOCKUP_HEIGHT + LOCKUP_GAP + EDGE_PADDING) {
                        Image(
                            painter = painterResource(Res.drawable.outis_lockup),
                            contentDescription = "Outis — a Kotlin Multiplatform video player",
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = spaceAbove - LOCKUP_GAP - LOCKUP_HEIGHT)
                                .height(LOCKUP_HEIGHT),
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(top = centreY + halfPlayer + STATUS_GAP, start = EDGE_PADDING, end = EDGE_PADDING),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = statusLine(catalogue, selected, state.playbackState, state.error?.category?.name),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        Row(
                            modifier = Modifier.padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(onClick = { showSource = true }) { Text("Videos") }
                            OutlinedButton(onClick = { showSettings = true }) { Text("Player settings") }
                        }
                    }
                }
            }
        }

        if (showSource && !immersive) {
            SourceDialog(
                catalogue = catalogue,
                selectedId = selected?.id,
                onSelect = {
                    selected = it
                    showSource = false
                },
                onDismiss = { showSource = false },
            )
        }

        if (showSettings && !immersive) {
            PlayerSettingsDialog(onDismiss = { showSettings = false })
        }
    }
}

/**
 * 16:9 within the space allowed, capped so a wide desktop window does not get a wall of video, and
 * shrunk to fit when the window is short — `aspectRatio` alone satisfies the ratio against the incoming
 * width and lets the result overflow vertically.
 */
private fun windowedPlayerSize(availableWidth: Dp, availableHeight: Dp): DpSize {
    val width = minOf(availableWidth, PLAYER_MAX_WIDTH)
    val height = width / ASPECT_16_9
    return if (height <= availableHeight) {
        DpSize(
            width,
            height
        )
    } else {
        DpSize(availableHeight * ASPECT_16_9, availableHeight)
    }
}

/** One line saying what the player is doing, and where the stream list came from. */
private fun statusLine(
    catalogue: CatalogueState,
    selected: CatalogueItem?,
    playbackState: PlaybackState,
    errorCategory: String?,
): String {
    val source = when {
        catalogue is CatalogueState.Ready && catalogue.fromFallback ->
            "Catalogue unavailable — playing the built-in stream"
        selected != null -> selected.label ?: selected.title
        else -> "No item selected"
    }
    val playback = when {
        errorCategory != null -> "failed — $errorCategory"
        playbackState == PlaybackState.IDLE -> "idle"
        playbackState == PlaybackState.BUFFERING -> "buffering…"
        playbackState == PlaybackState.READY -> "playing"
        playbackState == PlaybackState.ENDED -> "ended"
        else -> playbackState.name.lowercase()
    }
    return "$source · $playback"
}
