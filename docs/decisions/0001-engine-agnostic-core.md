# 1. No platform or Compose types in the `:core` API

**Status:** Accepted

## Context

Outis wraps three unrelated native players — Media3/ExoPlayer, `AVPlayer` and Shaka. The obvious
shortcut is to let each platform's types surface where convenient: return an `ExoPlayer` from a getter,
take a `UIViewController` as a parameter, expose Compose state directly.

Every such leak makes shared code un-writable. If `VideoPlayer` mentions `ExoPlayer`, an application's
`commonMain` cannot call it, and the reason for the SDK's existence disappears.

The pressure is real: an application genuinely does sometimes need the concrete player — analytics
vendors bind to it, ad SDKs anchor to a view controller.

## Decision

No platform type and no Compose type appears anywhere in `:core`'s public API. The Compose surface is a
separate module, `:ui`, which applications may omit entirely.

Where a concrete player is genuinely needed, it is reached through a deliberately untyped escape hatch:
`VideoPlayer.nativePlayerHandle: Any?`, and `PlayerHost.nativePlayerHandle: StateFlow<Any?>` for
consumers that must survive the player being re-created. Callers cast in a platform source set.

## Consequences

`commonMain` in a consuming application can drive playback with no `expect`/`actual` of its own.

`Any?` is unpleasant, and deliberately so — it is a signpost that you have left the portable API. The
alternative, a sealed hierarchy of platform handles, would have put platform types back in the common
API through the back door.

Platform-specific capability needs a platform-specific escape hatch each time, and those accumulate:
`setAdViewProvider` on Android, `setAdContainer` and `updateAdState` on iOS. They are free functions in
platform source sets rather than interface members, so `:core`'s common contract stays clean, but the
asymmetry is a real cost and is tracked separately.
