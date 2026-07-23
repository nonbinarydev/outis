[Outis](../README.md) › [Docs](README.md) › Getting started on Android

# Getting started on Android

Ten minutes from an empty `com.android.application` module to a playing video. Nothing here assumes
Kotlin Multiplatform — Outis publishes an Android variant of each artifact, so a plain Android app
depends on it like any other library. If you *are* building a KMP shared module, everything on this
page still applies to your `androidApp`; only the dependency declaration moves to `commonMain`.

> **Not yet published.** `0.1.0-alpha01` has not been released to Maven Central, so the coordinates
> below will not resolve until the first release.

---

## 1. Requirements

| | Value | Why |
|---|---|---|
| `minSdk` | **24** or higher | `gradle/libs.versions.toml:4`, applied at `core/build.gradle.kts:38` |
| `compileSdk` | **36** or higher | Media3 1.10.1 AARs declare `minCompileSdk=36` |
| Java / Kotlin JVM target | **11** or higher | Outis' Android classes are Java 11 bytecode (class-file major version 55) |
| Android Gradle Plugin | **8.2.0** or higher | required by the Google IMA AAR that Media3's IMA extension pulls in |
| Kotlin | **2.4.10** recommended | Outis is built with it (`gradle/libs.versions.toml:3`) |

`compileSdk` and the JVM target are the two that bite first. A `compileSdk` below 36 fails AAR
metadata validation on sync; a Kotlin `jvmTarget` below 11 fails at compile time, because Kotlin
refuses to inline Java 11 bytecode into a lower target.

---

## 2. Core-library desugaring is required

**Yes, you must enable core-library desugaring.** This is not optional and it is not conditional on
whether you use ads.

`:core` applies the Media3 IMA extension unconditionally to its Android source set
(`core/build.gradle.kts:105`, `implementation(libs.media3.exoplayer.ima)`), so every Android consumer
of `outis-core` gets it. Two artifacts in that chain declare the requirement in their AAR metadata,
and the Android Gradle Plugin enforces it:

- `androidx.media3:media3-exoplayer-ima:1.10.1` — `coreLibraryDesugaringEnabled=true`,
  `desugarJdkLib=com.android.tools:desugar_jdk_libs:2.1.5`
- `com.google.ads.interactivemedia.v3:interactivemedia:3.39.0` (pulled in transitively) —
  `coreLibraryDesugaringEnabled=true`, `desugarJdkLib=com.android.tools:desugar_jdk_libs:2.1.3`

Take the higher of the two: **`desugar_jdk_libs:2.1.5` or newer**. Without it the build fails on the
first sync at `:app:checkDebugAarMetadata`, before any of your code is compiled. No other Media3
artifact Outis uses requires it (`media3-exoplayer`, `-hls`, `-dash`, `-ui` and `-common` all declare
`coreLibraryDesugaringEnabled=false`), so IMA is the sole cause.

The root README's "no extra player config" line and the claim in the ads guide that desugaring is an
ads-only concern are both wrong; this page is the source of truth.

---

## 3. Repositories

Keep both. `google()` serves AndroidX, Media3 and the IMA SDK; `mavenCentral()` serves Outis itself
and the kotlinx libraries.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

---

## 4. Dependencies

```kotlin
// app/build.gradle.kts
android {
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true      // required — see section 2
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation("io.github.nonbinarydev:outis-core:0.1.0-alpha01")
    implementation("io.github.nonbinarydev:outis-ui:0.1.0-alpha01")   // optional Compose UI

    // Outis scopes these implementation(), but both appear in its public API signatures,
    // so you must declare them yourself. See the note below.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
```

**Why the two kotlinx lines.** `core/build.gradle.kts:80-81` declares kotlinx-coroutines and
kotlinx-collections-immutable with `implementation(...)`, not `api(...)`, so they are not on your
compile classpath — but `VideoPlayer.state` is a `StateFlow<PlayerState>`, `VideoPlayer.events` is a
`SharedFlow<PlayerEvent>` (`VideoPlayer.kt:36,42`), and `MediaItem.headers` is an
`ImmutableMap<String, String>` (`MediaItem.kt:36`). You cannot collect state or set a request header
without them.

`outis-ui` is optional. `:core` is a complete player API; `:ui` is one opinionated Compose chrome on
top of it. If you skip it, see section 8.

Using `outis-ui` also means your app is a Compose app: apply the
`org.jetbrains.kotlin.plugin.compose` plugin and add your own Compose UI dependencies as usual.

---

## 5. Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    ...
</manifest>
```

Neither `:core` nor `:ui` ships an `AndroidManifest.xml` of its own — every permission you end up
with comes from a transitive AAR. That means `INTERNET` currently arrives anyway, merged in from the
IMA AAR, but declare it yourself: it is your app's requirement, not a detail to inherit from an ad
SDK you may not be using.

### What Outis adds to your merged manifest

Worth knowing before you fill in a Play Data safety form, because none of it is your code:

| From | Merged in |
|---|---|
| `media3-exoplayer` | `ACCESS_NETWORK_STATE`, `WAKE_LOCK` |
| `interactivemedia` (via the Media3 IMA extension) | `INTERNET`, `com.google.android.gms.permission.AD_ID`, `ACCESS_ADSERVICES_ATTRIBUTION`, `ACCESS_ADSERVICES_AD_ID`, `AD_SERVICES_CONFIG`, plus `<queries>` entries for `http`/`https` `VIEW` intents |
| `media3-exoplayer-ima` | `<meta-data>` for `com.google.android.gms.ads.AD_MANAGER_APP` and the Play services version |

The advertising-ID permission is present whether or not you ever set an ad tag, because the IMA
extension is a hard dependency of `:core` on Android. If that is unacceptable for your app, remove it
with a manifest-merger `tools:node="remove"` rule and satisfy yourself that you never construct an ad
configuration.

---

## 6. Activity attributes for fullscreen and Picture-in-Picture

A library cannot inject these; the host Activity must declare them.

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:supportsPictureInPicture="true"
    android:configChanges="orientation|screenLayout|screenSize|smallestScreenSize|keyboardHidden">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

- `android:supportsPictureInPicture="true"` is what permits `enterPictureInPictureMode()` at all.
  **This one is not optional and its absence is not self-correcting**: `rememberPlayerWindow` judges
  availability from the device's `FEATURE_PICTURE_IN_PICTURE` and the app's AppOps grant, neither of
  which can see your manifest (`PlayerWindow.android.kt:56,97-114`). So without the attribute the
  button still renders, and every tap is rejected by the system with `IllegalStateException`, caught
  and reported as "did not enter" (`PlayerWindow.android.kt:135-140`). The symptom is a button that
  looks fine and does nothing.
- `android:configChanges` stops the Activity being recreated when PiP resizes it — and, as a useful
  side effect, on rotation, which keeps a player held in `remember` alive across an orientation
  change.

PiP availability *is* checked against the system feature and the app's AppOps permission at runtime,
so a user who has revoked PiP for your app gets no button rather than a dead one
(`PlayerWindow.android.kt:97-114`). That covers revocation, not misconfiguration.

Supply `onPipUnavailable` so a refusal is visible rather than silent — it is the only signal you get
when entry fails, and during development it is usually the manifest:

```kotlin
rememberPlayerWindow(
    player = player,
    onPipUnavailable = {
        Toast.makeText(context, "Picture-in-picture is unavailable here", Toast.LENGTH_SHORT).show()
    },
)
```

---

## 7. A complete Activity

```kotlin
package com.example.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.PlayerView
import dev.nonbinary.outis.ui.window.rememberPlayerWindow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlayerScreen() }
    }
}

@OptIn(ExperimentalPlayerUiApi::class)
@Composable
fun PlayerScreen() {
    val context = LocalContext.current

    // Constructed on the main thread — ExoPlayer is pinned to the main Looper and the engine
    // asserts this in its init block. Always pass the APPLICATION context, never the Activity.
    val player = remember {
        VideoPlayer(AppContext(context.applicationContext))
    }

    DisposableEffect(player) {
        onDispose { player.release() }          // idempotent
    }

    LaunchedEffect(player) {
        player.setMediaItem(
            MediaItem(
                source = MediaSource.Url(
                    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
                ),
                mimeType = MimeType.HLS,
            ),
            autoPlay = true,
        )
    }

    PlayerView(
        player = player,
        modifier = Modifier.fillMaxSize(),
        window = rememberPlayerWindow(player),
    )
}
```

That is the whole thing: surface, controls overlay, scrubber, track menus, auto-hide, D-pad focus and
the PiP button, all from `PlayerView`.

Notes on the code above, each verified against source:

- `AppContext(context.applicationContext)` — the Android `AppContext` holds an application `Context`
  for `ExoPlayer.Builder`. Passing an Activity leaks it, because the player outlives configuration
  changes (`AppContext.android.kt:17-23`).
- `VideoPlayer(...)` **must** be called on the main thread. The Android engine checks
  `Looper.myLooper() == Looper.getMainLooper()` in `init` and throws `IllegalStateException` if not
  (`ExoPlayerEngine.kt:497-500`). Every other method is safe from any thread — each engine
  marshals internally (`VideoPlayer.kt:28-29`).
- `release()` is idempotent (`VideoPlayer.kt:82`), so a `DisposableEffect` is the right home for it.
- `setMediaItem(item, autoPlay = true)` is fire-and-forget; the result lands on `state` and `events`
  (`VideoPlayer.kt:44-45`).
- `mimeType = MimeType.HLS` is optional here because the URL ends in `.m3u8` — set it explicitly for
  extension-less or signed URLs (`MediaItem.kt:30-31`). The only values are `MP4`, `HLS` and `DASH`
  (`MimeType.kt:10`).
- `rememberPlayerWindow(player)` with no `onToggleFullscreen` means the fullscreen button hides
  itself and only the PiP button shows. Pass `onToggleFullscreen = { desired -> ... }` once you have
  somewhere to put a fullscreen presentation.
- `@OptIn(ExperimentalPlayerUiApi::class)` is only silencing a warning — the marker is declared at
  `RequiresOptIn.Level.WARNING` (`ExperimentalPlayerUiApi.kt:10`), so the code compiles without it.
- The stream URL is a third-party Apple test asset, used here only so the snippet runs; substitute
  your own.

**Watch the import.** `outis-ui` pulls in `androidx.media3:media3-ui`
(`ui/build.gradle.kts:71`), which also has a class called `PlayerView`. Make sure the IDE imported
`dev.nonbinary.outis.ui.PlayerView`.

---

## 8. Without `outis-ui`

`:core` renders nothing by itself. If you are building your own surface, take the Media3 player out
of the escape hatch and bind it to whatever view you like — for example Media3's own `PlayerView`,
which you then declare yourself (`androidx.media3:media3-ui`, not exported by `outis-core`):

```kotlin
import androidx.media3.common.Player

val media3Player = player.nativePlayerHandle as? Player
```

On Android `nativePlayerHandle` is the `ExoPlayer` instance, non-null from construction until
`release()` (`ExoPlayerEngine.kt:117,505,342`). It is `null` after release. If you need to react to
the engine re-creating its player, collect `PlayerEvent.NativePlayerAttached` from `player.events`
rather than reading the snapshot once.

Everything else — `state`, `events`, transport, tracks, DRM — is identical with or without `:ui`.

---

## 9. Lifecycle

Three rules, and they cover almost every way a player SDK gets misused:

1. **Construct on the main thread.** Enforced on Android with a `check(...)` that throws.
2. **Call from anywhere.** Transport is fire-and-forget and each engine marshals internally.
3. **Always `release()`.** Idempotent, so over-calling it is safe; never calling it is not.

`PlayerView` additionally pauses the player on `Lifecycle.ON_STOP` and resumes it on `ON_START` if it
was playing. `ON_STOP` does not fire while in PiP, so PiP keeps playing. Set
`pauseWhenStopped = false` if you want background playback — it is the only switch for that
(`PlayerView.kt:70,74-90`).

---

## See also

- [Playback guide](playback.md) — sources, transport, state and events, tracks, live, error handling.
- [The Compose UI](ui.md) — customising or replacing the controls overlay.
- [Platform support](platform-support.md) — the full requirements table and per-platform gaps.
- [Troubleshooting](troubleshooting.md) — when the first build or the first frame does not arrive.
