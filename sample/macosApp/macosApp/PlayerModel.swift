// Copyright 2026 The Outis Authors.
//
// Licensed under the Apache License, Version 2.0. See LICENSE for details.

import AppKit
import CoreMedia
import Outis
import UniformTypeIdentifiers

/// One player for the window's lifetime, and the sandbox side of opening files: an open panel's choice
/// plays directly, and is also kept as a security-scoped bookmark so it can be reopened after a relaunch.
@MainActor
final class PlayerModel: ObservableObject {
    // A tighter poll than the default 250 ms, so the slider and state follow a frame step promptly.
    let player: VideoPlayer = makeVideoPlayer(positionPollIntervalMs: 50)
    @Published private(set) var state: PlayerState?
    @Published var videoRect: CGRect = .zero
    @Published private(set) var reopenableName: String?

    private var scopedURL: URL?

    init() {
        reopenableName = UserDefaults.standard.string(forKey: Keys.bookmarkName)
        Task { [weak self, player] in
            for await state in player.stateStream {
                self?.state = state
            }
        }
    }

    // MARK: Opening

    func chooseFile() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.audiovisualContent] + [UTType(filenameExtension: "mkv")].compactMap { $0 }
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        saveBookmark(for: url)
        play(url, securityScoped: false)
    }

    /// Plays the last chosen file through its bookmark — the path a relaunched sandboxed app must take.
    func reopenLastFile() {
        guard let data = UserDefaults.standard.data(forKey: Keys.bookmark) else { return }
        var stale = false
        guard let url = try? URL(
            resolvingBookmarkData: data,
            options: .withSecurityScope,
            relativeTo: nil,
            bookmarkDataIsStale: &stale
        ) else {
            forgetBookmark()
            return
        }
        play(url, securityScoped: true)
        if stale { saveBookmark(for: url) }
    }

    func playStream(_ address: String) {
        guard let url = URL(string: address) else { return }
        play(url, securityScoped: false)
    }

    private func play(_ url: URL, securityScoped: Bool) {
        scopedURL?.stopAccessingSecurityScopedResource()
        scopedURL = nil
        if securityScoped, url.startAccessingSecurityScopedResource() {
            scopedURL = url
        }
        player.setMediaItem(item: .of(url), autoPlay: true)
    }

    private func saveBookmark(for url: URL) {
        guard let data = try? url.bookmarkData(
            options: [.withSecurityScope, .securityScopeAllowOnlyReadAccess],
            includingResourceValuesForKeys: nil,
            relativeTo: nil
        ) else { return }
        UserDefaults.standard.set(data, forKey: Keys.bookmark)
        UserDefaults.standard.set(url.lastPathComponent, forKey: Keys.bookmarkName)
        reopenableName = url.lastPathComponent
    }

    private func forgetBookmark() {
        UserDefaults.standard.removeObject(forKey: Keys.bookmark)
        UserDefaults.standard.removeObject(forKey: Keys.bookmarkName)
        reopenableName = nil
    }

    // MARK: Transport

    func togglePlay() {
        if state?.playWhenReady == true { player.pause() } else { player.play() }
    }

    func seek(toMs positionMs: Double) {
        player.seekTo(positionMs: Int64(positionMs))
    }

    /// Steps whole frames with AVFoundation itself, which Outis doesn't wrap yet.
    func step(frames: Int) {
        player.pause()
        player.avPlayer?.currentItem?.step(byCount: frames)
    }

    func setRate(_ rate: Float) {
        player.setPlaybackSpeed(speed: rate)
    }

    func selectTrack(id: String) {
        guard let track = state?.audioTracks.first(where: { $0.id == id }) else { return }
        player.selectTrack(track: track)
    }

    /// The playhead read from the AVPlayer at call time, rather than the last polled `positionMs`.
    var preciseTime: CMTime {
        player.avPlayer?.currentTime() ?? .zero
    }

    private enum Keys {
        static let bookmark = "lastFileBookmark"
        static let bookmarkName = "lastFileName"
    }
}
