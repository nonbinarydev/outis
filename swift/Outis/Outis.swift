// Copyright 2026 The Outis Authors.
//
// Licensed under the Apache License, Version 2.0. See LICENSE for details.

@_exported import OutisCore
import AVFoundation

// Swift sees the Kotlin API without its default arguments or flows. These wrap the Kotlin entry points
// in SwiftEntryPoints.kt; they call nothing that takes every field, so a new Kotlin field breaks none.

/// A new player. Call on the main thread, and `release()` it when done.
///
/// - Parameter positionPollIntervalMs: how often `PlayerState.positionMs` is sampled; `nil` keeps the
///   default of 250 ms.
@MainActor
public func makeVideoPlayer(positionPollIntervalMs: Int64? = nil) -> VideoPlayer {
    if let interval = positionPollIntervalMs {
        return SwiftEntryPointsKt.makeVideoPlayer(positionPollIntervalMs: interval)
    }
    return SwiftEntryPointsKt.makeVideoPlayer()
}

extension MediaItem {
    /// An item for `url` with every other field at its default. A file URL plays from its path, so a
    /// sandboxed app must hold access to it while it plays (an open panel's choice, or a resolved
    /// security-scoped bookmark between `startAccessingSecurityScopedResource()` and its stop).
    public static func of(_ url: URL) -> MediaItem {
        SwiftEntryPointsKt.mediaItemOf(url: url)
    }
}

extension VideoPlayer {
    /// The current state, then each change, delivered on the main thread. Conflated: a slow consumer
    /// sees the latest state, not every one. Ends when the consuming task is cancelled.
    public var stateStream: AsyncStream<PlayerState> {
        AsyncStream { continuation in
            let observation = SwiftEntryPointsKt.observeState(self) { continuation.yield($0) }
            continuation.onTermination = { _ in observation.cancel() }
        }
    }

    /// Every event from now on, delivered on the main thread. Ends when the consuming task is cancelled.
    public var eventStream: AsyncStream<any PlayerEvent> {
        AsyncStream { continuation in
            let observation = SwiftEntryPointsKt.observeEvents(self) { continuation.yield($0) }
            continuation.onTermination = { _ in observation.cancel() }
        }
    }

    /// The engine's `AVPlayer`, for what Outis doesn't wrap: `currentTime()` read per display frame, or
    /// `currentItem?.step(byCount:)`. The same instance from construction until `release()`.
    public var avPlayer: AVPlayer? {
        nativePlayerHandle as? AVPlayer
    }
}
