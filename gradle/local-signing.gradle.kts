/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

// Configures PGP signing from a gitignored `secrets.properties` in the repo root.
//
// WHY THIS EXISTS: signing credentials cannot live in this project's gradle.properties (it is
// committed) nor be overridden from it (Gradle resolves ~/.gradle/gradle.properties at HIGHER
// precedence). Environment variables solve that for a terminal, but macOS GUI applications do not
// inherit a login shell's environment — so Android Studio's Gradle window would never see them.
//
// WHY IT CONFIGURES `signing` DIRECTLY rather than setting Gradle properties: the publish plugin reads
// its credentials through `providers.gradleProperty(...)`, which does NOT observe extra properties set
// programmatically by a build script. Only real Gradle properties (gradle.properties, -P, or
// ORG_GRADLE_PROJECT_* env vars) are visible to it. Configuring the signing extension bypasses that
// lookup entirely and gives the signing tasks a signatory.
//
// PRECEDENCE: if the credentials are already supplied as real Gradle properties — an env var in CI, for
// instance — this does nothing and lets those win.
//
// secrets.properties (gitignored; chmod 600):
//
//     signingKeyFile=/Users/you/.secrets/outis-signing-key.asc
//     signingInMemoryKeyId=758DFB45CEC69E33
//     signingInMemoryKeyPassword=...
//     mavenCentralUsername=...        # only needed for a real Central release
//     mavenCentralPassword=...

val secretsFile = rootProject.file("secrets.properties")
val alreadyConfigured = providers.gradleProperty("signingInMemoryKey").isPresent

if (secretsFile.exists() && !alreadyConfigured) {
    val secrets = java.util.Properties().apply { secretsFile.inputStream().use { load(it) } }

    val keyPath = secrets.getProperty("signingKeyFile")?.replaceFirst("~", System.getProperty("user.home"))
    val keyFile = keyPath?.let(::File)
    val password = secrets.getProperty("signingInMemoryKeyPassword")

    // Gradle's signing plugin requires the SHORT (8-character) key id. Accept a long key id or a full
    // 40-character fingerprint in secrets.properties and normalise, so any form from
    // `gpg --list-secret-keys` works.
    val keyId = secrets.getProperty("signingInMemoryKeyId")
        ?.trim()
        ?.removePrefix("0x")
        ?.takeIf { it.isNotBlank() }
        ?.takeLast(8)

    when {
        keyFile == null ->
            logger.warn("local-signing: secrets.properties has no signingKeyFile entry; artifacts will not be signed.")

        !keyFile.exists() ->
            logger.warn("local-signing: signingKeyFile points at $keyFile, which does not exist; artifacts will not be signed.")

        password.isNullOrBlank() ->
            logger.warn("local-signing: signingInMemoryKeyPassword is missing; artifacts will not be signed.")

        else -> {
            // The publish plugin applies `signing` lazily (inside signAllPublications()), which runs
            // after this script — so react to the plugin arriving rather than assuming it is present.
            plugins.withId("signing") {
                extensions.configure<SigningExtension>("signing") {
                    // keyId may be null — useInMemoryPgpKeys then uses the key's own id.
                    useInMemoryPgpKeys(keyId, keyFile.readText(), password)
                }
                logger.lifecycle("local-signing: signing ${project.name} with key ${keyId ?: "<from key file>"}")
            }
        }
    }
}
