/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import dev.nonbinary.outis.core.source.DrmScheme

/**
 * A short warning when [scheme] is not expected to work on the host, or `null` when it should.
 *
 * A **hint, not a gate**. Schemes stay selectable everywhere on purpose: trying one the platform cannot
 * do is an ordinary integration mistake, and watching the SDK report it is more useful than being
 * prevented from making it. The wording is deliberately "not expected to" rather than "cannot" —
 * detection is imperfect, and a wrong hint is harmless where a wrongly disabled control is not.
 *
 * This lives in the sample because the SDK has no capability API yet; it should be deleted in favour of
 * one (see issue #30). It is also why the strings hedge — the sample is guessing where `:core` could
 * actually know.
 */
expect fun drmSchemeCaveat(scheme: DrmScheme): String?
