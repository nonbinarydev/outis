// Copyright 2026 The Outis Authors.
//
// Licensed under the Apache License, Version 2.0. See LICENSE for details.

import AVFoundation
import SwiftUI

/// The player's picture and nothing else: no controls, and no subtitles unless a text track is
/// selected. Put your own SwiftUI over it with `.overlay`; `onVideoRectChange` reports where the
/// picture sits inside the view, in the view's points, so an overlay can line up with it.
public struct PlayerSurface {
    let player: VideoPlayer
    let videoGravity: AVLayerVideoGravity
    let onVideoRectChange: ((CGRect) -> Void)?

    public init(
        player: VideoPlayer,
        videoGravity: AVLayerVideoGravity = .resizeAspect,
        onVideoRectChange: ((CGRect) -> Void)? = nil
    ) {
        self.player = player
        self.videoGravity = videoGravity
        self.onVideoRectChange = onVideoRectChange
    }

    fileprivate func update(_ view: PlayerLayerView) {
        if view.playerLayer.player !== player.avPlayer {
            view.playerLayer.player = player.avPlayer
        }
        view.playerLayer.videoGravity = videoGravity
        view.onVideoRectChange = onVideoRectChange
    }
}

#if os(macOS)

extension PlayerSurface: NSViewRepresentable {
    public func makeNSView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        update(view)
        return view
    }

    public func updateNSView(_ view: PlayerLayerView, context: Context) {
        update(view)
    }

    public static func dismantleNSView(_ view: PlayerLayerView, coordinator: ()) {
        view.playerLayer.player = nil
    }
}

/// The view behind `PlayerSurface`: a layer-backed `NSView` whose layer is an `AVPlayerLayer`.
public final class PlayerLayerView: NSView {
    public let playerLayer = AVPlayerLayer()
    var onVideoRectChange: ((CGRect) -> Void)?
    private var videoRectObservation: NSKeyValueObservation?

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        videoRectObservation = observeVideoRect(of: playerLayer) { [weak self] in self?.onVideoRectChange?($0) }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }

    override public func makeBackingLayer() -> CALayer {
        playerLayer
    }
}

#else

extension PlayerSurface: UIViewRepresentable {
    public func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        update(view)
        return view
    }

    public func updateUIView(_ view: PlayerLayerView, context: Context) {
        update(view)
    }

    public static func dismantleUIView(_ view: PlayerLayerView, coordinator: ()) {
        view.playerLayer.player = nil
    }
}

/// The view behind `PlayerSurface`: a `UIView` whose layer is an `AVPlayerLayer`.
public final class PlayerLayerView: UIView {
    override public class var layerClass: AnyClass {
        AVPlayerLayer.self
    }

    public var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    var onVideoRectChange: ((CGRect) -> Void)?
    private var videoRectObservation: NSKeyValueObservation?

    override init(frame: CGRect) {
        super.init(frame: frame)
        videoRectObservation = observeVideoRect(of: playerLayer) { [weak self] in self?.onVideoRectChange?($0) }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not supported")
    }
}

#endif

/// Reports `videoRect` on the main queue, never during a SwiftUI update, so the callback may set state.
private func observeVideoRect(
    of layer: AVPlayerLayer,
    _ report: @escaping (CGRect) -> Void
) -> NSKeyValueObservation {
    layer.observe(\.videoRect, options: [.initial, .new]) { layer, _ in
        let rect = layer.videoRect
        DispatchQueue.main.async { report(rect) }
    }
}
