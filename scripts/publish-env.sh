#!/usr/bin/env bash
#
# Source this before publishing:
#
#     source scripts/publish-env.sh
#     ./gradlew publishToMavenLocal --no-configuration-cache
#
# It contains NO secrets — only references to the macOS keychain and to a private
# key kept outside the repository. It is therefore safe to commit.
#
# Why environment variables rather than gradle.properties: Gradle resolves
# ~/.gradle/gradle.properties at HIGHER precedence than a project's own
# gradle.properties, so a globally-configured signing key cannot be overridden
# from inside this repo. Environment variables can. See docs/releasing.md.
#
# Do NOT put these in ~/.zprofile or ~/.zshrc — that would apply them to every
# Gradle build on this machine, including unrelated projects.

set -u

KEY_FILE="${OUTIS_SIGNING_KEY_FILE:-$HOME/.secrets/outis-signing-key.asc}"
EXPECTED_KEY_ID="758DFB45CEC69E33"

if [ ! -f "$KEY_FILE" ]; then
  echo "publish-env: signing key not found at $KEY_FILE" >&2
  echo "  export it with:" >&2
  echo "  gpg --armor --export-secret-keys $EXPECTED_KEY_ID > $KEY_FILE && chmod 600 $KEY_FILE" >&2
  return 1 2>/dev/null || exit 1
fi

keychain() {
  security find-generic-password -s "$1" -w 2>/dev/null || {
    echo "publish-env: keychain item '$1' not found." >&2
    echo "  add it with: security add-generic-password -a \"\$USER\" -s $1 -w" >&2
    return 1
  }
}

export ORG_GRADLE_PROJECT_signingInMemoryKey="$(cat "$KEY_FILE")"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$(keychain outis-signing)" || return 1 2>/dev/null || exit 1
export ORG_GRADLE_PROJECT_mavenCentralUsername="$(keychain outis-central-username)"          || return 1 2>/dev/null || exit 1
export ORG_GRADLE_PROJECT_mavenCentralPassword="$(keychain outis-central-password)"          || return 1 2>/dev/null || exit 1

echo "publish-env: signing key $EXPECTED_KEY_ID loaded; Central credentials set."
echo "publish-env: verify the dry run signs with $EXPECTED_KEY_ID, not another key on this machine:"
echo "    ./gradlew publishToMavenLocal --no-configuration-cache"
echo "    gpg --verify ~/.m2/repository/io/github/nonbinarydev/outis-core/*/*.pom.asc"
