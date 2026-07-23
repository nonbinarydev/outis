/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */
import javax.inject.Inject

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.detekt)
}

// The Android host for the shared sample. All the UI lives in :sample — this module exists only to
// give it an Activity to run in, because the Kotlin Multiplatform Android plugin builds libraries and
// cannot produce an installable application.

/**
 * Stages :sample's Compose resources into this application's assets.
 *
 * This works around a real gap rather than a misconfiguration. The Compose resources plugin *registers*
 * `copyAndroidMainComposeResourcesToAndroidAssets` in :sample, but only *configures* it from its
 * integration with the legacy `com.android.library` plugin, which hooks the variant's asset
 * directories. :sample uses `com.android.kotlin.multiplatform.library`, where that hook never fires —
 * so the task is left with no `outputDirectory`, nothing ever joins the AGP asset merge, and the
 * drawable is simply absent from the AAR. Nothing fails at build time; `painterResource` throws
 * `MissingResourceException` on first composition instead.
 *
 * The package segment is not decoration. The runtime reader resolves
 * `composeResources/<packageOfResClass>/…`, so [resourcePackage] has to track the `packageOfResClass`
 * set in :sample's build file, or this will copy happily and the lookup will still miss.
 */
abstract class StageComposeResources : DefaultTask() {
    /**
     * Prepared resource roots, one per source set. Declared as a collection rather than a directory so
     * that a root which does not exist yet resolves to empty instead of failing the build — androidMain
     * has no resources today, and should not have to before it can be listed.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val preparedResourceRoots: ConfigurableFileCollection

    /** Must equal :sample's `packageOfResClass`. */
    @get:Input
    abstract val resourcePackage: Property<String>

    /** Set by AGP via `addGeneratedSourceDirectory`; do not assign it here. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun stage() {
        // sync, not copy: a resource removed from :sample should disappear from the assets rather than
        // linger from a previous build and mask its own deletion.
        files.sync {
            from(preparedResourceRoots)
            into(outputDirectory.dir("composeResources/${resourcePackage.get()}"))
        }
    }
}

val stageComposeResources by tasks.registering(StageComposeResources::class) {
    description = "Stages :sample's Compose resources where the Android resource reader expects them."
    val prepared = project(":sample").layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources")
    dependsOn(":sample:prepareComposeResourcesTaskForCommonMain")
    dependsOn(":sample:prepareComposeResourcesTaskForAndroidMain")

    // androidMain is listed alongside commonMain so that adding a platform-specific resource later does
    // not silently reintroduce the crash this task exists to fix.
    preparedResourceRoots.from(prepared.map { it.dir("commonMain/composeResources") })
    preparedResourceRoots.from(prepared.map { it.dir("androidMain/composeResources") })
    resourcePackage.set("dev.nonbinary.outis.sample.generated.resources")
}

// The Variant API rather than `sourceSets["main"].assets.srcDir(...)`: AGP rejects a Provider there,
// because it cannot tell generated (read-only) from static (read-write) content. This route both
// declares the directory and carries the task dependency, so the assets merge waits for the staging.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            stageComposeResources,
            StageComposeResources::outputDirectory,
        )
    }
}

android {
    namespace = "dev.nonbinary.outis.sample.android"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.nonbinary.outis.sample"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        versionCode = 1
        versionName = "0.1.0-alpha01"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Required by media3-exoplayer-ima and the Google IMA SDK it pulls in, both of which use Java 8+
        // APIs that minSdk 24 does not provide. The AAR metadata check fails without it. Note :core does
        // not need this — the requirement is transitive and lands on the application module.
        isCoreLibraryDesugaringEnabled = true
    }

    buildTypes {
        release {
            // Unsigned and unminified: this is a demonstration, not a shipped artifact.
            isMinifyEnabled = false
        }
    }

}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":sample"))
    implementation(libs.androidx.activity.compose)
    detektPlugins(libs.detekt.formatting)
    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.compose2)
}

detekt {
    source.setFrom(files("src"))
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    autoCorrect = System.getenv("CI") == null
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
