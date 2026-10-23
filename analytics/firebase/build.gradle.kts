/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

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
    explicitApi()

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    androidLibrary {
        namespace = "dev.nonbinary.outis.analytics.firebase"
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

    // Firebase for the iOS actual's cinterop only; the consuming app links Firebase and calls
    // FirebaseApp.configure(). Firebase's headers import modules, which cinterop parses only with -fmodules.
    cocoapods {
        version = project.version.toString()
        summary = "Outis Firebase usage-analytics adapter"
        homepage = "https://github.com/nonbinarydev/outis"
        ios.deploymentTarget = "15.3"
        pod("FirebaseCore") {
            version = "~> 12.19"
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
        pod("FirebaseAnalytics") {
            version = "~> 12.19"
            extraOpts += listOf("-compiler-option", "-fmodules")
        }
    }

    // The web binding calls the page's gtag.js, so no npm dependency.
    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api`: FirebaseUsageAnalytics is used through PlayerComponent, so :core's types are on the
                // consumer's classpath. The adapter never re-exports a vendor type.
                api(project(":core"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(project.dependencies.platform(libs.firebase.bom))
                implementation(libs.firebase.analytics)
                // FirebaseApp, to tell whether the app configured Firebase before logging.
                implementation(libs.firebase.common)
            }
        }
    }
}

dokka {
    moduleName.set("Outis Firebase Analytics")
    dokkaSourceSets.configureEach {
        reportUndocumented.set(true)
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/nonbinarydev/outis/tree/main/analytics/firebase/src")
            remoteLineSuffix.set("#L")
        }
    }
}

apply(from = rootProject.file("gradle/local-signing.gradle.kts"))

mavenPublishing {
    coordinates(group.toString(), "outis-analytics-firebase", version.toString())
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "Outis — Firebase Analytics"
        description = "Optional usage-analytics adapter for the Outis video player: Google Analytics 4 through " +
            "Firebase on Android and iOS, and gtag.js on the web."
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
    detektPlugins(libs.detekt.formatting)
    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.compose2)
}
