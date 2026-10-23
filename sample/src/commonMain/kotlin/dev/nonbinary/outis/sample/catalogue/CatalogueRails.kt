/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample.catalogue

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.nonbinary.outis.core.source.DrmSupport
import dev.nonbinary.outis.sample.containerCaveat
import dev.nonbinary.outis.sample.drmSchemeCaveat
import dev.nonbinary.outis.sample.rememberDrmSupport

private val CARD_WIDTH = 190.dp
private const val HEX_RGB_DIGITS = 6
private const val HEX_RADIX = 16
private const val OPAQUE_ALPHA = 0xFF000000L
private const val HASH_PRIME = 31
private const val HASH_MASK = 0xFFFF
private const val HUE_DEGREES = 360
private const val DERIVED_SATURATION = 0.32f
private const val DERIVED_LIGHTNESS = 0.30f
private const val CARD_ASPECT = 16f / 9f
private const val FOCUSED_SCALE = 1.06f

// The lazy layout composes cards in its measure pass, which can come after the effect asking for focus.
private const val INITIAL_FOCUS_FRAMES = 10

/**
 * The catalogue as horizontal rails, one per group.
 *
 * Cards are drawn from [CatalogueItem.tint] or a colour derived from the id — posters are deferred,
 * because the schema's poster names resolve to hotlinked third-party images and would pull an image
 * loader into the sample.
 *
 * A focused card is ringed and raised, for D-pad and keyboard navigation; [initialFocusId] names the card
 * that takes focus first.
 */
@Composable
fun CatalogueRails(
    catalogue: Catalogue,
    selectedId: String?,
    onSelect: (CatalogueItem) -> Unit,
    modifier: Modifier = Modifier,
    initialFocusId: String? = null,
) {
    val drmSupport = rememberDrmSupport()
    val initialFocus = remember { FocusRequester() }
    if (initialFocusId != null) {
        LaunchedEffect(initialFocusId) {
            repeat(INITIAL_FOCUS_FRAMES) {
                if (runCatching { initialFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
                withFrameNanos { }
            }
        }
    }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(catalogue.rails, key = { it.id }) { rail ->
            Column(modifier = Modifier.padding(bottom = 20.dp)) {
                Text(
                    rail.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                )
                rail.note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    )
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(rail.items, key = { it.id }) { item ->
                        CatalogueCard(
                            item,
                            selected = item.id == selectedId,
                            posterUrl = item.poster?.let { catalogue.posters[it] },
                            drmSupport = drmSupport,
                            onClick = { onSelect(item) },
                            focusRequester = initialFocus.takeIf { item.id == initialFocusId },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogueCard(
    item: CatalogueItem,
    selected: Boolean,
    posterUrl: String?,
    drmSupport: DrmSupport?,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) FOCUSED_SCALE else 1f)
    Column(
        modifier = Modifier
            .width(CARD_WIDTH)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CARD_ASPECT)
                .clip(shape)
                .background(item.cardColour())
                .then(
                    when {
                        focused -> Modifier.border(3.dp, Color.White, shape)
                        selected -> Modifier.border(2.dp, accent, shape)
                        else -> Modifier
                    },
                )
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick),
            contentAlignment = Alignment.BottomStart,
        ) {
            // Poster over the fallback colour; while it loads or if it fails, cardColour() shows through.
            posterUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.tags.isNotEmpty()) {
                Text(
                    item.tags.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        item.label?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Selectable regardless: seeing the SDK report an unsupported scheme/container is the point, and
        // the hint can be wrong where a disabled card could not be un-wronged. DRM caveat wins over the
        // container one (a scheme mismatch is the more specific problem).
        (item.drm?.scheme?.let { drmSchemeCaveat(it, drmSupport) } ?: item.mimeType?.let(::containerCaveat))?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `tint` where the catalogue supplies one, otherwise a stable colour from the id — so a rail of
 * untinted items still reads as distinct cards rather than a row of identical rectangles.
 *
 * Only the hue varies. Saturation and lightness are fixed so every derived card sits at the same weight
 * against the dark theme and none of them fight the player for attention.
 */
private fun CatalogueItem.cardColour(): Color {
    tint?.let { hex ->
        val cleaned = hex.removePrefix("#")
        if (cleaned.length == HEX_RGB_DIGITS) {
            cleaned.toLongOrNull(radix = HEX_RADIX)?.let { return Color(it or OPAQUE_ALPHA) }
        }
    }
    val hue = (id.fold(0) { acc, c -> (acc * HASH_PRIME + c.code) and HASH_MASK } % HUE_DEGREES).toFloat()
    return Color.hsl(hue, DERIVED_SATURATION, DERIVED_LIGHTNESS)
}
