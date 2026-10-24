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
#
# It reads three keychain items; create them once (see docs/maintainers/releasing.md):
#
#     security add-generic-password -a "$USER" -s outis-signing -w
#     security add-generic-password -a "$USER" -s outis-central-username -w
#     security add-generic-password -a "$USER" -s outis-central-password -w
#
# No `set -u` here: this file is sourced, and shell options would outlive it in your shell.

outis_keychain() {
  security find-generic-password -s "$1" -w 2>/dev/null || {
    echo "publish-env: keychain item '$1' not found." >&2
    echo "  add it with: security add-generic-password -a \"\$USER\" -s $1 -w" >&2
    return 1
  }
}

# Locals keep the secrets out of your shell, and nothing is exported until every lookup succeeds.
# Each is assigned apart from its `local`/`export`: `export V="$(cmd)"` reports export's own status,
# so a failed lookup would slip through as an empty value.
outis_publish_env() {
  local key_file key key_password central_username central_password
  key_file="${OUTIS_SIGNING_KEY_FILE:-$HOME/.secrets/outis-signing-key.asc}"
  if [ ! -f "$key_file" ]; then
    echo "publish-env: signing key not found at $key_file" >&2
    echo "  export it with:" >&2
    echo "  gpg --armor --export-secret-keys 758DFB45CEC69E33 > $key_file && chmod 600 $key_file" >&2
    return 1
  fi
  key="$(cat "$key_file")" || return 1
  key_password="$(outis_keychain outis-signing)" || return 1
  central_username="$(outis_keychain outis-central-username)" || return 1
  central_password="$(outis_keychain outis-central-password)" || return 1
  export ORG_GRADLE_PROJECT_signingInMemoryKey="$key"
  export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$key_password"
  export ORG_GRADLE_PROJECT_mavenCentralUsername="$central_username"
  export ORG_GRADLE_PROJECT_mavenCentralPassword="$central_password"
}

if outis_publish_env; then
  unset -f outis_publish_env outis_keychain
  echo "publish-env: signing key 758DFB45CEC69E33 loaded; Central credentials set."
  echo "publish-env: verify the dry run signs with 758DFB45CEC69E33, not another key on this machine:"
  echo "    ./gradlew publishToMavenLocal --no-configuration-cache"
  echo "    gpg --verify ~/.m2/repository/io/github/nonbinarydev/outis-core/*/*.pom.asc"
else
  unset -f outis_publish_env outis_keychain
  return 1 2>/dev/null || exit 1
fi
