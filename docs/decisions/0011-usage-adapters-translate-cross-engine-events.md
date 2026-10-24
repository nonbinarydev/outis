# 11. Usage-analytics adapters translate the cross-engine `PlayerEvent`s

**Status:** Accepted. Narrows [ADR-0003](0003-analytics-adapters-bind-natively.md) to quality-of-service
adapters; for those it stands.

## Context

ADR-0003 has analytics adapters bind the vendor SDK to the native player instead of translating
`PlayerEvent`s. Its case is a quality-of-service vendor's: Mux measures rendition switches, bandwidth and
dropped frames, which only Media3 surfaces as events, and Mux ships an SDK per platform that hooks the
player directly.

Issue #37 asks for Google Analytics 4 through Firebase, which is usage analytics: what was watched, not
how well it played. Neither the Firebase SDKs nor gtag.js hook a player. There is no ExoPlayer, AVPlayer
or `<video>` integration to bind to; GA4's enhanced measurement records video engagement only for
embedded YouTube players.

What usage analytics needs is that an item started playing, that it ended, that it failed, and that the
item changed. Every engine emits those: `IsPlayingChanged`, `Ended`, `FatalError` and
`MediaItemTransition` fire on Media3, AVFoundation and Shaka alike.

## Decision

ADR-0003 governs quality-of-service adapters. A usage-analytics adapter, whose vendor offers nothing to
bind to the player, translates `PlayerEvent`s, and only those every engine emits, so that it reports the
same thing on each platform. It never translates `BitrateChanged`, `BandwidthSample` or `DroppedFrames`.

`outis-analytics-firebase` is the first. Playback first starting is `video_start`, once per play-through;
the end is `video_complete`; a fatal error is `video_error`. Each carries the item's `PlaybackMetadata`.
What the viewer chose is not a player event, so the app logs it through the adapter.

Adapters still ship as separate artifacts, and `:core` takes no vendor dependency.

## Consequences

The adapter's logic is common code, tested on Node without a device or a Firebase project. Only the
hand-off to each platform's SDK differs per platform.

What GA4 reports now follows the event model, so a change to when an engine emits one of those four
events is a change to the reports. That is a reason to keep this set small.

`video_start` follows `IsPlayingChanged` rather than `FirstFrameRendered`. The first frame arrives on
load whether or not the viewer presses play, and Android reports none for audio-only content.

Consent cannot be the adapter's alone. Firebase collects automatic events of its own as soon as it
starts, so the adapter exposes Firebase's collection switch for the app to drive, and the app still sets
Firebase's off-by-default flag. On the web, only not loading gtag.js until consent sends nothing at all.
