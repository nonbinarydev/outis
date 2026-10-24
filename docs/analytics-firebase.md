[Outis](../README.md) › [Docs](README.md) › [Analytics](analytics.md) › Firebase

# Firebase / GA4 usage adapter

`outis-analytics-firebase` reports **usage** to [Google Analytics 4](https://support.google.com/analytics/answer/10089681)
— what viewers watch — through Firebase on Android and iOS and through gtag.js on the web.

It is the counterpart of the [Mux adapter](analytics-mux.md), not a replacement for it. Mux measures how
well a video played: startup time, rebuffering, rendition switches. This adapter records that it played.
Run both if you want both.

Unlike Mux, it translates `PlayerEvent`s (ADR-[0011](decisions/0011-usage-adapters-translate-cross-engine-events.md)).
Firebase has no player integration to bind to, and the events usage needs fire on every engine, so the
adapter reports the same thing on Android, iOS and the web.

## What it reports

| GA4 event | When |
|---|---|
| `video_start` | An item first plays: once per play-through, not again after a pause, a stall or a seek. A replay after the end starts again. |
| `video_complete` | The item played to its end. |
| `video_error` | Playback failed for good. Adds `error_category` and, when the engine has one, `error_code`. |

`video_start` and `video_complete` are GA4's own names, the ones its YouTube measurement uses, so
reports built for those work unchanged. Each event carries the item's
[`PlaybackMetadata`](analytics.md):

| Parameter | From |
|---|---|
| `video_title` | `PlaybackMetadata.title`, else the item's display title |
| `video_id` | `PlaybackMetadata.videoId` |
| `video_series` | `PlaybackMetadata.series` |
| `video_duration` | `PlaybackMetadata.durationMs`, else the player's duration, in seconds |
| `video_current_time` | The position at the event, in seconds |
| `stream_type` | `live` or `vod`: `PlaybackMetadata.streamType`, else the player's own reading |
| `video_provider` | Always `outis` |

Absent values are left out rather than sent empty. Rebuffering, bitrate and dropped frames are not
reported: they are quality of service, and Mux's.

## Add the dependency

```kotlin
implementation("io.github.nonbinarydev:outis-analytics-firebase:0.1.0-alpha01")
```

On **Android** the adapter brings the Firebase Analytics SDK with it. Your app adds the Google services
Gradle plugin and its `google-services.json`, as for any Firebase app; without them there is no Firebase
app, and the adapter drops every event.

On **iOS** Firebase is CocoaPods, so your app links it directly (the adapter carries only the Kotlin
bindings), adds its `GoogleService-Info.plist`, and calls `FirebaseApp.configure()`. Until it has, the
adapter drops every event:

```ruby
target 'YourApp' do
  use_frameworks!
  pod 'FirebaseAnalytics', '~> 12.19'   # keep the major aligned with the adapter's binding
end
```

On the **web** there is no SDK to add. The page loads gtag.js with its measurement id (`G-…`), and the
adapter calls the page's `gtag`. Until the page has loaded it, there is no `gtag` and events are dropped.

## Register it

`FirebaseUsageAnalytics` is a `PlayerComponent`. Construct it with the same `AppContext` you built the
player with:

```kotlin
val usage = FirebaseUsageAnalytics(appContext)
player.addComponent(usage)
```

`removeComponent`, or releasing the player, stops it. One instance per player.

## Your own events

What the viewer chose is not a player event, so the app reports it, through the same instance:

```kotlin
usage.logEvent("select_content", mapOf("content_type" to "video", "content_id" to item.id))
```

Values may be `String`, `Long`, `Int` or `Double`. GA4 drops names and values past its limits, so the
adapter reduces names to letters, digits and underscores, at most 40 characters, and cuts string values
to 100. Never send a URL a viewer typed, or anything else that could identify them: GA4's terms forbid
personal data.

## Consent

Usage analytics keeps an identifier on the viewer's device, which in the EU usually means asking first.
Attach the adapter only once the viewer agrees, and apply their choice to Firebase as a whole:

```kotlin
FirebaseUsageAnalytics.setCollectionEnabled(appContext, enabled = granted)
```

Firebase collects automatic events of its own, such as `first_open` and `screen_view`, as soon as it
starts. So that nothing is collected before the viewer has chosen, also turn collection off by default:

- **Android**, in the application manifest:
  `<meta-data android:name="firebase_analytics_collection_enabled" android:value="false" />`
- **iOS**, in `Info.plist`: `FIREBASE_ANALYTICS_COLLECTION_ENABLED` = `NO`.

`setCollectionEnabled` then turns it on, and Firebase remembers the choice across launches.

On the **web**, `setCollectionEnabled` sets Consent Mode's `analytics_storage`, which governs gtag.js's
cookies rather than whether it sends. To send nothing before consent, load gtag.js only once it is given;
to stop a page that has already loaded it, set `window['ga-disable-G-…'] = true` for its measurement id.
The sample does both.

Whether usage analytics needs consent, and on what terms, is a question for your legal basis, not for
this library.

## The sample

The sample wires the adapter behind its **Marketing** consent, logs `select_content` when a video is
chosen, and stays Firebase-free unless a build supplies the configuration. See
[`sample/README.md`](../sample/README.md#firebase).

## See also

- [Analytics, QoS and the plugin seam](analytics.md) — the event stream this is built on
- [Mux Data adapter](analytics-mux.md) — quality of service
- [ADR-0011](decisions/0011-usage-adapters-translate-cross-engine-events.md) — why this adapter, unlike
  Mux's, translates `PlayerEvent`s
