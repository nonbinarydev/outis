/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val DIALOG_MAX_WIDTH = 560.dp

/**
 * How the player itself is configured, as opposed to what it plays.
 *
 * Separate from [SourceDialog] rather than a third tab in it: one dialog picks content, this one
 * configures the engine, and mixing them would put "what" and "how" in the same tab row.
 *
 * Not built yet, and it is not merely a form. [dev.nonbinary.outis.core.PlayerConfig] is supplied at
 * construction — `VideoPlayer(context, config)`, with no runtime mutation — so applying a change here
 * means building a new player and restoring the current item and position onto it. The settings worth
 * exposing (buffer sizes, initial volume, the position poll interval) are all construction-time; the
 * ones that can change live, like volume and speed, are already in the player's own controls.
 */
@Composable
fun PlayerSettingsDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = DIALOG_MAX_WIDTH).padding(16.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("Player settings", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Not built yet. PlayerConfig is fixed at construction, so changing buffering, " +
                        "initial volume or the poll interval will rebuild the player and restore the " +
                        "current item and position — which is itself worth demonstrating.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 16.dp).align(Alignment.End),
                ) {
                    Text("Close")
                }
            }
        }
    }
}
