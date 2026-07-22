/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

internal fun formatTime(ms: Long?): String {
    if (ms == null || ms < 0) return "--:--"
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    fun pad(v: Long) = v.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${pad(minutes)}:${pad(seconds)}" else "${pad(minutes)}:${pad(seconds)}"
}
