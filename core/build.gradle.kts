/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
}

group = "io.github.nonbinarydev"
version = "0.1.0-alpha01"

kotlin {
    // JVM is an API/test-only target — the core types compile here so unit tests run fast on
    // the JVM. There is no JVM playback engine (desktop was dropped); the factory throws.
    jvm()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    androidLibrary {
        namespace = "dev.nonbinary.outis.core"
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

    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    // XCFramework so iOS consumers (SwiftUI/UIKit) can drop in the core engine directly.
    val xcFrameworkName = "Outis"
    val xcf = XCFramework(xcFrameworkName)
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = xcFrameworkName
            isStatic = false
            xcf.add(this)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api`, not `implementation`: these two appear in this module's PUBLIC signatures, so
                // consumers need them on their COMPILE classpath. `implementation` publishes into
                // runtimeElements but not apiElements, so `player.state.collect { }` would fail with
                // "Cannot access class 'kotlinx.coroutines.flow.StateFlow'" unless the consumer happened
                // to declare the same library themselves.
                //   StateFlow / SharedFlow / CoroutineScope  -> VideoPlayer.state, .events, PlayerHost
                //   ImmutableList / ImmutableMap             -> PlayerState tracks + chapters, MediaItem.headers
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.collections.immutable)

                // These three appear in no public signature, so they stay implementation-scoped.
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.napier)
                // NOTE: no Compose, no Ktor, no engine deps. kotlinx-serialization-json is a pure data
                // lib (not an engine) for parsing provider ad-tracking JSON (MediaTailor avails) into the
                // engine-neutral ad model. Engines (Media3/AVPlayer/Shaka) live in the platform source sets.
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val androidMain by getting {
            dependencies {
                // `api` because exactly one public declaration exposes a Media3 type:
                // `VideoPlayer.setAdViewProvider(provider: AdViewProvider?)` (ExoPlayerEngine.kt), the
                // documented entry point for hosting the CSAI ad overlay. AdViewProvider lives in
                // media3-common, so only that artifact is promoted — the exoplayer artifacts below stay
                // implementation-scoped so the engine itself is not part of this module's API.
                api(libs.media3.common)

                // Media3 engine only — the Compose surface lives in :ui.
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.exoplayer.hls)
                implementation(libs.media3.exoplayer.dash)
                // Client-side ad insertion (CSAI): the Media3 IMA extension wraps Google IMA, which it
                // pulls in transitively (com.google.ads.interactivemedia.v3:interactivemedia).
                implementation(libs.media3.exoplayer.ima)
                // Media3's `Tracks` API returns a Guava `ImmutableList`; declare Guava explicitly so the
                // IDE's compile classpath resolves it (gradle already pulls this exact version transitively).
                implementation(libs.guava)
                // Provides Dispatchers.Main on Android (for the position ticker + component scope).
                implementation(libs.kotlinx.coroutines.android)
            }
        }

        // Declaring the custom webMain hierarchy below disables Kotlin's default hierarchy
        // template, so the iOS intermediate set must be wired explicitly too.
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }

        // webMain intermediate set shared by JS(IR) + Wasm.
        val webMain by creating {
            dependsOn(commonMain)
        }
        val jsMain by getting {
            dependsOn(webMain)
            dependencies {
                implementation(npm("shaka-player", "4.11.2"))
            }
        }
        val wasmJsMain by getting { dependsOn(webMain) }
    }
}

dependencies {
    // Rulesets referenced by config/detekt/detekt.yml ship as separate artifacts; without them detekt
    // aborts with "Property 'formatting' is misspelled or does not exist".
    detektPlugins(libs.detekt.formatting)
    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.compose2)
}

detekt {
    // KMP has no src/main/kotlin, so point detekt at the whole source tree explicitly or it
    // silently reports NO-SOURCE and analyses nothing.
    source.setFrom(files("src"))
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    // Fix formatting in place locally, but never on CI — a lint task that rewrites the runner's
    // checkout reports success against source the commit does not contain.
    autoCorrect = System.getenv("CI") == null
}

// SARIF feeds GitHub code scanning, which puts findings inline on the pull request diff rather than
// leaving them in a log nobody opens. The per-source-set tasks each emit their own file.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        sarif.required.set(true)
        html.required.set(true)
        txt.required.set(true)
        xml.required.set(false)
        md.required.set(false)
    }
}

dokka {
    moduleName.set("Outis Core")
    dokkaSourceSets.configureEach {
        // Surface every public declaration that lacks KDoc as a build warning.
        reportUndocumented.set(true)
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/nonbinarydev/outis/tree/main/core/src")
            remoteLineSuffix.set("#L")
        }
    }
}

apply(from = rootProject.file("gradle/local-signing.gradle.kts"))

mavenPublishing {
    coordinates(group.toString(), "outis-core", version.toString())

    // Real API docs rather than the plugin's empty placeholder javadoc jar. Central requires a
    // javadoc artifact; the artifact set is frozen per version once published.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))

    // Central Portal (the old OSSRH/Nexus flow is retired). Signing is required by Central; the key
    // comes from ~/.gradle/gradle.properties locally, or ORG_GRADLE_PROJECT_signingInMemoryKey in CI.
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "Outis — Core"
        description = "Engine-agnostic Kotlin Multiplatform video player core (Android/iOS/Web). No Compose, no UI."
        inceptionYear = "2026"
        url = "https://github.com/nonbinarydev/outis"

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "indieboyalex"
                name = "Alex Sutton"
                url = "https://github.com/indieboyalex"
            }
        }
        scm {
            url = "https://github.com/nonbinarydev/outis"
            connection = "scm:git:https://github.com/nonbinarydev/outis.git"
            developerConnection = "scm:git:ssh://git@github.com/nonbinarydev/outis.git"
        }
    }
}
