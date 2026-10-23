/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.ui

/**
 * Marks the whole public surface of `:ui`, which is young and may change between alpha releases.
 *
 * `WARNING`, not `ERROR`: the library's alpha version already says nothing here is final, and gating every
 * call site of an optional UI module adds friction without adding information. The marker's job is to be
 * visible where the API is used. Opt in with `@OptIn(ExperimentalPlayerUiApi::class)`, or module-wide with
 * `optIn.add("dev.nonbinary.outis.ui.ExperimentalPlayerUiApi")`.
 */
@RequiresOptIn(message = "Player UI API is experimental and may change.", level = RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
public annotation class ExperimentalPlayerUiApi
