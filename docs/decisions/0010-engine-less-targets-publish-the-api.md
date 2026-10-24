# 10. The engine-less `jvm` and `wasmJs` targets are published, as API only

**Status:** Accepted

## Context

`:core` declares `jvm()` and `wasmJs()`, and neither has a playback engine: `VideoPlayer(context, config)`
throws `UnsupportedOperationException` on both. Both are part of the Maven Central publication
(`outis-core-jvm`, `outis-core-wasm-js`), and the root module metadata routes JVM and Wasm consumers to
them. A plain JVM application that depends on `outis-core` therefore compiles cleanly and fails on its
first `VideoPlayer(...)`.

The `jvm` target is also the only host-JVM runner for `commonTest`, and the fastest loop the project has.

The artifact set is frozen per version once published, so this had to be settled before
`0.1.0-alpha01`. Three ways out were weighed:

- **Keep the targets and drop them from publication.** The Kotlin Gradle plugin offers no supported way to
  remove a target's variants from the root module metadata, so a JVM consumer would fail at dependency
  resolution, naming a module that does not exist. That is an earlier failure, but it lands on every
  multiplatform consumer whose module has a `jvm()` or `wasmJs()` target — including the many that have
  one only to run their own shared tests on the JVM, and never construct a player there. They could no
  longer declare `outis-core` in `commonMain` at all.
- **Ship an engine.** Desktop was dropped, and a Wasm engine needs Shaka interop that does not exist yet.
  Neither fits `0.1.0-alpha01`.
- **Publish both and say so.**

## Decision

Both targets are published, as API-only artifacts. What they offer is the engine-agnostic API: shared
code that types against `VideoPlayer` and `PlayerState` compiles for every target a consumer has, and
tests on the JVM run against a fake `VideoPlayer`. `drmSupport()` reports nothing there, consistently.

The gap is stated where a consumer chooses a dependency and where the call fails:

- the README install snippet and the published-targets table in `docs/platform-support.md`;
- the `@throws` on `VideoPlayer(context, config)`;
- the artifact list in `docs/maintainers/releasing.md`.

## Consequences

A JVM or Wasm application can depend on `outis-core`, compile, and throw when it constructs a player. The
failure is at construction, with a message naming the target, but it is still a runtime failure that a
narrower artifact set would have made a build failure. That is the price of keeping `commonMain`
dependencies working for multiplatform consumers.

Removing either artifact later breaks resolution for everyone who compiled against it, so it is a
breaking change, not a clean-up.

`:ui` and `:analytics:mux` keep their narrower target sets. They draw or bind a native player, so an
engine-less variant of them would have nothing to offer. A `commonMain` dependency on either, from a module
that targets `jvm` or `wasmJs`, does not resolve; `docs/platform-support.md` says so.

A Wasm engine, if one lands, gives `outis-core-wasm-js` behaviour without changing its coordinates.
