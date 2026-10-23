# 9. The public model stays `data class`; binary compatibility starts at 1.0

**Status:** Accepted

## Context

Every public model type in `:core` and `:ui` is a `data class` with defaulted constructor parameters:
`PlayerState`, `MediaItem`, `PlayerConfig`, `DrmConfig`, the ad model and the rest. Adding a defaulted
parameter keeps every call site compiling, but it changes the primary constructor's descriptor, the
synthetic default-argument constructor and `copy$default`, and it appends a `componentN()`. Code compiled
against one release and linked against the next fails with `NoSuchMethodError`. A consumer built against
`Item(a, b)` and run against `Item(a, b, c)` fails on its first `Item(a = 1)`.

The `CHANGELOG` and `CONTRIBUTING` already said so and promised a **recompile required** marker until
1.0. The `PlayerConfig` KDoc said the opposite ("adding one never breaks callers"), and nothing said what
happens after 1.0.

Three alternatives were weighed:

- Drop `data` from the configuration and state carriers, and hand-write `equals`, `hashCode` and
  `toString` with `withX()` builders.
- Drop it only from the types the SDK produces (`PlayerState`, `AdState`, `PlayerError`), on the grounds
  that `copy()` lets a consumer fabricate a state.
- Keep `data` and keep the old signatures alive by hand when a field is added.

## Decision

The public model stays `data class`.

- **Before 1.0**, adding a field is source-compatible and binary-incompatible. The release that does it
  is marked **recompile required** in the `CHANGELOG`, and KDoc promises source compatibility only.
- **From 1.0**, a field added to a published data class keeps the previous primary constructor and the
  previous `copy` as `@Deprecated(level = DeprecationLevel.HIDDEN)` overloads, so code built against the
  earlier release still links. Checked on the JVM with Kotlin 2.4.10: a consumer compiled against
  `Item(a, b)` runs against `Item(a, b, c)` with the hidden overloads present, and fails with
  `NoSuchMethodError` without them. The checked-in ABI dump is what makes a missing overload visible in
  review.
- Fields are appended, never reordered. `componentN()` makes their order part of the ABI.

`copy()` on `PlayerState` stays. The constructor is public anyway, so `copy()` grants nothing new, and a
fabricated state is exactly what a Compose preview or a fake player needs. Engines produce `PlayerState`
and never consume it, so a fabricated one has no behaviour to guarantee.

## Consequences

Consumers keep `copy()` on `MediaItem` and the configuration types, and the guides can keep varying a
base item with `item.copy(startPositionMs = …)`.

After 1.0, every field added to a published data class costs two hidden overloads, and they accumulate
for as long as the class lives. That is the price of keeping `copy()`. If a class ever collects enough
of them to hurt, converting it away from `data` is a major-version change.

Until the ABI dump is checked in, the reorder rule and the overload rule are enforced by review alone.
