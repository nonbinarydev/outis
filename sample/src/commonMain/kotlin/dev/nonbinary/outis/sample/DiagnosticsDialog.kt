/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nonbinary.outis.sample.diagnostics.DiagnosticEntry
import dev.nonbinary.outis.sample.diagnostics.DiagnosticLevel
import dev.nonbinary.outis.sample.diagnostics.DiagnosticsLog

private val DIALOG_MAX_WIDTH = 720.dp
private const val DIALOG_HEIGHT_FRACTION = 0.85f
private const val MILLIS_PER_SECOND = 1000.0
private const val DECI = 10

// Warm amber for a stall/warning, soft green for a good signal — read against the dark theme without
// competing with Material's own error red.
private val GOOD_GREEN = Color(0xFF6BCB77)
private val WARN_AMBER = Color(0xFFE0A458)

/**
 * The player's event timeline. Newest first, so "it just stopped — why?" is answered without scrolling.
 *
 * Copy exports the whole log as text to paste straight into a bug report — the point of the panel is to
 * turn "the stream is dodgy" into an attributable sequence.
 */
@Composable
fun DiagnosticsDialog(log: DiagnosticsLog, currentStream: String?, onDismiss: () -> Unit) {
    val entries by log.entries.collectAsState()
    val clipboard = LocalClipboardManager.current

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
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
                        currentStream?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                    TextButton(onClick = {
                        val header = currentStream?.let { "stream: $it\n" }.orEmpty()
                        clipboard.setText(AnnotatedString(header + log.asText()))
                    }) { Text("Copy") }
                    TextButton(onClick = { log.clear() }) { Text("Clear") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                if (entries.isEmpty()) {
                    Text(
                        "No events yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(entries.asReversed()) { LogRow(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: DiagnosticEntry) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            "T+${(entry.atMs / MILLIS_PER_SECOND).oneDp()}s",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                entry.label,
                style = MaterialTheme.typography.bodySmall,
                color = entry.level.colour(),
            )
            entry.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticLevel.colour(): Color = when (this) {
    DiagnosticLevel.Info -> MaterialTheme.colorScheme.onSurface
    DiagnosticLevel.Good -> GOOD_GREEN
    DiagnosticLevel.Warn -> WARN_AMBER
    DiagnosticLevel.Error -> MaterialTheme.colorScheme.error
}

private fun Double.oneDp(): String {
    val scaled = (this * DECI).toLong()
    return "${scaled / DECI}.${scaled % DECI}"
}
