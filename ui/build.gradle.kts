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
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
}

group = "io.github.nonbinarydev"
version = "0.1.0-alpha01"

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    androidLibrary {
        namespace = "dev.nonbinary.outis.ui"
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

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.kotlinx.collections.immutable)
            }
        }
        val androidMain by getting {
            dependencies {
                // Android surface uses the View-based PlayerView (stable API; renders subtitle cues natively).
                implementation(libs.media3.ui)
                implementation(libs.media3.common)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.activity.ktx) // ComponentActivity PIP listener + PictureInPictureModeChangedInfo
            }
        }
        // iosMain (shared by both iOS targets) comes from the default hierarchy template — no custom
        // source sets here, so the template applies (unlike :core, which disables it via webMain).
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
    moduleName.set("Outis UI")
    dokkaSourceSets.configureEach {
        // Surface every public declaration that lacks KDoc as a build warning.
        reportUndocumented.set(true)
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/nonbinarydev/outis/tree/main/ui/src")
            remoteLineSuffix.set("#L")
        }
    }
}

apply(from = rootProject.file("gradle/local-signing.gradle.kts"))

mavenPublishing {
    coordinates(group.toString(), "outis-ui", version.toString())

    // Real API docs rather than the plugin's empty placeholder javadoc jar. Central requires a
    // javadoc artifact; the artifact set is frozen per version once published.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))

    // Central Portal (the old OSSRH/Nexus flow is retired). Signing is required by Central; the key
    // comes from ~/.gradle/gradle.properties locally, or ORG_GRADLE_PROJECT_signingInMemoryKey in CI.
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "Outis — UI"
        description = "Compose Multiplatform surface and customisable controls overlay for the Outis video player."
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
