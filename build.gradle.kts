/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.report.ReportMergeTask

plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    // Declared here so :sample:android can apply it without a version — a subproject cannot
    // version a plugin that is already on the build classpath. AGP 9 provides Kotlin support
    // built in, so no separate kotlin-android plugin is needed.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.jetbrainsCompose) apply false
    alias(libs.plugins.kotlinComposeCompiler) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
    alias(libs.plugins.detekt) apply false
}

/**
 * Merges every per-source-set SARIF into one file with a single `run`.
 *
 * Each detekt task emits its own SARIF, and since July 2025 GitHub code scanning rejects an upload
 * carrying several runs under one category. Uploading the reports directory therefore fails with
 * "does not support uploading multiple SARIF runs with the same category". Merging first is the fix.
 */
val detektMergeSarif by tasks.registering(ReportMergeTask::class) {
    output.set(layout.buildDirectory.file("reports/detekt/merged.sarif"))
}

subprojects {
    tasks.withType<Detekt>().configureEach {
        finalizedBy(detektMergeSarif)
    }
    detektMergeSarif.configure {
        input.from(tasks.withType<Detekt>().map { it.sarifReportFile })
    }
}
