/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.sample

import dev.nonbinary.outis.core.source.DrmScheme
import dev.nonbinary.outis.core.source.MimeType

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

/**
 * A short warning when [mimeType]'s container isn't expected to play on the host, or `null` when it
 * should. Same "hint, not a gate" contract as [drmSchemeCaveat]: it covers container support only (DASH,
 * WebM), which is static per platform — a per-*codec* caveat (e.g. AV1, which is device-dependent on
 * Apple hardware) would need a runtime capability probe and is out of scope here.
 */
expect fun containerCaveat(mimeType: MimeType): String?
