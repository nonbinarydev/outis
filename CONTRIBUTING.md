# Contributing to Outis

Thanks for looking. Outis is maintained by one person, so a little context on what that means in
practice: issues and pull requests are read, but not always quickly, and a large unsolicited change is
more likely to stall than a small one. If you are planning anything substantial, open an issue first —
not as a formality, but because the answer may be "that belongs in your app, not the SDK", and it is
better to hear that before writing it.

## Building

You need:

- **JDK 21.** Gradle 9.6.1 will not run on an older one.
- **Android SDK** with `compileSdk` 36, for the Android targets.
- **macOS with Xcode**, for the Apple targets. `iosArm64` and `iosSimulatorArm64` cannot be built on
  Linux or Windows — there is no `iosX64`, so an Intel Mac cannot build the simulator target either.

Everything else comes from the Gradle wrapper.

```bash
./gradlew build          # assemble and test
./gradlew :core:allTests # the full multiplatform test suite
./gradlew :core:jvmTest   # just the JVM run — much faster while iterating
```

`:core:allTests` runs the shared `commonTest` suite against every target that can host it, so the same
tests execute on JVM, iOS simulator, Node, browser and Wasm. That is the point of putting the parsers
and the ad controller in `commonMain`: they are tested everywhere they ship.

On a non-Mac, expect the Apple test targets to be unavailable. That is normal; CI covers them.

## Linting

Formatting and static analysis are detekt's job, with ktlint's rules included via detekt's `formatting`
ruleset. There is no separate ktlint plugin.

```bash
./gradlew detekt                 # both modules, no type resolution
./gradlew :core:detektJvmMain    # WITH type resolution
./gradlew :core:detektAndroidMain # WITH type resolution
```

**The plain `detekt` task does not run every rule you have enabled.** Rules that need type resolution —
`UnnecessaryNotNullOperator`, `RedundantSuspendModifier`, `IgnoredReturnValue`, the `UnusedPrivate*`
family and others — are inert without it, and type resolution is only available for the JVM and Android
targets. A green `detekt` is necessary but not sufficient; run the per-target tasks before opening a
pull request.

`autoCorrect` is enabled locally and disabled whenever the `CI` environment variable is set, so a CI
run reports rather than rewriting the checkout. If you want a read-only local run, `CI=true ./gradlew
detekt`.

Style comes from `.editorconfig` — 120 columns, four spaces, explicit imports, trailing commas.
Configuration lives in `config/detekt/detekt.yml`; where a rule is tuned there is a comment saying why.
If a rule fights you, argue with the comment in an issue rather than adding a bare `@Suppress`.

## Documentation

Public declarations carry KDoc. Dokka runs with `reportUndocumented`, so a missing doc comment shows
up as a warning.

Prose documentation lives in `docs/`. Two rules that matter more than style:

- **Every capability claim goes in [docs/platform-support.md](docs/platform-support.md) and is linked
  from elsewhere**, not restated. The single hardest thing to keep true in this project is which
  platform can do what.
- **Verify examples against the source before writing them.** Documentation that does not compile is
  worse than none.

## The additive API rule

`outis-core` is published, so its API has to stay additive. Concretely:

- **New `VideoPlayer` methods must have default bodies.** Making one abstract is a hard source break
  for every implementer, including `FakeVideoPlayer` in the test suite. This is enforced by
  `VideoPlayerAdditiveContractTest` — if you make a method abstract, that test fails, and it is telling
  you something rather than being in the way.
- **New `PlayerState` and config fields arrive with defaults.**
- At alpha, source-additive changes may still be *binary*-incompatible, because adding a field to a
  data class changes `copy` and `componentN` arity. Consumers recompile. This is called out in
  [CHANGELOG.md](CHANGELOG.md) whenever it happens.

## Pull requests

- Branch from `main`.
- Keep the change focused; a PR that fixes a bug and reformats a file is two PRs.
- Run `./gradlew build` and the type-resolution detekt tasks locally first.
- Explain what you observed, not just what you changed — especially for anything touching an engine,
  since much of that behaviour cannot be unit-tested and rests on device verification.
- Say which platforms you actually tested on. "Android only" is a fine answer; silently implying all
  three is not.

By contributing you agree your work is licensed under [Apache-2.0](LICENSE), matching the project.

## Reporting bugs

[Open an issue](https://github.com/nonbinarydev/outis/issues) with the platform, the engine's own error
code (`PlayerError.code`), and whether the same stream plays elsewhere. For a playback problem, a URL
that reproduces it is worth more than any description — if it is behind auth, say so and describe the
packaging (container, codec, DRM scheme).

Security vulnerabilities go through [SECURITY.md](SECURITY.md), not the public tracker.

## Releasing

Releases are cut by the maintainer, on the machine holding the signing key. The runbook is
[docs/maintainers/releasing.md](docs/maintainers/releasing.md); it is documented for continuity rather
than because contributors need it.
