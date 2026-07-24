# AGENTS.md

Guidance for AI coding agents working in this repository. Human contributors: see `CONTRIBUTING`
and the ADRs in `docs/decisions/`.

Outis is a Kotlin Multiplatform video player SDK — one engine-agnostic playback API across Android
(Media3), iOS (AVFoundation) and Web (Shaka), plus an optional Compose Multiplatform UI. Kotlin
2.4.10, AGP 9.1.1, `minSdk` 24, `compileSdk` 37.

## Modules

| Module | Published | What it is |
|---|---|---|
| `:core` | yes (`outis-core`) | The playback API and the three engines. No UI, no DI, no HTTP client. |
| `:ui` | yes (`outis-ui`) | Optional Compose Multiplatform surface and controls over `:core`. |
| `:sample` | no | The demo. Compose Multiplatform app: catalogue, custom-stream form, dialogs. |
| `:sample:android` | no | The installable Android host for `:sample` (the KMP Android plugin builds libraries only). |

`:core` uses a **custom source-set hierarchy** (`webMain` shared by `jsMain`/`wasmJsMain`), so its
default hierarchy template is disabled. `:ui` and `:sample` use the default template — reach `iosMain`
there with the typed accessor `iosMain.dependencies { }`, never `val iosMain by getting` (it is
materialised lazily and an eager lookup throws).

## Verifying a change — match the build to the blast radius

**Run the smallest build that proves the change, then stop.** Do not chain a full multi-target sweep as
a routine check.

- Changed one Android file → `:<module>:compileAndroidMain` (or `:sample:android:assembleDebug`).
- Changed a `commonMain` signature → compile the targets that actually consume it.
- **Do not compile the iOS/Native targets as a routine check** — they are the slowest tasks in the
  build, and recompiling them after an Android- or JS-only edit proves nothing.
- Always run `detekt` for the modules you touched; it is the gate. detekt covers every source set,
  including `iosMain`, which the other analysers cannot see.

detekt runs with `autoCorrect` on locally (off in CI, guarded by `System.getenv("CI")`). A first
`detekt` run may reformat and "fail"; the second pass is clean. Do not fight the formatter.

## Git — hands off

- **Never run `git commit`, `git push`, `git rebase`, tag creation, or any publish/deploy command.**
  Propose exact, copy-pasteable commands and let the maintainer run them.
- **Merge commits only.** Squash and rebase merging are disabled at the repo and ruleset level, and
  `required_linear_history` is deliberately off — it is incompatible with the `main` + `development`
  model and produces phantom conflicts (ADR-0005).
- Branches: `type/number-short-description`, cut from `development`, where `number` is the issue.
- Do not volunteer commit messages or `git add` lines unless asked. "Let me commit" means the
  maintainer is about to.

## Comments and docs

Write **fewer, shorter** comments than feels natural. A comment states a *live constraint* that will
break something if violated ("the linter must stay on 2026.2-eap or newer"), not the history behind a
decision. The story of what was tried belongs in the commit message or the issue, not the source.

## Version-catalog pins that are load-bearing

Some versions are held deliberately; read the comment before changing them.

- **AGP 9.1.1 is tied to the Qodana linter** (`qodana.yaml`, `2026.2-eap`). The older linter's IDE
  refused to import past AGP 9.0.0 and analysed nothing. Lower AGP only together with the linter.
- **`androidx.core` 1.19.0 and `lifecycle` 2.11.0 require `compileSdk` 37** (`checkAarMetadata` fails
  hard on 36). These move together with `android-compileSdk`.
- The **Maven Central search index lags** — it did not list Koin 4.2.2 while the artifact existed.
  When a tool disagrees about whether a version exists, fetch the `.pom` directly; do not trust the
  search API's list.

## The sample's shape (when working in `:sample`)

- One `PlayerView` call site, always. A second inside a mode branch disposes and recreates the
  surface on a tab switch — on web that tears the `<video>` out of the DOM.
- The catalogue is fetched at runtime from GitHub Pages, never bundled. `CatalogueRepository.load()`
  **never throws** — any failure degrades to a built-in stream, because a blank screen says nothing
  about whether the player works.
- The catalogue's own `CatalogueDrm`/`CatalogueItem` types are the single translation point to the
  SDK model (`toMediaItem()`). The custom-stream form builds one of *those*, not a `MediaItem`
  directly, so a typed-in stream behaves exactly like a curated one.
- `Json { ignoreUnknownKeys = true }` is set for forward-compatibility, which means a field the model
  forgets is **silently dropped, not a parse error**. Add every schema field to `Catalogue.kt`.

## Platforms and devices you cannot see

- On-device Android, a real iOS build, and browser behaviour are verified by the maintainer, not by
  you. State plainly what needs on-device or in-browser checking rather than claiming it works.
- iOS has no Xcode host in this repo yet, so the iOS engine is compile-verified only.
