/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

import com.vanniktech.maven.publish.JavaPlatform

plugins {
    `java-platform`
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.nonbinarydev"
version = "0.1.0-alpha01"

// Every published module belongs here; a module left out drifts out of alignment silently.
dependencies {
    constraints {
        api(project(":core"))
        api(project(":ui"))
        api(project(":analytics:mux"))
    }
}

apply(from = rootProject.file("gradle/local-signing.gradle.kts"))

mavenPublishing {
    coordinates(group.toString(), "outis-bom", version.toString())
    configure(JavaPlatform())
    publishToMavenCentral()
    signAllPublications()

    pom {
        name = "Outis — BOM"
        description = "Aligns the versions of every Outis artifact, so consumers declare the version once."
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
