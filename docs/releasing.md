# Releasing

Outis publishes to Maven Central through the [Sonatype Central Portal](https://central.sonatype.com)
under the namespace `io.github.nonbinarydev`.

| Artifact | Coordinate |
|---|---|
| `:core` | `io.github.nonbinarydev:outis-core` |
| `:ui` | `io.github.nonbinarydev:outis-ui` |

There are **two credential paths**, and it is worth understanding why before setting either up:

| | Signing (local builds, IDE) | Central upload (real releases) |
|---|---|---|
| Supplied by | `secrets.properties` (gitignored) | environment variables |
| Works from Android Studio | ✅ | ❌ — GUI apps don't inherit a shell |
| Needed for | `publishToMavenLocal` | `publishAndReleaseToMavenCentral` |

The split exists because the publish plugin reads its Central credentials through
`providers.gradleProperty(...)`, which only observes **real** Gradle properties — `gradle.properties`,
`-P`, or `ORG_GRADLE_PROJECT_*` environment variables. It does *not* observe properties set
programmatically by a build script, so a file cannot supply them. Signing is different: the build
configures the `signing` extension directly (`gradle/local-signing.gradle.kts`), bypassing that lookup
entirely, which is what lets a file work for signing and lets the IDE sign without any shell setup.

---

## One-time setup

### 1. A signing key

Central requires every artifact to be PGP-signed. The key's user ID is embedded in the signature and
published to a keyserver, so **pick an identity you are happy to publish permanently**. Use a key
dedicated to this project rather than an unrelated work or personal one.

```bash
gpg --quick-generate-key "Outis <you@example.com>" rsa4096 sign 2y
gpg --list-secret-keys --keyid-format=long                    # note the key id

# Publish the public half — Central verifies signatures against public keyservers.
gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>

# Export the private half for Gradle; keep it outside the repository.
mkdir -p ~/.secrets && chmod 700 ~/.secrets
gpg --armor --export-secret-keys <FINGERPRINT> > ~/.secrets/outis-signing-key.asc
chmod 600 ~/.secrets/outis-signing-key.asc
```

> Use `keyserver.ubuntu.com`, not `keys.openpgp.org` — the latter strips user IDs unless you verify the
> address by email, which is impossible for a `users.noreply.github.com` address.

Uploads are not instant. Confirm the key is retrievable before releasing:

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x<LONG-KEY-ID>"     # 200 = live
```

### 2. A Central Portal token

central.sonatype.com → *Account* → *Generate User Token*. This produces a username/password pair that
is **not** your portal login.

### 3. `secrets.properties` — for signing

Create this in the repo root. It is gitignored.

```properties
signingKeyFile=/Users/you/.secrets/outis-signing-key.asc
signingInMemoryKeyId=758DFB45CEC69E33
signingInMemoryKeyPassword=<key passphrase>
```

```bash
chmod 600 secrets.properties
```

The key id may be given short, long, or as a full fingerprint — the build normalises it to the
8-character form Gradle's signing plugin requires.

This is what makes signing work identically from a terminal and from Android Studio's Gradle window.
The trade-off is a passphrase in plaintext on disk; keep the file `600`, and note the private key lives
in a separate directory, so an attacker needs both.

### 4. Environment variables — for the actual release

Only needed when uploading to Central. `scripts/publish-env.sh` holds references rather than secrets
and is safe to read:

```bash
source scripts/publish-env.sh
```

Or export the two Central variables by hand:

```bash
export ORG_GRADLE_PROJECT_mavenCentralUsername="<token username>"
export ORG_GRADLE_PROJECT_mavenCentralPassword="<token password>"
```

In CI, set the same names as repository secrets, plus `ORG_GRADLE_PROJECT_signingInMemoryKey`,
`…KeyId` and `…KeyPassword` — environment variables take precedence over `secrets.properties`, so CI
needs no local file.

---

## Publishing

The configuration cache is enabled for ordinary builds but is **not supported** for Central publishing,
so release tasks need `--no-configuration-cache`. Android Studio's Gradle window will not add that flag
for you — run the release from a terminal.

### Dry run — always do this first

```bash
./gradlew publishToMavenLocal --no-configuration-cache
```

Then confirm *which key* actually signed:

```bash
gpg --verify ~/.m2/repository/io/github/nonbinarydev/outis-core/*/*.pom.asc
```

Expect the project key:

```
gpg: Good signature from "Outis <…>"
gpg:                using RSA key 758DFB45CEC69E33
```

If another key on your machine is named instead, stop — `secrets.properties` is not being picked up.
If it reports **"no configured signatory"**, the signing credentials are missing entirely; note that
this failure is loud rather than silent, so a misconfigured build cannot quietly sign with the wrong
key.

### Release

```bash
source scripts/publish-env.sh
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

Use `publishToMavenCentral` instead to upload without releasing, then inspect and release the
deployment manually in the Portal UI — worth doing while the process is new.

> Apple targets (`iosArm64`, `iosSimulatorArm64`) can only be built on macOS. Publish the whole set from
> a Mac; splitting across machines produces two separate, incomplete deployments.

---

## Versioning

The version lives in `core/build.gradle.kts` and `ui/build.gradle.kts`, currently `0.1.0-alpha01`.

**Published coordinates are immutable.** A version can never be re-uploaded or amended, so the artifact
set for a version — which targets ship, whether javadoc jars are real or empty — is frozen at first
publish. Decide those before releasing, not after.
