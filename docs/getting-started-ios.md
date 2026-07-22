[Outis](../README.md) › [Docs](README.md) › Getting started on iOS

# Getting started on iOS

There are two routes to a playing video on iOS, and which one you take depends on where your Kotlin
lives.

| Route | You get | Use it when |
|---|---|---|
| [A KMP shared module](#route-a-a-kotlin-multiplatform-shared-module) | `outis-core` **and** `outis-ui` | You already have (or want) a multiplatform module shared with Android or Web. |
| [The prebuilt XCFramework](#route-b-the-prebuilt-xcframework) | `outis-core` only | Your app is Swift or Objective-C and you want no Kotlin build in it. |

One thing neither route does: there is no Gradle task in *your* build called
`:core:embedAndSignAppleFrameworkForXcode`. `:core` and `:ui` are projects inside the Outis
repository (`settings.gradle.kts`), not in a consumer's build. The Maven publication contains
Kotlin/Native **klibs**, not frameworks — a framework is something your own module produces, or
something you take prebuilt from this repository.

## Before you start

- **macOS with Xcode.** Apple targets cannot be built on anything else.
- **`iosArm64` and `iosSimulatorArm64` only** — `core/build.gradle.kts:51-52` and
  `ui/build.gradle.kts:46-47`. There is no `iosX64`, so the iOS simulator is unavailable on an Intel
  Mac; you need Apple silicon, or a real device.
- **Nothing has been published yet.** At `0.1.0-alpha01` the coordinates below will not resolve from
  Maven Central. See [Platform support](platform-support.md) for the full requirements table.
- Kotlin 2.4.10, plus Compose Multiplatform 1.11.1 if you take `outis-ui`.

---

## Route A: a Kotlin Multiplatform shared module

Your shared module depends on the artifacts and declares its **own** framework binary. That framework
is what Xcode embeds, and `embedAndSignAppleFrameworkForXcode` is a task on *your* module because
*your* module declared `binaries.framework`.

### 1. Gradle

In your shared module's `build.gradle.kts`:

```kotlin
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.nonbinarydev:outis-core:0.1.0-alpha01")
            implementation("io.github.nonbinarydev:outis-ui:0.1.0-alpha01") // optional Compose chrome

            // outis-core scopes these implementation() (core/build.gradle.kts:80-81) but both appear
            // in its public API — StateFlow<PlayerState>, ImmutableMap<String, String> on
            // MediaItem.headers — so declare them yourself or your own code will not compile.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.1")
        }
    }
}
```

Two constraints worth knowing before you write the module:

- If your shared module also targets `jvm` or `wasmJs`, a `commonMain` dependency on **`outis-ui`**
  will not resolve — `:ui` publishes `android`, `iosArm64`, `iosSimulatorArm64` and `js` only
  (`ui/build.gradle.kts:26-53`). Move it to an intermediate source set that excludes those targets.
- `outis-ui` declares its Compose dependencies as `implementation` (`ui/build.gradle.kts:59-63`), so
  your module needs the `org.jetbrains.compose` and `org.jetbrains.kotlin.plugin.compose` plugins and
  its own `compose.runtime` / `compose.ui` dependencies to write any Compose code. It does *not* need
  `outis-core` declared separately: `ui/build.gradle.kts:58` is `api(project(":core"))`, so the core
  API comes through transitively.

If you want Swift to see Outis' own types (`MediaItem`, `PlayerState`, …) rather than only the
functions your shared module exposes, add `export("io.github.nonbinarydev:outis-core:0.1.0-alpha01")`
inside the `framework { }` block and change that module's dependency from `implementation` to `api`.
The Kotlin Gradle plugin rejects `export` on an `implementation`-scoped dependency.

### 2. The Xcode build phase

Add a **Run Script** phase to your app target, before "Compile Sources":

```sh
cd "$SRCROOT/.."
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

Then set **Framework Search Paths** on the app target to:

```
$(SRCROOT)/../shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)
```

That path is not folklore: the embed task reads `CONFIGURATION` and `SDK_NAME` from Xcode's
environment and resolves the framework under `build/xcode-frameworks/<configuration>/<sdk>` (verified
against the Kotlin Gradle plugin 2.4.10 sources). Two related traps:

- **User script sandboxing must be off.** With `ENABLE_USER_SCRIPT_SANDBOXING = YES` the plugin
  reports a sandboxing diagnostic and the phase cannot write the framework.
- **Custom build configurations need a hint.** If your scheme uses configurations other than `Debug`
  and `Release`, the plugin cannot infer a Kotlin build type and fails with "Unable to detect Kotlin
  framework build type"; set `KOTLIN_FRAMEWORK_BUILD_TYPE` to `debug` or `release` in the run-script
  environment.

### 3. Host the Compose surface

`outis-ui` renders through Compose, so on iOS you wrap it in a `UIViewController` with
`ComposeUIViewController` (`androidx.compose.ui.window`, Compose Multiplatform 1.11.1) and present
that from SwiftUI or UIKit. Put this in your shared module's `iosMain`, in a file named
`PlayerViewController.kt` — the file name decides the Swift name:

```kotlin
package com.example.shared

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import dev.nonbinary.outis.core.AppContext
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.ui.ExperimentalPlayerUiApi
import dev.nonbinary.outis.ui.PlayerView
import platform.UIKit.UIViewController

@OptIn(ExperimentalPlayerUiApi::class)
fun PlayerViewController(url: String): UIViewController = ComposeUIViewController {
    val player = remember {
        VideoPlayer(AppContext()).apply {
            setMediaItem(MediaItem(MediaSource.Url(url), mimeType = MimeType.HLS), autoPlay = true)
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    PlayerView(player, modifier = Modifier.fillMaxSize())
}
```

`AppContext` takes no arguments on iOS — `AVPlayer` needs no host context
(`AppContext.ios.kt:11`). `release()` is idempotent (`AVPlayerEngine.kt:416-417`), but it is not
optional: the engine keeps itself strongly reachable in a process-wide set until you call it
(`AVPlayerEngine.kt:117`, `:189`, `:419`), precisely so a dropped reference cannot leave an `AVPlayer`
playing audio with nothing to stop it.

From Swift:

```swift
import SwiftUI
import Shared

struct PlayerScreen: UIViewControllerRepresentable {
    let url: String

    func makeUIViewController(context: Context) -> UIViewController {
        PlayerViewControllerKt.PlayerViewController(url: url)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
```

---

## Route B: the prebuilt XCFramework

For a Swift-only app, build the XCFramework from a clone of this repository:

```sh
./gradlew :core:assembleOutisReleaseXCFramework
```

Output:

```
core/build/XCFrameworks/release/Outis.xcframework
```

`assembleOutisDebugXCFramework` produces the debug variant, and `assembleOutisXCFramework` builds
both. The name comes from `core/build.gradle.kts:66-75`.

**This XCFramework is not part of the Maven publication.** Nothing on Maven Central ships it; you
build it, and you version it, yourself.

What you get:

- Two slices, `ios-arm64` and `ios-arm64-simulator`, both `arm64`. No `x86_64`, so it does not run on
  an Intel Mac's simulator.
- A **dynamic** framework — `isStatic = false` at `core/build.gradle.kts:72` — so add it to your
  target under *Frameworks, Libraries, and Embedded Content* with **Embed & Sign**. Do not use "Do Not
  Embed"; the app will build and then fail to launch.
- `MinimumOSVersion` 15.0 in the framework's `Info.plist`, which is the Kotlin/Native default
  deployment target for Kotlin 2.4.10.
- `outis-core` only. `:ui` declares no `binaries.framework` at all, so it has no
  `embedAndSignAppleFrameworkForXcode` task and no XCFramework task — verified by running
  `./gradlew :ui:tasks --all`. **There is no way to get the Compose chrome through this route**; take
  route A if you want it.

Do not combine the two routes. Linking the prebuilt `Outis.xcframework` alongside a shared framework
that also contains `outis-core` gives you the Kotlin runtime twice.

### Swift usage

The generated Objective-C header strips the `Outis` prefix for Swift via `swift_name` attributes, so
the Swift API reads close to the Kotlin one. Top-level Kotlin functions become members of a class
named after their file: `VideoPlayer(...)` lives in `PlayerFactory.kt`, so it is
`PlayerFactoryKt.VideoPlayer`.

Defaults do not survive the Objective-C bridge, so every parameter is required in Swift:

```swift
import AVFoundation
import Outis

// Construct on the main thread — the engine asserts it (AVPlayerEngine.kt:186).
let player = PlayerFactoryKt.VideoPlayer(
    context: AppContext(),
    config: PlayerConfig(
        initialVolume: 1.0,
        positionPollIntervalMs: 250,
        bufferConfig: nil,
        initialBitrateBps: nil,
        retryConfig: nil,
        audioConfig: nil,
        liveConfig: nil,
        components: []
    )
)

let item = MediaItem(
    source: MediaSourceUrl(url: "https://example.com/master.m3u8"),
    mimeType: MimeType.hls,
    headers: [:],
    drmConfig: nil,
    videoConstraints: nil,
    preferredAudioLanguage: nil,
    preferredTextLanguage: nil,
    captionsDefault: CaptionsDefaultMode.off,
    startPositionMs: nil,
    startMuted: false,
    loop: false,
    adConfig: nil,
    metadata: nil,
    chapterThumbnails: false
)
player.setMediaItem(item: item, autoPlay: true)
```

`outis-core` contains no view, so you supply the rendering yourself. The engine creates its `AVPlayer`
during construction and never replaces it (`AVPlayerEngine.kt:191-197`), so
`player.nativePlayerHandle` is non-`nil` as soon as the factory returns and stays the same instance
until `release()`:

```swift
let controller = AVPlayerViewController()
controller.player = player.nativePlayerHandle as? AVPlayer
```

Reading state from Swift is the rough edge of this route. `player.state` is a Kotlin
`StateFlow`, which bridges as a protocol with a single `value: Any?` property — you can poll
`player.state.value as? PlayerState`, but collecting the flow means implementing
`Kotlinx_coroutines_coreFlowCollector` and calling `collect(collector:completionHandler:)`. If you
need continuous observation in Swift, route A and a small Kotlin-side adapter is much less work.

---

## Info.plist

Nothing in Outis touches your `Info.plist`; these are the entries iOS itself will make you add.

**App Transport Security.** A plain-HTTP test stream is blocked by default. For development, scope the
exception to the host you actually use rather than opening everything:

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSExceptionDomains</key>
    <dict>
        <key>test.example.com</key>
        <dict>
            <key>NSExceptionAllowsInsecureHTTPLoads</key>
            <true/>
        </dict>
    </dict>
</dict>
```

`NSAllowsArbitraryLoadsInMedia` covers media loaded through AVFoundation specifically and is the
narrower alternative to `NSAllowsArbitraryLoads`. Ship HTTPS and neither key is needed. Local files
(`MediaSource.LocalFile`, resolved with `NSURL.fileURLWithPath` at `AVPlayerEngine.kt:225`) are not
subject to ATS at all.

**Background audio.** Playing while the app is backgrounded requires the *Audio, AirPlay, and Picture
in Picture* background mode on the target. Note also that `PlayerView` pauses on `Lifecycle.ON_STOP`
and resumes on `ON_START` by default (`PlayerView.kt:70`, `:78-90`); pass `pauseWhenStopped = false`
if you want playback to continue.

## The audio session is set for you

The engine puts the shared `AVAudioSession` into the `playback` category during construction
(`AVPlayerEngine.kt:190`, `:849-861`), so audio plays through the ring/silent switch without you
configuring anything. Two consequences:

- Setting `PlayerConfig.audioConfig = AudioConfig(mixWithOthers = true)` switches it to `playback`
  with `AVAudioSessionCategoryOptionMixWithOthers` (`AVPlayerEngine.kt:852-857`), so your audio plays
  alongside other apps instead of interrupting them. The other two `AudioConfig` fields,
  `handleAudioFocus` and `pauseOnBecomingNoisy`, are Android-only.
- The session is deactivated again only when the **last** live engine is released
  (`AVPlayerEngine.kt:422`) — several players share one process-wide session, so one being released
  does not hand audio back.

If your app manages the audio session itself, configure it *after* constructing the player, because
the player will overwrite the category on construction.

## What iOS does differently

Full details are in [Platform support](platform-support.md); these are the ones that most often read
as bugs.

- **`videoSize` is always `null`.** The iOS engine never populates it (`AVPlayerEngine.kt:286`,
  `:406`) — it is Android-only. Never size your layout from it.
- **`bufferedPositionMs` is not real.** It is set equal to `positionMs`
  (`AVPlayerEngine.kt:759`), so a buffer-ahead indicator reads permanently zero.
- **`isLive` is computed**, from a ready item with an indefinite duration and a non-empty seekable
  range (`AVPlayerEngine.kt:766`). It is reliable; use it rather than a null duration.
- **DASH does not play.** AVFoundation has no DASH support, and the engine passes the URL straight to
  `AVURLAsset` without inspecting `MimeType` — so a `.mpd` fails as an ordinary source error, not with
  anything that names DASH. iOS delivery is HLS.
- **Most load failures arrive as `PlayerError.Category.SOURCE`** (`AVPlayerEngine.kt:523`, `:734`),
  including a `DrmConfig` iOS cannot satisfy. `Category.DRM` is only produced once a FairPlay key
  manager exists (`FairPlayContentKeyManager.kt:167`), and one is created only for
  `DrmScheme.FAIRPLAY` *with* a `certificateUrl` (`AVPlayerEngine.kt:247`). See [DRM](drm.md).
- **Client-side ads do nothing.** The iOS engine never reads `MediaItem.adConfig`. See
  [Client-side ads](ads-client-side.md).
- **`PlayerSurface`'s `showSubtitles` parameter is ignored on iOS.** The surface is an
  `AVPlayerViewController` (`PlayerSurface.ios.kt:57-61`), which renders the selected text track
  natively; use `VideoPlayer.clearTextTrack()` to turn subtitles off.
- **`rememberPlayerWindow` is Android-only** (`PlayerWindow.android.kt:47`). On iOS, construct
  `PlayerWindow(...)` yourself and wire `onToggleFullscreen` / `onEnterPip` to your own presentation
  and `AVPictureInPictureController`. Until you do, those buttons hide themselves.
- **Keyboard shortcuts are a no-op** (`PlayerKeyboard.ios.kt`). They exist on web only.

## See also

- [Playback](playback.md) — loading, transport, `state` versus `events`, tracks, live and errors.
- [The Compose UI](ui.md) — `PlayerView`, `PlayerSurface` and customising the controls overlay.
- [Platform support](platform-support.md) — target sets, versions and every known gap.
- [Troubleshooting](troubleshooting.md) — including `:ui:embedAndSignAppleFrameworkForXcode` not
  being found.
