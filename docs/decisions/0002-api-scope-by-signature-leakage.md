# 2. Dependency scope is decided by public-signature leakage

**Status:** Accepted

## Context

`:core` originally declared every dependency as `implementation`. That publishes into
`runtimeElements` but not `apiElements`, so consumers do not receive those libraries on their
**compile** classpath.

Several of them were in public signatures regardless: `StateFlow` and `SharedFlow` on
`VideoPlayer.state` and `.events`, `ImmutableList` and `ImmutableMap` throughout `PlayerState` and
`MediaItem`, and `AdViewProvider` on the Android `setAdViewProvider` extension.

The result was that `player.state.collect { }` — the first thing anyone writes — failed to compile
unless the consumer happened to declare kotlinx-coroutines themselves. `:ui`, the only consumer that
existed, had silently worked around it by re-declaring `kotlinx-collections-immutable` and
`media3-common`.

## Decision

A dependency is `api` if and only if one of its types appears in a public signature of the module.
Everything else stays `implementation`.

Applied: `kotlinx-coroutines-core` and `kotlinx-collections-immutable` are `api` in `commonMain`.
`media3-common` is `api` in `androidMain` — and only that artifact, not the exoplayer ones, because the
single leaking declaration exposes `AdViewProvider` and nothing more. `kotlinx-datetime`,
`kotlinx-serialization-json` and Napier appear in no public signature and stay `implementation`.

## Consequences

Consumers get what they need to compile against the published artifacts, and `:ui` dropped both of its
workarounds.

`api` dependencies are part of the published contract: their major-version bumps become breaking
changes for consumers. That is the cost of exposing those types at all, and it is a reason to keep the
set small rather than a reason to hide it behind `implementation`.

The rule is mechanical enough to check. Grep public declarations for a library's types; if there are
none, it should not be `api`.
