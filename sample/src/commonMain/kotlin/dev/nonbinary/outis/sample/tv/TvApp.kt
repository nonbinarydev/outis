/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.sample.SampleHost
import dev.nonbinary.outis.sample.ads.rememberServerSideAds
import dev.nonbinary.outis.sample.catalogue.CatalogueItem
import dev.nonbinary.outis.sample.catalogue.CatalogueRails
import dev.nonbinary.outis.sample.catalogue.CatalogueRepository
import dev.nonbinary.outis.sample.catalogue.CatalogueState
import dev.nonbinary.outis.sample.catalogue.toMediaItem
import dev.nonbinary.outis.sample.generated.resources.Res
import dev.nonbinary.outis.sample.generated.resources.outis_lockup
import dev.nonbinary.outis.sample.rememberControlsState
import dev.nonbinary.outis.ui.PlayerView
import dev.nonbinary.outis.ui.controls.DefaultControls
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject

/**
 * The sample on a television: catalogue rails to browse with a D-pad, and the player full screen.
 * Catalogue-only, because typing a stream URL with a D-pad is no way to try a player. Back leaves the
 * player for the rails, and the rails for the launcher. It runs no analytics, so it asks for no consent.
 */
@Composable
fun TvApp(appContext: AppContext, modifier: Modifier = Modifier) {
    SampleHost(appContext) { TvContent(appContext, modifier) }
}

@Composable
private fun TvContent(appContext: AppContext, modifier: Modifier = Modifier) {
    val player = remember { VideoPlayer(appContext) }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    val repository = koinInject<CatalogueRepository>()
    val catalogue by produceState<CatalogueState>(CatalogueState.Loading, repository) {
        value = repository.load()
    }

    var playing by remember { mutableStateOf<CatalogueItem?>(null) }
    var lastPlayedId by remember { mutableStateOf<String?>(null) }
    val ads = rememberServerSideAds(player, playing)
    val controls = rememberControlsState(player, ads)
    LaunchedEffect(playing) {
        val item = playing
        if (item == null) {
            player.stop()
        } else {
            player.setMediaItem(item.toMediaItem(), autoPlay = true)
            // The focused card went with the rails; the overlay takes focus as it appears.
            controls.showControls()
        }
    }
    // Keeps the rails' scroll positions while the player covers them, so Back lands where the viewer was.
    val browseState = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { event ->
                    when {
                        playing == null -> moveAcrossRails(event, focusManager)
                        // Escape stands in for Back on a keyboard. From the rails, Back falls through to the
                        // platform.
                        event.key == Key.Back || event.key == Key.Escape -> {
                            if (event.type == KeyEventType.KeyUp) {
                                lastPlayedId = playing?.id
                                playing = null
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            // One PlayerView, always composed: the rails cover it rather than remove it. Its controls are
            // left out while browsing, so the D-pad cannot wander into them under the rails.
            PlayerView(
                player,
                modifier = Modifier.fillMaxSize(),
                state = controls,
                controls = { if (playing != null) DefaultControls() },
            )
            if (playing == null) {
                browseState.SaveableStateProvider("rails") {
                    TvBrowse(catalogue, lastPlayedId, onPlay = { playing = it })
                }
            }
        }
    }
}

/**
 * Arrow keys and the D-pad move focus between cards. Android TV does this for the D-pad by itself; the web
 * does not for arrow keys, and `?tv` exists to try the screen there. Only while browsing: in the player
 * the same keys belong to the scrubber and the menus.
 */
private fun moveAcrossRails(event: KeyEvent, focusManager: FocusManager): Boolean {
    val direction = when (event.key) {
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        else -> return false
    }
    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
    return true
}

@Composable
private fun TvBrowse(catalogue: CatalogueState, lastPlayedId: String?, onPlay: (CatalogueItem) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        // Inset from the screen edge, which a television may overscan.
        Column(Modifier.padding(horizontal = 32.dp, vertical = 24.dp)) {
            Image(
                painterResource(Res.drawable.outis_lockup),
                contentDescription = "Outis",
                modifier = Modifier.height(72.dp).padding(start = 16.dp, bottom = 8.dp),
            )
            when (catalogue) {
                CatalogueState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is CatalogueState.Ready -> CatalogueRails(
                    catalogue.catalogue,
                    selectedId = lastPlayedId,
                    onSelect = onPlay,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    initialFocusId = lastPlayedId ?: catalogue.catalogue.rails.firstOrNull()?.items?.firstOrNull()?.id,
                )
            }
        }
    }
}
