/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.detekt)
}

// Deliberately NOT published: no maven-publish plugin, no group or version. This module exists to be
// run and read, not consumed.

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    androidLibrary {
        namespace = "dev.nonbinary.outis.sample"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }
        }
    }

    iosArm64()
    iosSimulatorArm64()

    // `binaries.executable()`, unlike :core and :ui which publish libraries — this target produces the
    // web demo bundle that the Pages workflow publishes.
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "sample.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation(project(":ui"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)
                // Generates typed accessors for src/commonMain/composeResources — the lockup shown
                // above the player.
                implementation(compose.components.resources)
            }
        }
    }
}

// Without this the accessor package is derived from the project path ("outis.sample.generated
// .resources"), which does not match the module's own package and reads as a mistake in imports.
compose.resources {
    packageOfResClass = "dev.nonbinary.outis.sample.generated.resources"
}

detekt {
    // KMP has no src/main/kotlin, so point detekt at the whole source tree explicitly or it
    // silently reports NO-SOURCE and analyses nothing.
    source.setFrom(files("src"))
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    autoCorrect = System.getenv("CI") == null
}

dependencies {
    detektPlugins(libs.detekt.formatting)
    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.compose2)
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        sarif.required.set(true)
        html.required.set(true)
        txt.required.set(true)
        xml.required.set(false)
        md.required.set(false)
    }
}
