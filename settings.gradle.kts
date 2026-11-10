/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
        // Compose Multiplatform artifacts.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        // Mux Data SDKs (analytics adapters only) — not on Maven Central. Scoped to the Mux group so it
        // is never consulted for anything else.
        maven("https://muxinc.jfrog.io/artifactory/default-maven-release-local") {
            content { includeGroupByRegex("com\\.mux(\\..*)?") }
        }
        // Lets the Kotlin/JS plugin download Node.js distributions when
        // repositoriesMode is restrictive.
        maven {
            name = "NodeJsDistributions"
            url = uri("https://nodejs.org/dist")
        }
    }
}

rootProject.name = "outis"

include(":core")
include(":ui")

// The samples, the analytics adapters and the BOM only when Outis is the build being run. An app that builds
// Outis from source (includeBuild, as Liberator does) substitutes just :core and :ui; with the adapters included,
// every IDE sync of that app also ran CocoaPods and Xcode builds of Firebase and Mux, and two syncs at once
// raced on the same synthetic Pods folder and left it half-installed.
if (gradle.parent == null) {
    include(":sample")
    include(":sample:android")
    include(":analytics:mux")
    include(":analytics:firebase")
    include(":bom")
}
