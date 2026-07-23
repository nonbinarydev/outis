[Outis](../README.md) › [Docs](README.md) › Server-side ads (SSAI)

# Server-side ads (SSAI)

Server-side ad insertion means the ads are already stitched into the stream by the packager, so the
player just plays a normal HLS or DASH manifest and never fetches an ad itself. What is left for the
client is bookkeeping: knowing where the breaks are on the timeline, showing ad UI while the playhead
is inside one, stopping the viewer from seeking past an unwatched break, and firing the tracking
beacons the ad server expects.

Outis ships that bookkeeping as `AdController`, in `dev.nonbinary.outis.core.ads`. It is pure Kotlin
in `commonMain`, it does no I/O, it touches no engine, and it behaves identically on Android, iOS and
Web — because it never talks to any of them.

## The SDK does not drive SSAI for you

This is the first thing to understand and the easiest thing to get wrong.

**No engine reads `AdConfig.ServerSide`.** `MediaItem.adConfig` is read in exactly four places across
all three engines, and every one of them is an `as? AdConfig.ClientSide` cast:
`ExoPlayerEngine.kt:220`, `ExoPlayerEngine.kt:254`, `ExoPlayerEngine.kt:718` and
`ShakaEngine.kt:466`. The iOS engine never reads `adConfig` at all. Setting
`AdConfig.ServerSide(breaks)` on a `MediaItem` therefore changes nothing about playback. It is a
carrier for your cue-points and nothing else, which is exactly what
`MediaItem.serverSideBreaks()` (`MediaItem.kt:93`) exists to get back out.

**`AdController` is a class you construct and feed.** Nothing constructs one for you, nothing
registers it, and it is not a `PlayerComponent`. You create it, you push the playhead into it on
every position update, you route your seeks through it, and you read its state and events.

**It does not write `PlayerState.adState`.** `PlayerState.adState` (`PlayerState.kt:105`) is written
only by the two client-side engines — `ExoPlayerEngine.kt:645-649` and `ShakaEngine.kt:597-602`. On a
stitched stream it stays `null` for the whole session. `AdController` publishes its own
`StateFlow<AdState>` instead, and **that flow is non-null**: `AdController.state`
(`AdController.kt:57`) is a `StateFlow<AdState>`, not `StateFlow<AdState?>`, and outside a break it
emits a reset `AdState` carrying the current cue-points (`AdController.kt:78`, `:148`, `:177`). Chrome
that tests `adState != null` to decide "an ad is playing" will believe an ad is playing permanently.
The correct test is `AdState.isInAdBreak`, as `Ads.kt:106-108` says.

**The shipped Compose chrome has no ad awareness.** `outis-ui` does not lock the scrubber during a
break, draw a countdown or a skip button, or render cue-point markers. See
[the Compose UI](ui.md#what-the-shipped-controls-do-not-do). Ad UI is yours to build.

## The model

Four types, all in `dev.nonbinary.outis.core.ads` (`Ads.kt`).

```kotlin
sealed interface AdConfig {
    data class ServerSide(val breaks: List<AdBreak>) : AdConfig
    data class ClientSide(val adTagUri: String) : AdConfig
}

data class AdBreak(val id: String, val startMs: Long, val ads: List<Ad>)

data class Ad(
    val id: String,
    val durationMs: Long,
    val title: String? = null,
    val skipOffsetMs: Long? = null,
    val clickThroughUrl: String? = null,
)
```

`AdBreak` derives `durationMs` (the sum of its ads) and `endMs` (`startMs + durationMs`) as computed
properties (`Ads.kt:67`, `:70`).

Three rules follow from the source and all three bite in practice:

- **`startMs` is a position on the stitched timeline** (`Ads.kt:56-58`) — an ordinary player
  position with the ads included, not a content-only "as-broadcast" offset. This is what makes SSAI
  tractable: `PlayerState.positionMs` and `AdBreak.startMs` are the same clock.
- **`AdBreak.id` must be unique within a session** (`Ads.kt:50-52`). The watched-set and the
  duplicate check in `addBreak` are both keyed on it. Two breaks sharing an id means the second is
  silently dropped and the first counts as watched for both.
- **`Ad.durationMs` must match what is actually stitched.** The playhead-to-ad mapping accumulates
  durations along `AdBreak.ads` (`AdController.kt:86-93`), so a break whose ad durations do not sum
  to the real stitched length drifts. `0` disables quartile reporting for that ad rather than firing
  all three at once (`Ads.kt:84-85`, `AdController.kt:202`).

A break with an empty `ads` list has `endMs == startMs`, so the playhead can never be inside it and
it never becomes active. It still contributes a cue-point and still clamps one forward seek (see
below) before being marked watched. Filter empty avails out if your provider emits them.

## Wiring it up

`AdController` needs three things from the player: positions, seeks, and the end of the stream. All
of it is ordinary `VideoPlayer` API.

```kotlin
import dev.nonbinary.outis.core.PlayerEvent
import dev.nonbinary.outis.core.VideoPlayer
import dev.nonbinary.outis.core.ads.AdController
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.serverSideBreaks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

fun startSsai(player: VideoPlayer, item: MediaItem, scope: CoroutineScope): AdController {
    val ads = AdController(item.serverSideBreaks())

    // Subscribe to events BEFORE the first onPosition — the flow is replay-free (see below).
    scope.launch {
        ads.events.collect { event -> sendBeacon(event) }
    }

    scope.launch {
        player.state
            .map { it.positionMs }
            .distinctUntilChanged()
            .collect { ads.onPosition(it) }
    }

    scope.launch {
        player.events.collect { if (it is PlayerEvent.Ended) ads.onEnded() }
    }

    player.setMediaItem(item, autoPlay = true)
    return ads
}
```

`AdController(breaks)` sorts the list by `startMs` on construction (`AdController.kt:47`), so the
order you pass in does not matter. It holds no resources and has no `release()` — build one per item
and drop it when the item changes.

### The position loop

`onPosition(positionMs)` (`AdController.kt:74`) is the whole state machine. It is idempotent per
position and safe to call on every state emission. On each call it:

1. finds the first unwatched break containing the position;
2. if there is none, marks every unwatched break the playhead has now passed as watched, emits their
   `BreakCompleted` (and `AllAdsCompleted` if that was the last one), and resets `state` to a
   cue-points-only `AdState`;
3. if there is one, maps the offset into the break onto an ad, emits `BreakStarted` / `AdStarted` /
   `Quartile` as thresholds are crossed, and publishes a fully populated `AdState`.

The resolution of everything derived from it — the countdown, the quartiles, the moment the skip
button appears — is the resolution of your position source. `PlayerState.positionMs` is polled, not
streamed: `PlayerConfig.positionPollIntervalMs` defaults to 250 ms (`PlayerFactory.kt:26`), which is
the cadence on all three engines (`ExoPlayerEngine.kt:535`, `AVPlayerEngine.kt:469`,
`ShakaEngine.kt:246`). Quartile beacons are therefore accurate to roughly a quarter of a second, and
a break shorter than one poll interval can be jumped over entirely — in which case it is finalised
rather than played, and its completion events still fire.

### Seeking

Two calls, and both need you to route the player through them; nothing intercepts `seekTo` for you.

`resolveSeek(fromMs, targetMs)` (`AdController.kt:128`) returns the position you should actually seek
to:

```kotlin
fun seek(player: VideoPlayer, ads: AdController, targetMs: Long) {
    val from = player.state.value.positionMs
    player.seekTo(ads.resolveSeek(from, targetMs))
}
```

| Case | Result |
|---|---|
| Backward seek (`targetMs <= fromMs`) | Passes through unchanged. |
| Forward seek from inside an unwatched break | Pinned to `fromMs` — you cannot seek out of the ad you are watching. |
| Forward seek that crosses an unwatched break | Snapped to that break's `startMs`. The viewer sees the ad, then seeks on. |
| Forward seek crossing only watched breaks | Passes through unchanged. |

Breaks are marked watched by playing through them, by a poll or seek landing past their `endMs`
(`AdController.kt:186-199`), by `skipCurrentBreak()`, or by `markWatchedUpTo()`. Once watched, a
break never clamps a seek again — that is what stops a viewer being dragged back into the same
mid-roll on every scrub.

`skipCurrentBreak()` (`AdController.kt:141`) is the skip button's action. It marks the current break
watched, emits `AdCompleted` and `BreakCompleted` (plus `AllAdsCompleted` if it was the last), resets
`state`, and returns the position to seek to — the break's `endMs` — or `null` if there is no active
break. It does not seek for you:

```kotlin
ads.skipCurrentBreak()?.let { player.seekTo(it) }
```

It is meaningful only when `AdState.canSkip` is `true`, which happens once the playhead passes the
current ad's `skipOffsetMs` (`AdController.kt:117`). `skipOffsetMs = null` means unskippable; `0`
means skippable immediately (`Ads.kt:91-92`). Nothing in `AdController` enforces this — calling
`skipCurrentBreak()` while `canSkip` is `false` still skips the break. Gate the button on
`canSkip` yourself.

### Resuming, and the end of the stream

`markWatchedUpTo(positionMs)` (`AdController.kt:167`) marks every break whose `endMs <= positionMs` as
watched. Call it before the first `onPosition` when you resume a session mid-asset, otherwise
`resolveSeek` will drag the viewer back into pre-rolls and mid-rolls they already sat through:

```kotlin
val ads = AdController(item.serverSideBreaks())
ads.markWatchedUpTo(resumePositionMs)
player.setMediaItem(item.copy(startPositionMs = resumePositionMs), autoPlay = true)
```

`onEnded()` (`AdController.kt:175`) finalises anything still unwatched, which in practice means the
post-roll. A post-roll sits at the very end of the timeline and the last position poll frequently
never reports a position past its `endMs`, so without `onEnded()` its `BreakCompleted` and
`AllAdsCompleted` never fire. Drive it off `PlayerEvent.Ended`, as in the wiring example above.

### Live avails

`addBreak(adBreak)` (`AdController.kt:159`) adds a break discovered during playback — the live case,
where avails arrive as SCTE-35 markers or provider events rather than up front. It is ignored if a
break with the same id is already known, it re-sorts the list, it refreshes `cuePoints` immediately,
and the position loop picks the new break up on the next `onPosition`.

`AllAdsCompleted` is not final on a live stream: `addBreak` can introduce new avails afterwards and
the event fires again once those are watched too (`Ads.kt:183-185`).

## Where cue-points come from

`AdController` tracks whatever breaks it is given; two parsers ship for the common sources. Both are
pure string-in, data-out. **Neither performs any I/O** — fetching the JSON or the playlist is your
app's job, with Ktor, `URLSession`, `fetch`, or whatever you already use. Outis declares no HTTP
client.

### MediaTailor

Two functions, in `MediaTailorAvails.kt`.

`parseMediaTailorSession(json)` (`MediaTailorAvails.kt:74`) parses the small body a MediaTailor
session-init POST returns into `MediaTailorSession(manifestUrl, trackingUrl)`. Both fields are
**root-relative paths, not absolute URLs** (`MediaTailorAvails.kt:60-71`) — prepend the scheme and
host you POSTed to before using either.

`parseMediaTailorAvails(json)` (`MediaTailorAvails.kt:38`) parses the tracking endpoint's JSON into
`List<AdBreak>`. The root is `{ "avails": [...] }`, times on the wire are floating-point seconds and
are scaled to milliseconds, and unknown fields — `adSystem`, `mediaFiles`, `companionAds`,
`trackingEvents` and the rest — are ignored.

```kotlin
import dev.nonbinary.outis.core.ads.AdConfig
import dev.nonbinary.outis.core.ads.AdController
import dev.nonbinary.outis.core.ads.parseMediaTailorAvails
import dev.nonbinary.outis.core.ads.parseMediaTailorSession
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource
import dev.nonbinary.outis.core.source.MimeType
import dev.nonbinary.outis.core.source.serverSideBreaks

// httpPost / httpGet are yours — the SDK does no networking.
val origin = "https://abcd1234.mediatailor.eu-west-1.amazonaws.com"
val session = parseMediaTailorSession(httpPost("$origin/v1/session/my-config", body = "{}"))

val breaks = parseMediaTailorAvails(httpGet(origin + session.trackingUrl))

val item = MediaItem(
    MediaSource.Url(origin + session.manifestUrl),
    mimeType = MimeType.HLS,
    adConfig = AdConfig.ServerSide(breaks),
)
val ads = AdController(item.serverSideBreaks())
```

Three details worth knowing before you debug a mismatch:

- The avail's own `durationInSeconds` is **not** used. `AdBreak.durationMs` is always the sum of the
  ad durations (`Ads.kt:67`). If the avails document disagrees with itself, the ads win.
- Each ad's `startTimeInSeconds` is **not** used either. Ads are laid out back-to-back from the
  break's `startMs` (`AdController.kt:86-93`).
- `skipOffset` accepts either a bare number of seconds or an ISO-8601 `PT[nM][nS]` duration —
  `"PT5S"`, `"PT1M5S"`, `"PT38.4S"` (`MediaTailorAvails.kt:83-91`). Anything else parses to `null`,
  which means unskippable.
- The click-through key on the wire is spelled `clickthroughUrl`, lowercase `t`, and that exact key
  is what is accepted (`MediaTailorAvails.kt:110-111`). It lands on `Ad.clickThroughUrl`. Opening it
  is your job; the SDK never navigates.

Parsing is strict about structure even though it is lenient about fields: malformed JSON throws from
`kotlinx.serialization`. Wrap the call if your tracking endpoint can return an error page.

### SCTE-35 in an HLS playlist

`parseHlsAdCues(mediaPlaylist)` (`Scte35Hls.kt:24`) reads ad avails out of an HLS **media** playlist
(not the master). It recognises both signalling forms broadcasters emit:

- `#EXT-X-DATERANGE:ID="…",PLANNED-DURATION=38.4,SCTE35-OUT=0x…` — preferred, because it carries a
  stable id;
- the `#EXT-X-CUE-OUT[:duration] … #EXT-X-CUE-IN` bracket, used when no DATERANGE is present.

When any DATERANGE with `SCTE35-OUT` or `PLANNED-DURATION` is present, the CUE-OUT lines are ignored,
because packagers that emit both would otherwise double-count every avail (`Scte35Hls.kt:25-26`).

It returns `List<HlsAdCue>`, and `HlsAdCue` is only `id` plus `durationMs` (`HlsAdCue.kt:10-23`).
**There is no position on it.** Converting a cue into an `AdBreak` means supplying `startMs`
yourself — from the live playhead when you observed the cue, or from your own accumulation of
`#EXTINF` durations up to the marker:

```kotlin
import dev.nonbinary.outis.core.ads.Ad
import dev.nonbinary.outis.core.ads.AdBreak
import dev.nonbinary.outis.core.ads.parseHlsAdCues

// `ads` is your AdController; startPositionFor is yours — see below.
for (cue in parseHlsAdCues(playlistText)) {
    if (cue.durationMs == 0L) continue // unknown duration, not a zero-length break
    ads.addBreak(
        AdBreak(
            id = cue.id,
            startMs = startPositionFor(cue),
            ads = listOf(Ad(id = cue.id, durationMs = cue.durationMs)),
        ),
    )
}
```

Two traps:

- A `durationMs` of `0` means the signalling carried no duration, not a zero-length break
  (`HlsAdCue.kt:19-22`). Turned into an `AdBreak` as-is it has `endMs == startMs`, so the playhead
  can never be inside it and it will never produce any ad UI. Skip it or substitute your own
  duration.
- Only the DATERANGE form has stable ids. The `"cue-n"` and `"daterange-n"` fallbacks are positional,
  so a live playlist that drops expired segments renumbers them (`HlsAdCue.kt:12-15`) — and since
  `AdBreak.id` is the watched-set key, renumbering will make already-watched breaks look new. Derive
  a stable id yourself when your packager gives you no DATERANGE `ID`.

## Beaconing: the event contract

`AdController.events` (`AdController.kt:67`) is a `SharedFlow<AdEvent>` carrying the lifecycle
signals an ad-tracking or QoS adapter needs.

| Event | Fires when |
|---|---|
| `AdEvent.BreakStarted(adBreak)` | The playhead entered a break. Always followed immediately by an `AdStarted` for its first ad. |
| `AdEvent.AdStarted(ad, indexInBreak, countInBreak)` | The playhead moved onto a new creative — at break start, or at a boundary between two ads. `indexInBreak` is 0-based. |
| `AdEvent.Quartile(ad, quartile)` | 25%, 50% or 75% of `Ad.durationMs` elapsed. |
| `AdEvent.AdCompleted(ad)` | The creative ended — or was seeked away from, or skipped. **Not** proof it was watched to the end. |
| `AdEvent.BreakCompleted(adBreak)` | The break is done and marked watched, so seeks may now pass through it. Fires whether it was played through, skipped, or jumped over. |
| `AdEvent.AllAdsCompleted` | Every currently known break is watched. Not final on live — `addBreak` can add more, and it fires again. |

`AdQuartile` has three entries, `FIRST`, `MIDPOINT` and `THIRD` (`Ads.kt:190-199`). There is
deliberately no `COMPLETE`: 100% arrives as `AdCompleted`. Thresholds are evaluated in ascending
order (`AdController.kt:20-24`), so a viewer who jumps forward within an ad still receives the
earlier quartiles first, in order, rather than only the one they landed on. Each quartile fires at
most once per ad play; the set resets when the ad changes (`AdController.kt:104`).

### Subscribe before the first `onPosition`

The flow is created as `MutableSharedFlow<AdEvent>(extraBufferCapacity = 32)` (`AdController.kt:59`)
— **no replay**, a 32-event buffer, and every emission is a non-suspending `tryEmit`. Two consequences:

- Anything emitted before you collect is gone. A pre-roll at position 0 emits `BreakStarted` and
  `AdStarted` on the very first `onPosition`, so a collector launched after the position loop misses
  the start of the first break entirely. Subscribe first.
- A collector that cannot keep up drops events rather than stalling the playhead. Do the slow part —
  the network beacon — off the collector, by handing the event to a queue.

### Deduplicate by ad id

Seeking backwards into a break that is still unwatched re-enters it, and re-emits its `BreakStarted`,
`AdStarted` and `Quartile` events (`AdController.kt:35-37`). If you bill or report on those, dedupe
per session on `Ad.id`, which exists to be that key (`Ads.kt:76-79`).

## What `AdController` publishes on `state`

Unlike the client-side engines, which populate a partial `AdState`, `AdController` writes every field
on every in-break update (`AdController.kt:109-119`).

| Field | Inside a break | Outside a break |
|---|---|---|
| `isInAdBreak` | `true` | `false` — **gate on this** |
| `currentBreak` | The active `AdBreak` | `null` |
| `currentAd` | The `Ad` the playhead is on | `null` |
| `adIndexInBreak` | 0-based index within the break | `0` |
| `adCountInBreak` | `currentBreak.ads.size` | `0` |
| `adPositionMs` | Offset into the current ad | `0` |
| `adRemainingMs` | `durationMs - adPositionMs`, floored at `0` | `0` |
| `canSkip` | `true` once `adPositionMs >= skipOffsetMs` | `false` |
| `cuePoints` | Every known break's `startMs` | Every known break's `startMs` |

`cuePoints` survives the reset deliberately, so a scrubber can always draw ad markers whether or not
an ad is playing (`AdController.kt:53-56`). Display `adIndexInBreak + 1` for "ad 2 of 3" labels
(`Ads.kt:117-118`).

For contrast, `PlayerState.adState` under client-side ads leaves `currentBreak` `null`, `cuePoints`
empty and, on Android, `canSkip` `false`. See [client-side ads](ads-client-side.md).

## Threading

`AdController` is **not thread-safe** (`AdController.kt:34-35`). Drive `onPosition` and call
`resolveSeek`, `skipCurrentBreak`, `addBreak`, `markWatchedUpTo` and `onEnded` from a single thread —
the main dispatcher is the obvious choice, since that is where your UI reads `state` anyway. It has
no internal scope, no timers and nothing to release.

This is the one part of the SDK with no platform behaviour to state, because it is pure Kotlin with
no `expect`/`actual` anywhere. Whatever it does on Android it does identically on iOS and Web.

## Dependencies you must declare yourself

`AdController.state` and `AdController.events` are typed `StateFlow` and `SharedFlow`, but
`kotlinx-coroutines-core` is declared `implementation`, not `api`, in `core/build.gradle.kts:80`. It
is therefore not on your compile classpath transitively, and you cannot collect either flow until you
add it to your own build:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
```

You do **not** need `kotlinx-serialization` — the parsers take and return ordinary Kotlin types, and
the JSON dependency is internal to `:core`. You will need
`kotlinx-collections-immutable` if you construct a `MediaItem` with non-empty `headers`, for the same
`implementation`-scoping reason (`core/build.gradle.kts:81`). See
[platform support](platform-support.md) for the full list.

## Checklist

- [ ] Fetch the avails yourself and build `List<AdBreak>` with stitched-timeline `startMs` values.
- [ ] Subscribe to `AdController.events` **before** the first `onPosition`.
- [ ] Call `markWatchedUpTo(resumePositionMs)` before the first `onPosition` when resuming.
- [ ] Feed `onPosition` from `player.state`, distinct-until-changed on `positionMs`.
- [ ] Route every seek through `resolveSeek(from, target)`.
- [ ] Call `onEnded()` on `PlayerEvent.Ended` so post-rolls complete.
- [ ] Gate ad UI on `AdState.isInAdBreak`, never on a null check.
- [ ] Deduplicate beacons by `Ad.id`.
- [ ] Build the ad UI and the scrubber lock yourself — `outis-ui` does neither.

---

**See also:** [Client-side ads (IMA)](ads-client-side.md) · [Playback guide](playback.md) ·
[The Compose UI](ui.md) · [Platform support](platform-support.md)
