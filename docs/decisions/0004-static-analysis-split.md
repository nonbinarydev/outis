# 4. Three analysers, with distinct responsibilities

**Status:** Accepted

## Context

The project runs detekt, Qodana and SonarCloud. Left undivided they report the same Kotlin rules three
times, and three tools disagreeing about one line is worse than one tool being wrong.

Their reach differs sharply, and not in the way their marketing suggests:

- **detekt** runs against every source set, including `iosMain`, `jsMain` and `wasmJsMain`. It is the
  only analyser that sees the Kotlin/Native and Kotlin/JS code at all.
- **Qodana** cannot resolve Kotlin/Native. Its Android-capable linters are Docker/Linux-only, and the
  cinterop-derived `platform.*` libraries exist only on macOS with Xcode. Worse than not analysing
  them, it parses them and reports every import as unresolved — so `iosMain` is excluded by path.
  JetBrains' own tracker records this: QD-718 closed *Obsolete*, QD-9206 closed *Won't fix* with
  "Qodana doesn't fully support KMP".
- **SonarCloud** runs Automatic Analysis, which cannot ingest coverage at all.

A second trap: detekt's aggregate `detekt` task runs **without type resolution**, so a dozen enabled
rules never execute. A green aggregate run is not evidence the code is clean.

## Decision

detekt owns formatting and complexity, and is the only analyser required to pass in CI. It runs
per-source-set, including the type-resolution tasks (`detektJvmMain`, `detektAndroidMain`) which are the
only ones that execute the type-dependent rules.

Qodana runs advisory, on `qodana-jvm-android` because the JVM-community image ships no Android plugin
and reports every `expect` in `:ui` as having no `actual`. `iosMain` is excluded by path.

SonarCloud adds what neither does: duplication detection, security hotspots, the maintainability rating
and the Clean-as-You-Code new-code gate. Coverage is excluded rather than reported as zero.

## Consequences

Each tool is required to be right about something no other tool covers, and none is trusted outside it.

CI cost is three workflows. For a public repository the minutes are free, and the showcase value of
demonstrating the integrations is part of why they are there.

Where a finding is a false positive it is suppressed **at the site with the reasoning written out** —
`KotlinArrayHashCode` on `LicenseRequest.hashCode`, `UnreachableCode` on `selectTrack` — rather than by
lowering a threshold globally, so new occurrences still surface.
