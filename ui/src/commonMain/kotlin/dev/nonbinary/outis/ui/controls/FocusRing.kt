/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui.controls

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.dp

/** Thickness of the ring. Drawn wholly inside the control's bounds, so it never affects layout. */
private val RingWidth = 2.dp

/** Deliberately plain white: controls sit over video, where a themed accent colour can vanish. */
private val RingColor = Color.White

/**
 * A visible focus ring so D-pad / TV users can see which control is selected — Compose's default
 * indication is touch-oriented and barely registers across a room.
 *
 * Implemented as a [Modifier.Node] rather than the older `composed { }` form, so a focus change
 * invalidates only the draw pass instead of recomposing every control carrying the ring. That
 * matters on TV, where D-pad movement re-focuses on every key press.
 */
internal fun Modifier.controlFocusRing(): Modifier = this then FocusRingElement

private data object FocusRingElement : ModifierNodeElement<FocusRingNode>() {
    override fun create() = FocusRingNode()

    /** The ring takes no parameters, so an already-attached node never needs updating. */
    override fun update(node: FocusRingNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "controlFocusRing"
    }
}

private class FocusRingNode : Modifier.Node(), FocusEventModifierNode, DrawModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focused != focusState.isFocused) {
            focused = focusState.isFocused
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val strokePx = RingWidth.toPx()
        // Inset by half the stroke so the ring lands fully inside the bounds, matching how
        // Modifier.border draws — a centred stroke would be clipped in half at the edges.
        inset(strokePx / 2f) {
            drawOutline(
                outline = CircleShape.createOutline(size, layoutDirection, this),
                color = RingColor,
                style = Stroke(strokePx),
            )
        }
    }
}
