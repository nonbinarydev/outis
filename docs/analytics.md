[Outis](../README.md) › [Docs](README.md) › Analytics

# Analytics, QoS and the plugin seam

Outis does not ship an analytics integration. It ships the two things one needs: a timed event stream
that already carries the fields a QoS backend asks for, and a handle on the native player for vendor
SDKs that insist on binding to the concrete engine.

Three of the QoS events are Android-only. That is the single most important fact on this page, and it
is not a temporary gap — see [Which engine emits what](#which-engine-emits-what).

## Two streams, and which to use

`VideoPlayer` exposes both. They are not alternatives; they answer different questions.

| | `state: StateFlow<PlayerState>` | `events: SharedFlow<PlayerEvent>` |
|---|---|---|
| Shape | Conflated — you see the latest snapshot, not every change | One-shot, no replay (`replay = 0`) |
| Missed values | Yes, by design. A slow collector skips intermediate states | Buffered to 64, then dropped |
| Timestamps | None | Every event carries `positionMs` and `elapsedRealtimeMs` |
| Use it for | Rendering UI | Analytics, QoS, anything that counts occurrences |

Drive UI from `state` and analytics from `events`. Using `state` for analytics under-counts, because
conflation means two buffering stalls in quick succession can appear as one. Using `events` for UI
means reconstructing a snapshot you already have.

Every event carries a monotonic `elapsedRealtimeMs` as well as the content `positionMs`, so you can
measure real durations — a stall's length, time-to-first-frame — without trusting wall-clock time,
which can jump.

## The plugin seam

`PlayerComponent` is the registration point. It is deliberately minimal: two methods, and everything
you need arrives on the `PlayerHost` handed to `attach`.

```kotlin
interface PlayerComponent {
    fun attach(host: PlayerHost)
    fun detach()
}

interface PlayerHost {
    val state: StateFlow<PlayerState>
    val events: SharedFlow<PlayerEvent>
    val scope: CoroutineScope              // player-lifecycle-scoped; cancelled on release()
    val nativePlayerHandle: StateFlow<Any?>
}
```

Register with `player.addComponent(component)` and remove with `player.removeComponent(component)`.
`detach()` is called on removal *and* on `release()`, so a component never has to observe the player's
death separately.

Launch collectors on `host.scope`. It is cancelled when the player is released, which means you do not
need to track subscriptions yourself — this is the main reason to use the seam rather than collecting
`player.events` directly from your own scope.

```kotlin
class QosReporter(private val sink: (String, Map<String, Any?>) -> Unit) : PlayerComponent {

    override fun attach(host: PlayerHost) {
        host.scope.launch {
            var stallStart: Long? = null
            host.events.collect { event ->
                when (event) {
                    is PlayerEvent.FirstFrameRendered ->
                        sink("startup", mapOf("ms" to event.elapsedRealtimeMs))

                    is PlayerEvent.BufferingStarted ->
                        stallStart = event.elapsedRealtimeMs

                    is PlayerEvent.BufferingEnded -> {
                        val started = stallStart ?: return@collect
                        stallStart = null
                        sink("rebuffer", mapOf("durationMs" to event.elapsedRealtimeMs - started))
                    }

                    is PlayerEvent.FatalError ->
                        sink("error", mapOf("category" to event.error.category.name,
                                            "code" to event.error.code))

                    else -> Unit
                }
            }
        }
    }

    override fun detach() = Unit   // host.scope is already cancelled; nothing to unwind
}
```

`addComponent` is a no-op after `release()`, and `attach` is called outside the internal lock, so a
component may safely call back into the player from `attach`.

## Which engine emits what

Verified against `ExoPlayerEngine.kt`, `AVPlayerEngine.kt` and `ShakaEngine.kt`.

| Event | Android | iOS | Web |
|---|:---:|:---:|:---:|
| `BufferingStarted` | yes | yes | yes |
| `BufferingEnded` | yes | yes | yes |
| `SeekStarted` | yes | yes | yes |
| `SeekCompleted` | yes | yes | yes |
| `FirstFrameRendered` | yes | yes | yes |
| `MediaItemTransition` | yes | yes | yes |
| `PlaybackStateChanged` | yes | yes | yes |
| `IsPlayingChanged` | yes | yes | yes |
| `NativePlayerAttached` | yes | yes | yes |
| `Ended` | yes | yes | yes |
| `FatalError` | yes | yes | yes |
| `TracksChanged` | yes | yes | yes |
| `PlaybackRecovered` | yes | yes | — |
| **`BitrateChanged`** | **yes** | — | — |
| **`BandwidthSample`** | **yes** | — | — |
| **`DroppedFrames`** | **yes** | — | — |

The three bold rows are the ones to design around. Media3 exposes an `AnalyticsListener` with
per-load bandwidth samples, rendition switches and dropped-frame counts; `AVPlayer` and Shaka expose
no comparable callback that this SDK currently maps. A dashboard built on bitrate distribution or
dropped frames will show Android data and nothing else.

`PlaybackRecovered` is emitted where an engine heals a stall by itself: Android after a recoverable
error, and iOS after the stall self-heal re-seeks. The web engine has no equivalent path.

## The native player handle

For a vendor SDK that binds to the concrete player, `nativePlayerHandle` is the escape hatch. What it
returns differs by platform, and the web case is the one that surprises people:

| Platform | Type | Notes |
|---|---|---|
| Android | `androidx.media3.exoplayer.ExoPlayer` | Set once the player is constructed, `null` after `release()` |
| iOS | `platform.AVFoundation.AVPlayer` | The content player — during a CSAI ad break IMA may be presenting its own |
| Web | `org.w3c.dom.HTMLVideoElement` | **The `<video>` element, not the `shaka.Player`** |

On web this is the element, not the Shaka instance, because the element is what a surface needs to
mount and what most analytics libraries attach to. There is no public accessor for the underlying
`shaka.Player`.

Two hazards:

**It changes.** The handle is a `StateFlow` on `PlayerHost` precisely because engines re-create their
native player — Shaka does so after a failed load leaves the player wedged. Bind through the flow, not
through a one-shot read:

```kotlin
override fun attach(host: PlayerHost) {
    host.scope.launch {
        host.nativePlayerHandle.collect { handle ->
            vendorSdk.rebind(handle)      // called again on every re-creation, and with null on release
        }
    }
}
```

`VideoPlayer.nativePlayerHandle` is the plain `Any?` snapshot for one-off casts. Prefer the flow
whenever the binding must survive.

**It is untyped by design.** `Any?` keeps platform types out of the common API. Cast it in a
platform-specific source set, and treat a failed cast as "not this engine" rather than an error — the
same handle is `null` before construction and after release.

## See also

- [Playback](playback.md) — the full state and event model in context
- [Client-side ads](ads-client-side.md) — what `PlayerState.adState` carries, and where it comes from
- [Platform support](platform-support.md) — every per-platform gap in one place
