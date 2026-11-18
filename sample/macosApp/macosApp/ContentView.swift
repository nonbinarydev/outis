// Copyright 2026 The Outis Authors.
//
// Licensed under the Apache License, Version 2.0. See LICENSE for details.

import CoreMedia
import Outis
import SwiftUI

private let sampleMP4 = "https://pub-a930c32af9a24738aec886ca10d1787e.r2.dev/media/progressive/bbb-1080p.mp4"
private let sampleHLS = "https://pub-a930c32af9a24738aec886ca10d1787e.r2.dev/media/hls/master.m3u8"

struct ContentView: View {
    @StateObject private var model = PlayerModel()
    @State private var showsOverlay = true

    var body: some View {
        VStack(spacing: 0) {
            PlayerSurface(player: model.player) { model.videoRect = $0 }
                .background(.black)
                .overlay(alignment: .topLeading) {
                    if showsOverlay { PictureOverlay(rect: model.videoRect) }
                }
            Divider()
            ControlsView(model: model, showsOverlay: $showsOverlay)
                .padding(12)
        }
    }
}

/// Stands in for an app's own cue drawing: SwiftUI pinned to the picture, wherever it sits in the view.
private struct PictureOverlay: View {
    let rect: CGRect

    var body: some View {
        if rect.width > 0, rect.height > 0 {
            ZStack(alignment: .bottom) {
                Rectangle().strokeBorder(.yellow.opacity(0.7), lineWidth: 1)
                Text("Your own SwiftUI, aligned to the picture")
                    .font(.system(size: max(12, rect.height / 20), weight: .semibold))
                    .foregroundStyle(.white)
                    .shadow(color: .black, radius: 2)
                    .padding(.bottom, rect.height * 0.06)
            }
            .frame(width: rect.width, height: rect.height)
            .offset(x: rect.minX, y: rect.minY)
            .allowsHitTesting(false)
        }
    }
}

private struct ControlsView: View {
    @ObservedObject var model: PlayerModel
    @Binding var showsOverlay: Bool
    @State private var scrubMs: Double?

    var body: some View {
        let state = model.state
        let durationMs = Double(state?.durationMs?.int64Value ?? 0)
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Button("Open File…") { model.chooseFile() }
                if let name = model.reopenableName {
                    Button("Reopen \(name)") { model.reopenLastFile() }
                        .help("Plays the last file through its security-scoped bookmark")
                }
                Button("Sample MP4") { model.playStream(sampleMP4) }
                Button("Sample HLS") { model.playStream(sampleHLS) }
                Spacer()
                Text(status(of: state))
                    .foregroundStyle(state?.error == nil ? Color.secondary : Color.red)
                    .lineLimit(2)
                    .textSelection(.enabled)
            }
            HStack {
                Button { model.step(frames: -1) } label: { Image(systemName: "backward.frame.fill") }
                    .help("Back one frame")
                Button { model.togglePlay() } label: {
                    Image(systemName: state?.playWhenReady == true ? "pause.fill" : "play.fill")
                }
                .keyboardShortcut(.space, modifiers: [])
                Button { model.step(frames: 1) } label: { Image(systemName: "forward.frame.fill") }
                    .help("Forward one frame")
                Slider(
                    value: Binding(
                        get: { scrubMs ?? Double(state?.positionMs ?? 0) },
                        set: { scrubMs = $0 }
                    ),
                    in: 0...max(durationMs, 1),
                    onEditingChanged: { editing in
                        if !editing, let target = scrubMs {
                            model.seek(toMs: target)
                            scrubMs = nil
                        }
                    }
                )
                .disabled(state?.isSeekable != true)
                // Redrawn every display frame while playing, from the AVPlayer's clock rather than the poll.
                TimelineView(.animation(minimumInterval: nil, paused: state?.isPlaying != true)) { _ in
                    Text("\(timecode(model.preciseTime)) / \(timecode(ms: durationMs))")
                        .monospacedDigit()
                }
            }
            HStack(spacing: 16) {
                Picker("Rate", selection: Binding(
                    get: { state?.playbackSpeed ?? 1 },
                    set: { model.setRate($0) }
                )) {
                    ForEach([Float(0.5), 1, 1.5, 2], id: \.self) { Text("\($0, specifier: "%g")×").tag($0) }
                }
                .fixedSize()
                Picker("Audio", selection: Binding(
                    get: { state?.selectedAudioTrackId ?? "" },
                    set: { model.selectTrack(id: $0) }
                )) {
                    if state?.audioTracks.isEmpty ?? true { Text("—").tag("") }
                    ForEach(state?.audioTracks ?? [], id: \.id) { track in
                        Text(track.label ?? track.language ?? track.id).tag(track.id)
                    }
                }
                .fixedSize()
                .disabled((state?.audioTracks.count ?? 0) < 2)
                Toggle("Overlay", isOn: $showsOverlay)
                Spacer()
            }
        }
    }

    private func status(of state: PlayerState?) -> String {
        guard let state else { return "" }
        if let error = state.error { return error.message ?? "Playback failed (\(error.category.name))" }
        return state.playbackState.name.capitalized
    }
}

private func timecode(_ time: CMTime) -> String {
    let seconds = time.seconds
    return timecode(ms: seconds.isFinite ? seconds * 1000 : 0)
}

private func timecode(ms: Double) -> String {
    let total = Int(ms.rounded(.down))
    let millis = total % 1000
    let seconds = total / 1000 % 60
    let minutes = total / 60_000 % 60
    let hours = total / 3_600_000
    return String(format: "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
}
