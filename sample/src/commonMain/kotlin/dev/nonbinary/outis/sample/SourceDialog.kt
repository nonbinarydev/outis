/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nonbinary.outis.sample.catalogue.CatalogueItem
import dev.nonbinary.outis.sample.catalogue.CatalogueRails
import dev.nonbinary.outis.sample.catalogue.CatalogueState

private val DIALOG_MAX_WIDTH = 900.dp
private const val DIALOG_HEIGHT_FRACTION = 0.85f

/** Panels the dialog hosts. Player-level settings will land here as a third tab. */
private enum class SourceTab(val label: String) {
    Catalogue("Catalogue"),
    Custom("Custom stream"),
}

/**
 * Everything that chooses *what* to play, kept off the main screen.
 *
 * A dialog rather than a panel below the player: the demo's main screen is the lockup and the player,
 * and a permanent rail strip squeezed both. It also scales — the custom-stream form and player settings
 * become tabs here rather than competing for the same space.
 *
 * `usePlatformDefaultWidth = false` because the default caps a dialog at a phone-ish width, which would
 * leave the rails scrolling in a narrow column on desktop.
 */
@Composable
fun SourceDialog(
    catalogue: CatalogueState,
    selectedId: String?,
    onSelect: (CatalogueItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(SourceTab.Catalogue) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = DIALOG_MAX_WIDTH)
                .fillMaxHeight(DIALOG_HEIGHT_FRACTION)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Choose a stream", style = MaterialTheme.typography.titleLarge)
                    Box(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    SourceTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            text = { Text(entry.label) },
                        )
                    }
                }

                when (tab) {
                    SourceTab.Catalogue -> CataloguePanel(catalogue, selectedId, onSelect)
                    SourceTab.Custom -> CustomStreamForm(
                        onPlay = onSelect,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CataloguePanel(
    catalogue: CatalogueState,
    selectedId: String?,
    onSelect: (CatalogueItem) -> Unit,
) {
    val ready = catalogue as? CatalogueState.Ready
    if (ready == null) {
        Text(
            "Loading catalogue…",
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        )
        return
    }
    Column {
        if (ready.fromFallback) {
            Text(
                "The published catalogue could not be loaded — showing the built-in stream.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        CatalogueRails(
            catalogue = ready.catalogue,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
