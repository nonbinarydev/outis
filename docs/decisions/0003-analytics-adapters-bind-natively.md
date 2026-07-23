# 3. Analytics adapters bind to the native player, not to `PlayerEvent`

**Status:** Accepted

## Context

`PlayerEvent` is a timed, one-shot stream carrying QoS-shaped events, and `PlayerComponent` exists as a
registration seam. The apparent design for a Mux, Conviva or Datadog integration is therefore to
translate `PlayerEvent`s into the vendor's API.

Three of those events are emitted by the Media3 engine only — `BitrateChanged`, `BandwidthSample` and
`DroppedFrames`. `AVPlayer` and Shaka expose no comparable callback that this SDK maps.

Vendor SDKs do not have that problem. Mux's ExoPlayer SDK hooks Media3's `AnalyticsListener`, its iOS
SDK hooks `AVPlayer`, and `mux-embed` hooks the `<video>` element. Each collects rendition changes,
bandwidth and dropped frames on its own platform, natively.

## Decision

Analytics adapters bind the vendor SDK to `PlayerHost.nativePlayerHandle` and let it instrument the
player directly. They do not translate `PlayerEvent`s.

Adapters ship as separate published artifacts (`outis-analytics-<vendor>`), so `:core` never takes a
vendor dependency.

## Consequences

iOS and Web analytics are as good as the vendor can make them, rather than being capped by what Outis
happens to expose. Routing through `PlayerEvent` would have *downgraded* two platforms out of three.

`nativePlayerHandle` is load-bearing for this, which is why it is a `StateFlow` on `PlayerHost` —
engines re-create their native player (Shaka does so after a failed load wedges it) and an adapter that
bound once would silently stop reporting.

Adapters are platform-specific by nature, since the vendor SDKs are. The iOS adapter needs CocoaPods
and cannot be verified on a Linux runner.

`PlayerEvent` remains the right surface for a consumer's own analytics and for anything a vendor does
not model. It is simply not the feed for a vendor SDK that already instruments the player.

Not chosen: re-implementing bandwidth and dropped-frame measurement on iOS and Web so `PlayerEvent` is
uniform. That is significant work duplicating what each vendor already does better.
