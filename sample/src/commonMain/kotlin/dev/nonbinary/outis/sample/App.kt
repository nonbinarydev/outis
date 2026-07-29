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
import androidx.compose.foundation.layout.BoxWithConstraintsScope
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
import androidx.compose.material3.TextButton
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
import dev.nonbinary.outis.analytics.mux.MuxAnalytics
import dev.nonbinary.outis.analytics.mux.MuxConfig
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.PlaybackState
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.sample.catalogue.CatalogueItem
import dev.nonbinary.outis.sample.catalogue.CatalogueRepository
import dev.nonbinary.outis.sample.catalogue.CatalogueState
import dev.nonbinary.outis.sample.catalogue.toMediaItem
import dev.nonbinary.outis.sample.consent.ConsentCategory
import dev.nonbinary.outis.sample.consent.ConsentManager
import dev.nonbinary.outis.sample.di.sampleModule
import dev.nonbinary.outis.sample.diagnostics.DiagnosticsLog
import dev.nonbinary.outis.sample.generated.SampleConfig
import dev.nonbinary.outis.sample.generated.resources.Res
import dev.nonbinary.outis.sample.generated.resources.outis_lockup
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.PlayerView
import dev.nonbinary.outis.ui.controls.DefaultControls
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module

private const val ASPECT_16_9 = 16f / 9f
private val PLAYER_MAX_WIDTH = 960.dp
private val EDGE_PADDING = 16.dp
private val LOCKUP_HEIGHT = 128.dp
private val LOCKUP_MIN_HEIGHT = 48.dp
private val LOCKUP_GAP = 24.dp
private val STATUS_GAP = 16.dp

/** Starts Koin around the app. `KoinApplication` remembers the container, so this runs once. */
@Composable
fun App(appContext: AppContext, modifier: Modifier = Modifier) {
    // koinConfiguration + the KoinConfiguration overload — the KoinAppDeclaration-lambda form is
    // deprecated in Koin 4.2. Built once via remember so the container is not rebuilt on recomposition.
    // AppContext is provided into the graph so DI can build things that need it (the consent store's
    // SharedPreferences on Android). Keyed on appContext so a new context rebuilds the container.
    val config = remember(appContext) {
        koinConfiguration {
            modules(module { single { appContext } }, sampleModule)
        }
    }
    KoinApplication(config) {
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
    val diagnostics = koinInject<DiagnosticsLog>()

    // Observe only — every line is a real player event, so the log never affects playback. Kept for the
    // life of the player so a stall that arrives minutes in is still captured.
    LaunchedEffect(player) {
        player.events.collect { diagnostics.record(it) }
    }
    val catalogue by produceState<CatalogueState>(CatalogueState.Loading, repository) {
        value = repository.load()
    }

    var selected by remember { mutableStateOf<CatalogueItem?>(null) }
    var showSource by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showConsentManage by remember { mutableStateOf(false) }

    val consent = koinInject<ConsentManager>()
    val consentState by consent.state.collectAsState()

    MuxQosBinding(player, appContext, consentState.isGranted(ConsentCategory.PERFORMANCE))

    // First item of the first rail, once the catalogue arrives. Keyed on the state so it runs on the
    // transition to Ready rather than on every recomposition, and never overrides a user's choice.
    LaunchedEffect(catalogue) {
        val ready = catalogue as? CatalogueState.Ready ?: return@LaunchedEffect
        if (selected == null) selected = ready.catalogue.rails.firstOrNull()?.items?.firstOrNull()
    }
    LaunchedEffect(selected) {
        selected?.let { item ->
            diagnostics.note("Stream selected", item.label ?: item.title)
            player.setMediaItem(item.toMediaItem(series = seriesOf(catalogue, item)), autoPlay = true)
        }
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
                    OverlayChrome(
                        lockupSpaceAbove = centreY - halfPlayer,
                        statusTop = centreY + halfPlayer + STATUS_GAP,
                        status = statusLine(catalogue, selected, state.playbackState, state.error?.category?.name),
                        onVideos = { showSource = true },
                        onSettings = { showSettings = true },
                        onDiagnostics = { showDiagnostics = true },
                    )
                }
            }
        }

        SourceDialogs(
            show = DialogFlags(showSource, showSettings, showDiagnostics),
            immersive = immersive,
            catalogue = catalogue,
            diagnostics = diagnostics,
            selected = selected,
            onSelect = {
                selected = it
                showSource = false
            },
            onDismissSource = { showSource = false },
            onDismissSettings = { showSettings = false },
            onDismissDiagnostics = { showDiagnostics = false },
            onManagePrivacy = {
                showSettings = false
                showConsentManage = true
            },
        )

        // Shown until a first-run choice is made (non-dismissable), or on demand from Player settings.
        // Nothing collects before a choice, because the adapters attach only once their category is
        // granted. Hidden while immersive, like the other dialogs.
        if ((!consentState.decided || showConsentManage) && !immersive) {
            ConsentDialog(
                manager = consent,
                firstRun = !consentState.decided,
                onClose = { showConsentManage = false },
            )
        }
    }
}

/**
 * Binds the Mux QoS adapter to [player] while Performance consent stands. The adapter is built only when
 * a build supplies an env key (otherwise null and skipped). Revoking consent disposes the effect, which
 * removes the component and does not re-add — so collection stops live, not on the next launch.
 */
@Composable
private fun MuxQosBinding(player: VideoPlayer, appContext: AppContext, performanceGranted: Boolean) {
    val mux = remember {
        SampleConfig.MUX_ENV_KEY.takeIf { it.isNotBlank() }?.let {
            MuxAnalytics(appContext, MuxConfig(envKey = it, playerName = "outis-sample"))
        }
    }
    DisposableEffect(mux, performanceGranted) {
        if (mux != null && performanceGranted) player.addComponent(mux)
        onDispose { mux?.let(player::removeComponent) }
    }
}

/** The title of the rail [item] sits in, for QoS grouping — the item alone does not know its rail. */
private fun seriesOf(catalogue: CatalogueState, item: CatalogueItem): String? =
    (catalogue as? CatalogueState.Ready)?.catalogue?.rails
        ?.firstOrNull { rail -> rail.items.any { it.id == item.id } }?.title

/** The lockup, status line and the three buttons — the non-player furniture, hidden while immersive. */
@Composable
private fun BoxWithConstraintsScope.OverlayChrome(
    lockupSpaceAbove: Dp,
    statusTop: Dp,
    status: String,
    onVideos: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    // Fit the lockup to the room above the player — full size in portrait, scaled down in a short
    // landscape window (e.g. iPad landscape, where the centred player leaves less than the full lockup
    // height above it) — and hide it only when there is genuinely no room.
    val lockupHeight = (lockupSpaceAbove - LOCKUP_GAP - EDGE_PADDING).coerceAtMost(LOCKUP_HEIGHT)
    if (lockupHeight >= LOCKUP_MIN_HEIGHT) {
        Image(
            painter = painterResource(Res.drawable.outis_lockup),
            contentDescription = "Outis — a Kotlin Multiplatform video player",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = lockupSpaceAbove - LOCKUP_GAP - lockupHeight)
                .height(lockupHeight),
        )
    }
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(top = statusTop, start = EDGE_PADDING, end = EDGE_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(status, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onVideos) { Text("Videos") }
            OutlinedButton(onClick = onSettings) { Text("Player settings") }
            TextButton(onClick = onDiagnostics) { Text("Diagnostics") }
        }
    }
}

private data class DialogFlags(val source: Boolean, val settings: Boolean, val diagnostics: Boolean)

/** The three overlays, lifted out of [AppContent] so its body stays under the complexity threshold. */
@Composable
private fun SourceDialogs(
    show: DialogFlags,
    immersive: Boolean,
    catalogue: CatalogueState,
    diagnostics: DiagnosticsLog,
    selected: CatalogueItem?,
    onSelect: (CatalogueItem) -> Unit,
    onDismissSource: () -> Unit,
    onDismissSettings: () -> Unit,
    onDismissDiagnostics: () -> Unit,
    onManagePrivacy: () -> Unit,
) {
    if (immersive) return
    if (show.source) {
        SourceDialog(catalogue, selected?.id, onSelect, onDismissSource)
    }
    if (show.settings) {
        PlayerSettingsDialog(onDismiss = onDismissSettings, onManagePrivacy = onManagePrivacy)
    }
    if (show.diagnostics) {
        DiagnosticsDialog(diagnostics, selected?.label ?: selected?.title, onDismissDiagnostics)
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
