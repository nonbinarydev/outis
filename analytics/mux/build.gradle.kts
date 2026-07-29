/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
    kotlin("native.cocoapods")
}

group = "io.github.nonbinarydev"
version = "0.1.0-alpha01"

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    androidLibrary {
        namespace = "dev.nonbinary.outis.analytics.mux"
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

    // Consumes Mux's iOS AVPlayer SDK for the iOS actual's cinterop bindings only. The Mux binary is
    // linked by the consuming app (the sample's iosApp), not embedded here — this module is a Gradle
    // dependency of :sample, whose framework does the final link.
    cocoapods {
        version = project.version.toString()
        summary = "Outis Mux analytics adapter"
        homepage = "https://github.com/nonbinarydev/outis"
        ios.deploymentTarget = "15.3"
        pod("Mux-Stats-AVPlayer") {
            version = "~> 4.15"
            // The pod's clang module is MUXSDKStats (what you `import` in Swift), not the sanitised
            // pod name the cinterop would otherwise look for.
            moduleName = "MUXSDKStats"
        }
    }

    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api`: MuxAnalytics implements PlayerComponent and is used through it, so :core's types
                // are on the consumer's classpath. The adapter never re-exports a vendor type.
                api(project(":core"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val androidMain by getting {
            dependencies {
                // The Mux Data SDK for AndroidX Media3. monitorWithMuxData() is an extension on the Media3
                // Player — which is exactly what :core exposes as nativePlayerHandle on Android.
                implementation(libs.mux.data.media3)
                // For the ExoPlayer type the handle is cast to.
                implementation(libs.media3.exoplayer)
            }
        }

        // Typed accessor, not `by getting`: iosMain is materialised lazily by the hierarchy template.
        iosMain.dependencies {
            // No vendor dependency yet — the iOS actual is a stub (see MuxAnalytics.ios.kt). The real Mux
            // AVPlayer SDK is CocoaPods/SPM and cannot be built or verified on this or a Linux runner.
        }

        val jsMain by getting {
            dependencies {
                implementation(npm("mux-embed", libs.versions.muxEmbed.get()))
            }
        }
    }
}

mavenPublishing {
    coordinates(group.toString(), "outis-analytics-mux", version.toString())
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "Outis — Mux Analytics"
        description = "Optional Mux Data QoS adapter for the Outis video player. Binds Mux's native SDK to the player."
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

detekt {
    source.setFrom(files("src"))
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    autoCorrect = System.getenv("CI") == null
}

dependencies {
    // The shared config/detekt/detekt.yml references the Compose ruleset, so every module must supply
    // the rule providers even when it has no Compose — detekt validates the whole config or aborts.
    detektPlugins(libs.detekt.formatting)
    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.compose2)
}
