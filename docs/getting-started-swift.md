[Outis](../README.md) › [Docs](README.md) › Getting started with Swift

# Getting started with Swift (iOS and macOS)

For an app written in Swift and built only in Xcode: no Gradle, no Kotlin, no CocoaPods at build time.
Outis comes in as a Swift package with two parts:

- `OutisCore`: the prebuilt `outis-core` XCFramework (iOS device, iOS simulator, macOS; `arm64` only),
  a binary target.
- `Outis`: a small Swift module that re-exports `OutisCore` and adds what Swift cannot get from the
  Kotlin API directly: a player and a `MediaItem` without spelling out every default, state and events as
  `AsyncStream`s, and a SwiftUI `PlayerSurface`.

`import Outis` gives you both.

> **Not yet released.** There is no tagged release carrying the zipped framework, so the package resolves
> only from a local clone today (below). Until then, treat the Swift API as experimental: everything on
> this page is marked `@ExperimentalPlayerApi` on the Kotlin side.

---

## 1. Add the package

Build the framework once from a clone of this repository:

```bash
./gradlew :core:assembleOutisCoreReleaseXCFramework
```

Then, in Xcode, **File › Add Package Dependencies… › Add Local…** and choose the clone's root folder,
which holds `Package.swift`. Add the `Outis` library to your app target. Xcode embeds and signs the
dynamic framework itself; there is no *Embed & Sign* step to do by hand.

Requirements: iOS 15 or macOS 12 and later, and an `arm64`-only Mac build (set `ARCHS = arm64`; the
framework has no `x86_64` slice).

## 2. Play something

```swift
import Outis
import SwiftUI

@MainActor
final class Playback: ObservableObject {
    let player = makeVideoPlayer()          // on the main thread; release() when done
    @Published var state: PlayerState?

    init() {
        Task { [weak self, player] in
            for await state in player.stateStream { self?.state = state }
        }
    }

    func open(_ url: URL) {
        player.setMediaItem(item: .of(url), autoPlay: true)
    }
}

struct PlayerScreen: View {
    @StateObject private var playback = Playback()

    var body: some View {
        PlayerSurface(player: playback.player)
            .background(.black)
            .onAppear { playback.open(URL(string: "https://example.com/master.m3u8")!) }
    }
}
```

- `makeVideoPlayer(positionPollIntervalMs:)` takes an optional poll interval; leave it out for the
  default 250 ms.
- `MediaItem.of(_:)` makes a `MediaSource.LocalFile` from a file URL and a `MediaSource.Url` from
  anything else, with every other field at its default. For the other fields, build a `MediaItem` with
  its full initialiser ([route B](getting-started-ios.md#swift-usage)).
- `stateStream` starts with the current state, then yields each new one on the main thread. States are
  conflated: a slow consumer sees the latest. `eventStream` yields every `PlayerEvent` from then on.
- The rest of the API is Outis's own, with Swift argument labels: `play()`, `pause()`,
  `seekTo(positionMs:)`, `setPlaybackSpeed(speed:)`, `selectTrack(track:)`, `clearTextTrack()`,
  `release()`. See [Playback](playback.md).

**Name clash with AVKit.** SwiftUI's AVKit overlay has a view called `VideoPlayer`, and so does Outis
(the player protocol). In a file that imports both, write `OutisCore.VideoPlayer`.

## 3. Draw on top of the picture

`PlayerSurface` shows the picture and nothing else: no controls, and no subtitles unless you select a
text track (`MediaItem.captionsDefault` is `OFF` unless you set it). Put your own SwiftUI over it with
`.overlay`. To line the overlay up with the picture rather than the whole view, take the picture's
frame from `onVideoRectChange`:

```swift
@State private var picture = CGRect.zero

PlayerSurface(player: player) { picture = $0 }
    .overlay(alignment: .topLeading) {
        Text(cueText)
            .frame(width: picture.width, height: picture.height, alignment: .bottom)
            .offset(x: picture.minX, y: picture.minY)
    }
```

The rect is in the surface's own points and updates whenever the view resizes or the video's size
becomes known.

## 4. Timing finer than the poll

`PlayerState.positionMs` is sampled every `positionPollIntervalMs`. For something drawn every display
frame, read the clock directly from the `AVPlayer`:

```swift
TimelineView(.animation(minimumInterval: nil, paused: state?.isPlaying != true)) { _ in
    Text(format(player.avPlayer?.currentTime() ?? .zero))
}
```

`player.avPlayer` is the engine's own `AVPlayer`: the same instance from construction until `release()`.
It is also how to step frames today (`player.avPlayer?.currentItem?.step(byCount: 1)`), which Outis
does not wrap yet. What a frame-accurate editor needs and does not get yet is in the
[macOS design note](design/macos-discern-and-mkv.md#4-what-a-subtitle-timing-tool-needs-from-the-api).

## 5. The App Sandbox (macOS)

Outis needs no entitlement of its own. What the app needs:

| To play | Entitlement |
|---|---|
| A file the user picked in an open panel | `com.apple.security.files.user-selected.read-only` (or `read-write`) |
| The same file after a relaunch | `com.apple.security.files.bookmarks.app-scope`, plus a security-scoped bookmark |
| A remote stream (HTTP(S), HLS) | `com.apple.security.network.client` |

A local file plays from its path, so the app must hold access for as long as it plays: an open panel's
choice has it for the rest of that launch, and a resolved bookmark has it between
`startAccessingSecurityScopedResource()` and `stopAccessingSecurityScopedResource()`. Stop access only
after `stop()` or after another item replaces it.

Matroska does not play: AVFoundation has no demuxer for it. See
[Platform support](platform-support.md#macos-specifics-worth-knowing).

## 6. The sample

[`sample/macosApp`](../sample/macosApp) is a minimal SwiftUI app built exactly this way: an Xcode
project with the package added by local path, sandboxed with the entitlements above. It opens a file
from an open panel, reopens it after a relaunch through its bookmark, plays the catalogue's MP4 and HLS
streams, and shows the rate and audio-track controls, frame stepping, a display-frame clock and an
overlay pinned to the picture. Build the framework (step 1), then open
`sample/macosApp/macosApp.xcodeproj` and run.
