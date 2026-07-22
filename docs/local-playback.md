# Local-file playback (mp4 / m4v / mkv)

What Outis and each platform player can do for **on-device local files** — embedded subtitles, audio
tracks, metadata, chapters, and track names/languages. No DRM is involved.

Two kinds of "not supported" are distinguished throughout, because they have very different
consequences:

- **Platform limitation** — the underlying player physically can't do it. No SDK work changes this.
- **Not surfaced** — the platform can, but Outis doesn't expose it yet.

---

## Coverage matrix

Legend: ✅ works · ⚠️ partial/unreliable · ❌ not available · *(not surfaced)* = the platform supports
it, Outis doesn't expose it.

| Capability (local file) | **Android — Media3** | **iOS — AVPlayer** | **Web — native `<video>`** |
|---|---|---|---|
| **Container: mp4 / m4v** | ✅ | ✅ | ✅ (codec-dependent) |
| **Container: mkv (Matroska)** | ✅ | ❌ **platform** (no demuxer) | ❌ **platform** (WebM only) |
| **Audio tracks (multiple)** | ✅ | ✅ | ❌ *(not surfaced; browser API is weak)* |
| **— track names + languages** | ✅ `Format.label` + `language` | ✅ `displayName` + `extendedLanguageTag` | ❌ |
| **Text subtitles — mp4** (tx3g/`mov_text`, CEA-608/708) | ✅ | ✅ | ❌ *(not surfaced)* |
| **Text subtitles — mkv** (SRT, SSA/ASS, WebVTT) | ✅ | n/a (no mkv) | n/a |
| **Image subtitles** (PGS, VobSub) | ⚠️ **unreliable** | ❌ | ❌ |
| **Embedded metadata** (title/artist/artwork) | ❌ *(not surfaced)* | ❌ *(not surfaced)* | ⚠️ *(not surfaced)* |
| **Chapters** (embedded markers) | ✅ | ✅ | ❌ |
| **Track selection (audio/subtitle)** | ✅ | ✅ | ❌ for local files |

The one-line read: **Android is strongest** — playback, text tracks and names/languages across mp4
*and* mkv, with chapters covered by a shared parser; its remaining soft spot is image-based subtitles.
**iOS is strong for mp4/m4v** (chapters via the same parser) but **cannot open mkv at all**. **Web is
weakest** — no mkv, no chapters, and local mp4 surfaces no tracks.

---

## What works today

- **Local source model:** `MediaSource.LocalFile(path)`, played by every engine (Media3 `setUri`,
  `AVURLAsset.fileURLWithPath`, native `<video>.src`).
- **Audio + subtitle track lists with names and languages:** `PlayerState.audioTracks` / `textTracks`
  (`MediaTrack { id, type, label, language, isSelected, isDefault }`), populated on Android (`Format`),
  iOS (`AVMediaSelectionGroup`) and Shaka. Selectable via `selectTrack()` / `clearTextTrack()`.
- **Subtitle rendering** is handled natively by each engine (Media3 `SubtitleView`, AVKit, the browser),
  so embedded text subtitles draw with no extra work once the track is selected.
- **Chapters** — see below.

## Current limitations

1. **No `MimeType.MKV`.** Local mkv still plays via extension detection, but you can't hint the type for
   an extension-less file.
2. **Web local files surface no tracks.** `LocalFile` routes to the native `<video>` element rather than
   Shaka, and track loading only runs on the Shaka path — so `audioTracks`/`textTracks` stay empty and
   the audio/subtitle controls are inert for local mp4 on web. Browser embedded-track APIs are weak in
   their own right, so this is partly a platform constraint.
3. **No embedded metadata extraction.** `MediaItem.metadata` is app-supplied only; the engines'
   `onMetadata` (Media3) and `AVMetadataItem` (iOS) are not read, so nothing surfaces title, artist or
   artwork from the file itself.
4. **No sideloaded subtitle support.** There's no `MediaItem` field for an external `.srt`/`.vtt`
   sidecar, which is common for local files. Media3 and AVKit both support side-loading.
5. **`MediaTrack` has no codec/format field**, so you can't distinguish a text subtitle from an
   (unreliable) image subtitle, or show "SSA" versus "PGS".

---

## Per-capability detail

### Subtitles

- **Text-based, mp4/m4v:** tx3g (`mov_text`) and CEA-608/708 — ✅ Android, ✅ iOS.
- **Text-based, mkv (Android):** SRT (`S_TEXT/UTF8`), SSA/ASS (`S_TEXT/ASS`) and WebVTT
  (`S_TEXT/WEBVTT`) are extracted by Media3's `MatroskaExtractor` and rendered — ✅. This is the
  strongest case for mkv subtitle support.
- **Image-based (PGS, VobSub):** ⚠️ **the weak spot, even on Android.** Media3's supported-formats page
  lists **neither PGS nor VobSub**; VobSub decoding was added relatively recently and primarily for
  **mp4**, and PGS-in-container has open rendering issues (androidx/media #268, ExoPlayer #3008 /
  #8260). Treat PGS/VobSub-in-mkv as **not dependable** and verify per file on-device. Many mkv rips use
  exactly these formats for forced and foreign-language subtitles, so this is a real risk if your goal
  is "play any mkv". iOS and Web: ❌ (mkv unsupported).
- **Sideloaded `.srt`/`.vtt`:** not supported today, though both Android and iOS could via a `MediaItem`
  field.

### Audio tracks, names and languages

- Android (`Format.label`/`language`) and iOS (`displayName`/`extendedLanguageTag`) both surface multiple
  embedded audio tracks **with names and languages** — ✅. Media3 reads the Matroska track `Name` and
  `Language` elements; AVKit reads mp4 alternate-group metadata.
- Web: ❌ for local files — the native path doesn't populate tracks, and `HTMLVideoElement.audioTracks`
  is itself unsupported in Chrome.

### Embedded metadata (title / artist / artwork)

Platform-capable on Android (`Player.Listener.onMetadata` → `MediaMetadata`) and iOS
(`AVAsset.commonMetadata`), but not surfaced by Outis — it would need a `PlayerState` slot plus
per-engine extraction.

### Chapters (Android + iOS)

Implemented as a shared, unit-tested container parser
(`dev.nonbinary.outis.core.chapters.ChapterExtractor`, in `commonMain`) that reads only small ranges
over a `ChapterReader`, surfaced on `PlayerState.chapters`:

- **MP4/M4V:** QuickTime `chap` text-track chapters (the iTunes/m4v form), with a Nero `chpl` fallback.
  The title track is chosen by media handler type (`text`/`sbtl`/`subt`), so a file that *also* carries
  a chapter **image** track — the JPEG thumbnails tools like Subler write — never has its image samples
  mis-read as titles.
- **Chapter thumbnails (opt-in):** set `MediaItem.chapterThumbnails = true` to also pull each chapter's
  preview image into `Chapter.thumbnail` (raw JPEG bytes; the UI decodes them). Off by default, since it
  costs extra IO and memory. Degrades to `null` when the file has no image track — always the case for
  Matroska and title-only MP4s — so treat `thumbnail` as optional.
- **Matroska (mkv):** the `Chapters` element (EBML), selecting a single edition (EditionFlagDefault,
  else the first). The format carries no per-chapter thumbnails, so `thumbnail` is always `null`.
- **Android:** Media3 exposes no chapter API, so the engine runs `ChapterExtractor` over a
  `RandomAccessFile` off-thread. This is why the parser exists.
- **iOS:** the AVFoundation `chapterMetadataGroups` binding isn't exposed in Kotlin/Native cinterop, so
  iOS runs the **same** parser over a POSIX-backed reader — one tested implementation, covering exactly
  what AVFoundation can open (mp4/m4v; never mkv).
- **Web:** ❌ no embedded-chapter API, so `chapters` stays empty.

Known limitations, all of which degrade to an empty or partial list rather than an error: unknown-size
EBML elements positioned *before* `Chapters` (a rare streamed layout), and chapter titles in non-BOM
UTF-16 or MacRoman declared via an `encd` atom (BOM-prefixed UTF-16 and UTF-8 are handled). Real-file
correctness across the full range of container variants is best confirmed on-device.

---

## Hard platform walls

These cannot be fixed by SDK work:

- **mkv on iOS:** AVFoundation has no Matroska demuxer, so `.mkv` won't open. The only route is a
  third-party demuxer or player (VLCKit, libVLC, mpv) — effectively a second engine, not a tweak.
- **mkv on Web:** browsers implement only the WebM subset of Matroska, so general `.mkv` won't play.
  Supporting it would need WASM demuxing (ffmpeg.wasm or similar) feeding MSE.
- **PGS/VobSub anywhere but (unreliably) Android:** image-subtitle decoding isn't present in AVKit or in
  browsers.

---

## Sources

- [Android — Media3/ExoPlayer supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [ExoPlayer #3008 — Support PGS and VOBSUB](https://github.com/google/ExoPlayer/issues/3008) ·
  [#8260 — VobSub](https://github.com/google/ExoPlayer/issues/8260) ·
  [androidx/media #268 — PGS not shown](https://github.com/androidx/media/issues/268)
