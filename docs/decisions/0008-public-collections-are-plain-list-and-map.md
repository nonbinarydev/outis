# 8. Public collections are plain `List` and `Map`

**Status:** Accepted. Revises the collections part of [ADR-0002](0002-api-scope-by-signature-leakage.md)'s
application; its rule stands.

## Context

The public model mixed two collection families with no rule a consumer could predict. `PlayerState`'s
tracks and chapters, `MediaItem.headers`, the two `DrmConfig` maps and `PlaybackMetadata.custom` were
`ImmutableList`/`ImmutableMap`; `AdConfig.ServerSide.breaks`, `AdBreak.ads`, `AdState.cuePoints`,
`PlayerConfig.components` and every parser's return type were plain `List`. The split was not input
versus output either: `headers` is consumer-supplied and immutable, `components` consumer-supplied and
plain.

The stated reason for the immutable types was Compose stability, and it did not hold. `:core` is not
compiled by the Compose compiler, so its classes carry no stability metadata and read as unstable to
`:ui` and to every app whatever their fields are. `PlayerState.adState` also embedded plain lists inside
the very snapshot the immutable fields were meant to keep stable.

The cost was real. ADR-0002 made `kotlinx-collections-immutable` an `api` dependency because these types
leaked, which put a `0.x` library's breaking changes into Outis's published contract. The guides told
consumers to add it to their own build just to write `persistentMapOf("Authorization" to token)`.

## Decision

Public signatures in `:core` use `kotlin.collections.List` and `Map`. `kotlinx-collections-immutable`
leaves `:core` and the version catalog.

Stability is declared rather than typed: `:ui` lists `dev.nonbinary.outis.core.**`, `List` and `Map` in
its Compose stability configuration (`ui/compose-stability.conf`).

## Consequences

Consumers build items with `mapOf` and `listOf`, and `:core` has one fewer transitive dependency.

Nothing stops a caller from passing a `MutableMap` and mutating it after `setMediaItem`; the SDK does not
copy at the boundary. What it promises is narrower: it never mutates a collection it hands out.

A stability declaration can be wrong in a way a type cannot. If a `:core` model type ever gains mutable
state, `ui/compose-stability.conf` has to be revisited with it. Apps writing their own Compose chrome over
`:core` need the same lines in their own configuration to get skipping; strong skipping already compares
the common case, an unchanged `PlayerState` list, by instance.
