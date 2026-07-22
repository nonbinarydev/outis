[Outis](../README.md) › [Docs](README.md) › Local files and chapters

# Local files and chapters

How to play a file that is already on the device — a bundled asset, a completed download, a file the
user picked — and how to read the chapter markers embedded in it. No DRM is involved anywhere on this
page.

Two things dominate this subject and neither is obvious from the API: **local playback is a two-and-a-
half platform feature** (it works on Android and iOS; on Web the documented type cannot reach a file
at all), and **chapters arrive asynchronously**, as a second state emission some time after load.

---

## 1. Playing a local file

There are two accepted forms and they behave the same.

```kotlin
import dev.nonbinary.outis.core.source.MediaItem
import dev.nonbinary.outis.core.source.MediaSource

val path = "/data/user/0/com.example.app/files/film.mp4" // Android: context.filesDir

player.setMediaItem(MediaItem(MediaSource.LocalFile(path)), autoPlay = true)
// …or the same file as a URL:
player.setMediaItem(MediaItem(MediaSource.Url("file://$path")), autoPlay = true)
```

`MediaSource.LocalFile` takes an **absolute filesystem path with no scheme** — no `file://` prefix
(`MediaSource.kt:25-31`). Android passes it straight to Media3's `setUri`
(`ExoPlayerEngine.kt:709-713`); iOS wraps it with `NSURL.fileURLWithPath` (`AVPlayerEngine.kt:225`).

`MediaSource.Url` accepts a `file://` URL and both chapter extractors recognise it as local
(`ExoPlayerEngine.kt:614-617`, `AVPlayerEngine.kt:583-586`). Use whichever fits the layer you are
coming from — a `MediaStore`/`NSURL` origin usually hands you a URL, a download manager usually hands
you a path.

> The extractors strip the `file://` prefix literally, with no percent-decoding. A URL containing
> `%20` or any other escape therefore points at a filename that does not exist, and chapters come back
> empty even though playback is fine. Prefer `LocalFile` with a decoded path when the name can contain
> spaces.

**Leave `mimeType` null.** A `LocalFile` is always treated as progressive, never adaptive
(`MediaItem.kt:24-28`), and the enum has only `MP4`, `HLS` and `DASH` (`MimeType.kt:10`) — there is no
`MKV`. Setting `MimeType.MP4` on an mkv actively hurts on Android, where it becomes `video/mp4`
(`ExoPlayerEngine.kt:736-740`); left null, Media3's progressive path sniffs the container from its
bytes, so an extension-less mkv still plays. The iOS engine never reads `mimeType` at all — AVFoundation
opens the asset itself.

Everything else on `MediaItem` applies normally: `startPositionMs` for resume, `startMuted`, `loop`,
`preferredAudioLanguage`, `preferredTextLanguage`, `captionsDefault`. See
[playback.md](playback.md#2-load-media).

---

## 2. What each platform does with a local file

| Capability | Android (Media3) | iOS (AVFoundation) | Web (native `<video>`) |
|---|---|---|---|
| mp4 / m4v | Yes | Yes | Not reachable — see §3 |
| mkv (Matroska) | Yes | No — no demuxer | No — WebM subset only |
| Audio and text tracks in `PlayerState` | Yes | Yes | No — always empty |
| Track selection (`selectTrack` / `clearTextTrack`) | Yes | Yes | No |
| Embedded chapters (`PlayerState.chapters`) | Yes, mp4 and mkv | Yes, mp4 only | No |
| Chapter thumbnails (`chapterThumbnails`) | Yes | Yes | No |
| Embedded container metadata (title, artist, artwork) | Not surfaced | Not surfaced | Not surfaced |

Per-platform behaviour for everything else lives in
[platform-support.md](platform-support.md#per-platform-behaviour-and-known-gaps).

---

## 3. Web: `LocalFile` does not reach a file

The web engine takes `LocalFile.path` verbatim and assigns it to the media element:
`url` is the raw path (`ShakaEngine.kt:398-401`), the source is classified progressive because any
`LocalFile` is (`ShakaEngine.kt:1005`), and the progressive branch does `video.src = url`
(`ShakaEngine.kt:448-451`, `:481`). A browser resolves `/Users/me/film.mp4` against the page origin and
404s; it cannot open a filesystem path, and a page served over HTTP cannot open `file://` either.

So on Web there is no supported route from `MediaSource.LocalFile` to a playing file. What does work is
an ordinary `MediaSource.Url` pointing at something the page origin can serve, or a `blob:` object URL
you created yourself from a `File`/`Blob` — but both of those are URLs, and the field is documented as
a path. Treat "local files on web" as unsupported until that is designed properly.

Even where a progressive URL does play on Web, it surfaces **no tracks**: `loadTracks()` runs only on
the Shaka branch and from Shaka's own track events (`ShakaEngine.kt:462`, `:349-351`), so
`audioTracks` and `textTracks` stay empty and the audio and subtitle controls are inert.

---

## 4. Tracks and subtitles in a local file

On Android and iOS the embedded audio and subtitle tracks of a local file populate
`PlayerState.audioTracks` / `textTracks` exactly like a stream's, with names and languages:

- Android reads Media3 `Format.label` and `Format.language`, and the manifest default flag
  (`ExoPlayerEngine.kt:456-459`).
- iOS reads `AVMediaSelectionOption.displayName` and `extendedLanguageTag`
  (`AVPlayerEngine.kt:618-623`).

Select with `player.selectTrack(track)` and turn subtitles off with `player.clearTextTrack()`
(`VideoPlayer.kt:107`, `:110`). Rendering is the platform's own: Media3's `SubtitleView` inside the
Android surface (`PlayerSurface.android.kt:27`, `:72`), AVKit on iOS. Outis decodes no subtitles itself
on any platform.

**Image-based subtitles (PGS, VobSub).** Media3 1.10.1 — the version this SDK pins
(`gradle/libs.versions.toml:20`) — ships the whole pipeline: `MatroskaExtractor` recognises `S_VOBSUB`
and `S_HDMV/PGS`, and `DefaultSubtitleParserFactory` constructs `VobsubParser` and `PgsParser` (verified
in the resolved `media3-extractor-1.10.1.aar`). Older advice that these are missing on Android is stale.
There is no equivalent on iOS or Web, and since neither can open mkv, the common mkv case does not
arise there.

**Not available anywhere:** a sideloaded `.srt`/`.vtt` sidecar. `MediaItem` has no field for one
(`MediaItem.kt:22-90`), even though Media3 and AVKit both support side-loading. And `MediaTrack` carries
no codec or format field (`MediaTrack.kt:20-39`), so you cannot tell a text subtitle from an image one,
or show "SSA" versus "PGS", in your own track picker.

---

## 5. Chapters

`PlayerState.chapters` (`PlayerState.kt:126-132`) holds the chapter markers embedded in the container,
sorted by start time. They come from a shared parser in `commonMain`,
`dev.nonbinary.outis.core.chapters.ChapterExtractor`, which does small ranged reads over the file rather
than loading it: Media3 exposes no chapter API, so the Android engine runs the parser over a
`RandomAccessFile` (`ExoPlayerEngine.kt:586-612`), and the iOS engine runs the same parser over a
POSIX-backed reader (`AVPlayerEngine.kt:582-609`). Web never populates the field.

```kotlin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

scope.launch {
    player.state
        .map { it.chapters }
        .distinctUntilChanged()
        .collect { chapters -> renderChapterMarkers(chapters) }
}
```

`Chapter` (`Chapter.kt:16-29`):

| Field | Meaning |
|---|---|
| `startMs: Long` | Start on the content timeline, in ms. Always present. |
| `title: String?` | Title if the container carried one; `null` for a start-point-only marker. |
| `endMs: Long?` | The **next** chapter's `startMs`. See the rule below. |
| `thumbnail: ByteArray?` | Raw preview-image bytes; `null` unless you opted in and the file has an image track. §6. |

`Chapter` overrides `equals`/`hashCode` to compare `thumbnail` by content (`Chapter.kt:37-55`), so
chapters decoded twice from the same file compare equal and do not look like a state change. That also
means both are O(image size) when thumbnails are loaded — do not put chapters in a hot recomposition key.

### The five rules that will bite you

1. **They arrive asynchronously.** `setMediaItem` clears `chapters` to empty
   (`ExoPlayerEngine.kt:177`, `AVPlayerEngine.kt:294`) and kicks off a background parse (`:182`, `:299`);
   the parsed list lands as a **second state emission**, after the first frame in most cases
   (`ExoPlayerEngine.kt:610`, `AVPlayerEngine.kt:605`). Code that reads `player.state.value.chapters`
   once after load will nearly always see an empty list. Collect the flow.
2. **A file with no chapters produces no second emission at all** — the engines skip the update when the
   result is empty (`ExoPlayerEngine.kt:608`, `AVPlayerEngine.kt:601`). There is no "parsing finished,
   nothing found" signal, so do not wait for one.
3. **Parse failures are silent.** Any malformed or unreadable container yields an empty list, never a
   `PlayerError` and never an event (`ChapterExtractor.kt:20`, `:49-52`; both engines wrap the call in
   `runCatching`). Chapters must never break playback. The cost is that "no chapters" and "broken file"
   are indistinguishable to you.
4. **The last chapter's `endMs` is always `null`.** `endMs` is derived purely by pairing each chapter
   with the next one's start (`ChapterExtractor.kt:63-67`); nothing fills in the media duration, and no
   parser reads a container-declared end. Compute the final segment yourself from
   `PlayerState.durationMs` when you need it, and handle `durationMs == null`.
5. **`stop()` does not clear them.** It clears the track lists and the media item but leaves `chapters`
   populated from the previous file (`ExoPlayerEngine.kt:292-313`, `AVPlayerEngine.kt:393-411`). They are
   cleared at the next `setMediaItem`. If your chrome survives `stop()`, gate the chapter rail on
   `mediaItem != null` too.

One more, less likely to bite but worth knowing: the publish step is guarded by a **reference** check
against the current item (`ExoPlayerEngine.kt:610`, `AVPlayerEngine.kt:604`). Load item A, then a new but
equal item B while the parse is in flight, and A's chapters are discarded rather than misapplied.

### What the parsers read

- **MP4 / M4V** — QuickTime `chap` text-track chapters (the iTunes/m4v form), with a Nero `chpl`
  fallback. The title track is chosen by media **handler type** (`text`/`sbtl`/`subt`), so a file that
  also carries a chapter *image* track — the JPEG thumbnails tools like Subler write — never has its
  image samples mis-read as titles (`ChapterExtractor.kt:159-172`; test
  `picks_text_track_by_handler_not_first_referenced`).
- **Matroska (mkv)** — the `Chapters` EBML element, presenting exactly **one edition**: the one flagged
  `EditionFlagDefault`, else the first (`ChapterExtractor.kt:435-445`; tests
  `matroska_picks_default_edition_only`, `matroska_uses_first_edition_when_none_default`). Editions are
  alternative chapterings of the same content, not additive, so presenting more than one would be wrong.
  Nested sub-chapters are flattened into the same flat list (`ChapterExtractor.kt:466`).
- Across both: markers are sorted by start and **collapsed by start time** — two chapters at the same
  millisecond become one (`ChapterExtractor.kt:63-67`).

Two known parse gaps, both of which degrade to a shorter or empty list rather than an error:
unknown-size EBML elements positioned *before* `Chapters` (a rare streamed layout — later siblings are
not walked, `ChapterExtractor.kt:484-485`), and chapter titles in non-BOM UTF-16 or MacRoman declared via
an `encd` atom; BOM-prefixed UTF-16 and UTF-8 are handled (`ChapterExtractor.kt:397-416`).

---

## 6. Chapter thumbnails

Opt in per item. Off by default, because it reads image samples rather than a few kilobytes of titles.

```kotlin
player.setMediaItem(
    MediaItem(MediaSource.LocalFile(path), chapterThumbnails = true),
    autoPlay = true,
)
```

`MediaItem.chapterThumbnails` (`MediaItem.kt:83-89`) pulls each chapter's preview image from an MP4
chapter **image** track into `Chapter.thumbnail`. It is honoured on Android and iOS; it does nothing on
Web, and nothing for Matroska or a title-only MP4 — those formats carry no per-chapter images, so
`thumbnail` stays `null` and the titles are unaffected either way.

**The bytes are raw and you decode them.** They are usually JPEG but the format is not guaranteed
(`Chapter.kt:23-29`), and `outis-ui` renders neither chapter markers nor thumbnails — a grep for
"chapter" over `ui/src` returns nothing. Everything chapter-related is yours to draw.

**The budget** (`ChapterExtractor.kt:29-32`), which is what you actually need to reason about before
enabling this on a multi-gigabyte file:

| Ceiling | Value | Effect when exceeded |
|---|---|---|
| One image | 4 MB | That chapter's `thumbnail` stays `null`. |
| All images | 32 MB | Remaining chapters get no thumbnail. |
| Image count | 512 | Chapters past the 512th get no thumbnail. |

Retained bytes live in `PlayerState`, so a fully populated worst case is 32 MB held for the lifetime of
the item. Titles are never affected by any of these limits.

Matching is by ordinal when the image count equals the chapter count; otherwise each chapter takes the
nearest unused image sample within a 2-second tolerance, so an unrelated image track cannot bind a
far-off frame to a chapter (`ChapterExtractor.kt:196-236`).

---

## 7. Hard platform walls

Neither of these can be fixed by SDK work.

- **mkv on iOS.** AVFoundation has no Matroska demuxer, so `.mkv` does not open. The only route is a
  third-party demuxer or player (VLCKit, libVLC, mpv) — a second engine, not a tweak. Chapters follow the
  same wall: the iOS extractor can only read files AVFoundation can play, which means mp4/m4v
  (`AVPlayerEngine.kt:577-581`).
- **mkv on Web.** Browsers implement only the WebM subset of Matroska, so general `.mkv` does not play.
  Supporting it would need WASM demuxing (ffmpeg.wasm or similar) feeding MSE.

---

## 8. Not implemented

Listed so you can plan around them rather than search for them.

- **Embedded container metadata.** Nothing reads Media3's `onMetadata` or AVFoundation's
  `AVMetadataItem` — a grep for either across `core/src/androidMain` and `core/src/iosMain` finds
  nothing — so a file's own title, artist and artwork never surface. `MediaItem.metadata`
  (`MediaItem.kt:78-82`) is app-supplied only and is for display, never playback.
- **Sideloaded subtitle files.** No `MediaItem` field for an `.srt`/`.vtt` sidecar.
- **A codec or format field on `MediaTrack`.**
- **Chapters on Web.** No browser API for embedded chapters; the field stays empty.

---

## See also

- [playback.md](playback.md) — loading, transport, `PlayerState` and track selection in full.
- [platform-support.md](platform-support.md) — every per-platform capability claim, in one place.
- [ui.md](ui.md) — the Compose chrome, which has no chapter support of its own.
- [troubleshooting.md](troubleshooting.md) — symptom → cause → fix.
