/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nonbinary.outis.sample.consent.ConsentCategory
import dev.nonbinary.outis.sample.consent.ConsentManager

private val DIALOG_MAX_WIDTH = 560.dp

/**
 * Consent for the analytics SDKs, serving both roles.
 *
 * - **First run** ([firstRun] = true): shown until the user chooses, so it cannot be dismissed by back
 *   or an outside tap — only Accept all / Reject non-essential / Save makes it go away. Nothing is
 *   collected before then, because the adapters only attach once their category is granted.
 * - **Revisit** (from Player settings): dismissable, seeded from the current choice.
 *
 * Switches edit **local** state; the choice is committed only by an action button. Committing per-toggle
 * would flip `decided` on the first flick and dismiss the first-run dialog mid-interaction.
 */
@Composable
fun ConsentDialog(manager: ConsentManager, firstRun: Boolean, onClose: () -> Unit) {
    val state by manager.state.collectAsState()

    // Local, editable copy of the consent-gated categories, seeded from the current decision.
    val pending = remember {
        mutableStateMapOf<ConsentCategory, Boolean>().apply {
            ConsentCategory.entries.filter { it.requiresConsent }.forEach { put(it, state.isGranted(it)) }
        }
    }

    Dialog(
        onDismissRequest = { if (!firstRun) onClose() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !firstRun,
            dismissOnClickOutside = !firstRun,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = DIALOG_MAX_WIDTH).padding(16.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (firstRun) "Before you start" else "Privacy & data",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "This demo can send anonymous playback data to help improve it. You choose what is " +
                        "shared, and can change it any time from Player settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                ConsentCategory.entries.forEach { category ->
                    CategoryRow(
                        category = category,
                        granted = if (category.requiresConsent) pending[category] == true else true,
                        onChange = { pending[category] = it },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            manager.rejectNonEssential()
                            onClose()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Reject non-essential") }
                    Button(
                        onClick = {
                            manager.save(pending.toMap())
                            onClose()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (firstRun) "Save choices" else "Save") }
                }
                Button(
                    onClick = {
                        manager.acceptAll()
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Accept all") }

                if (!firstRun) {
                    TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: ConsentCategory, granted: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(category.label, style = MaterialTheme.typography.titleSmall)
            Text(
                category.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Essential cannot be turned off — it is always granted — so its switch is fixed on and disabled.
        Switch(
            checked = granted,
            onCheckedChange = onChange.takeIf { category.requiresConsent },
            enabled = category.requiresConsent,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
