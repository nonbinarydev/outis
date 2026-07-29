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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.sample.catalogue.CatalogueDrm
import dev.nonbinary.outis.sample.catalogue.CatalogueItem

/** Marks the entry this form produces, so the catalogue can highlight it like any other selection. */
const val CUSTOM_STREAM_ID = "custom-stream"

/**
 * Builds a [CatalogueItem] by hand, rather than a [dev.nonbinary.outis.core.source.MediaItem] directly.
 *
 * Reusing the catalogue's own type means selection, the status line and card highlighting all treat a
 * typed-in stream exactly like a curated one, and the single `toMediaItem()` translation stays the only
 * place that knows how to reach the SDK's model.
 *
 * Fields are limited to what `MediaItem` and `DrmConfig` genuinely support on all three platforms.
 * Custom request headers are deliberately absent: `MediaItem.headers` is Android-only today, so a field
 * here would demonstrate our own gap to anyone who tried it on web or iOS.
 */
@Composable
fun CustomStreamForm(onPlay: (CatalogueItem) -> Unit, modifier: Modifier = Modifier) {
    var url by remember { mutableStateOf("") }
    var mimeType by remember { mutableStateOf<MimeType?>(null) }
    var drmOn by remember { mutableStateOf(false) }
    var scheme by remember { mutableStateOf(DrmScheme.WIDEVINE) }
    var licenceUrl by remember { mutableStateOf("") }
    var certificateUrl by remember { mutableStateOf("") }

    // FairPlay cannot start a key session without the FPS application certificate, so a licence URL
    // alone is not enough to enable Play — the catalogue's own FairPlay entry documents this.
    val needsCertificate = drmOn && scheme == DrmScheme.FAIRPLAY
    val canPlay = url.isNotBlank() &&
        (!drmOn || licenceUrl.isNotBlank()) &&
        (!needsCertificate || certificateUrl.isNotBlank())

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Stream URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Container", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Null means "let the engine infer it from the URL", which is what MediaItem does with a
            // null mimeType — worth having as an option rather than forcing a guess.
            FilterChip(
                selected = mimeType == null,
                onClick = { mimeType = null },
                label = { Text("Auto") },
            )
            MimeType.entries.forEach { type ->
                FilterChip(
                    selected = mimeType == type,
                    onClick = { mimeType = type },
                    label = { Text(type.name) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = drmOn, onCheckedChange = { drmOn = it })
            Text("DRM", modifier = Modifier.padding(start = 12.dp))
        }

        if (drmOn) {
            DrmSection(
                scheme = scheme,
                onScheme = { scheme = it },
                licenceUrl = licenceUrl,
                onLicenceUrl = { licenceUrl = it },
                certificateUrl = certificateUrl,
                onCertificateUrl = { certificateUrl = it },
                needsCertificate = needsCertificate,
            )
        }

        Button(
            enabled = canPlay,
            onClick = {
                onPlay(
                    CatalogueItem(
                        id = CUSTOM_STREAM_ID,
                        title = "Custom stream",
                        label = url,
                        url = url.trim(),
                        mimeType = mimeType,
                        drm = if (drmOn) {
                            CatalogueDrm(
                                scheme = scheme,
                                licenseServerUrl = licenceUrl.trim(),
                                certificateUrl = certificateUrl.trim().takeIf { it.isNotBlank() },
                            )
                        } else {
                            null
                        },
                    ),
                )
            },
        ) {
            Text("Play")
        }
    }
}

/** Extracted so [CustomStreamForm] stays readable; it is one cohesive block of the same form. */
@Composable
private fun DrmSection(
    scheme: DrmScheme,
    onScheme: (DrmScheme) -> Unit,
    licenceUrl: String,
    onLicenceUrl: (String) -> Unit,
    certificateUrl: String,
    onCertificateUrl: (String) -> Unit,
    needsCertificate: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // ClearKey is omitted: it takes inline keys, not a license URL, so this URL-based form can't drive it.
        DrmScheme.entries.filter { it != DrmScheme.CLEARKEY }.forEach { entry ->
            FilterChip(
                selected = scheme == entry,
                onClick = { onScheme(entry) },
                label = { Text(entry.name) },
            )
        }
    }
    // Selectable regardless of the caveat: watching the SDK report an unsupported scheme is more
    // useful than being stopped from trying, and the hint is a guess on web.
    drmSchemeCaveat(scheme)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    OutlinedTextField(
        value = licenceUrl,
        onValueChange = onLicenceUrl,
        label = { Text("Licence server URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (needsCertificate) {
        OutlinedTextField(
            value = certificateUrl,
            onValueChange = onCertificateUrl,
            label = { Text("FPS certificate URL") },
            supportingText = { Text("Required — FairPlay cannot start a key session without it.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
