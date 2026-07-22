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
