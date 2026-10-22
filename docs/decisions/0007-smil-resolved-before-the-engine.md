# 7. SMIL is resolved before the engine, and the selection is replaceable

**Status:** Accepted

## Context

Some operator backends publish a SMIL document per asset, listing the manifests that asset exists
as — an HLS `.m3u8` and a DASH `.mpd` over the same content. The client fetches the SMIL, picks the
one it can play, and plays it. The older Wowza convention, where a `<switch>` holds progressive
`mp4:` rungs with no segment timeline, is out of scope: resolving it would mean picking a single
rung and losing adaptive bitrate.

**No engine parses SMIL.** Media3 has no SMIL `MediaSource`, Shaka reads DASH/HLS/MSS, and
AVFoundation takes HLS or progressive with no manifest extension point at all. It is resolved before
the engine or it is not supported. The primitive for that already exists: `httpGetText`, used by the
chapters and thumbnails sidecars, has actuals on all three platforms.

**Which manifest is playable is knowledge `:core` already holds.** AVFoundation has no DASH, so on
iOS it is HLS or nothing. The format choice is also the DRM choice — FairPlay pairs with HLS,
Widevine and PlayReady with DASH. An application choosing for itself re-derives the SDK's own
capability matrix.

**There is no standard for SMIL that points at manifests.** Vendors disagree on where the format is
signalled: the `src` extension, a `type` attribute, `system-profile`, or a `<param>` child. Shipping
one dialect as "SMIL support" would put a vendor matrix in the published API.

## Decision

SMIL is a source, not a container. It is declared as `MediaSource.Smil`, inferred from a `.smil`
extension on a `MediaSource.Url` as `MimeType` is already inferred, and resolved to a
`MediaSource.Url` before any engine sees the item. `Url` and `LocalFile` keep the path they have
today: no added round trip, no added suspend point.

Selection is `PlayerConfig.smilSelector`, defaulting to the platform table and replaceable by an
application. It is not a `PlayerComponent`: that seam is `attach`/`detach` for stateful things
binding to the native player, and a selector is a pure function of the renditions.

The selector receives parsed renditions carrying every attribute the document held, understood or
not, so dialect variance is absorbed by overriding selection rather than by forking the parser.

The parser resolves no external entities, and a rendition URL on a different origin to the SMIL does
not inherit `MediaItem.headers`.

## Consequences

SMIL costs one round trip before first frame, on the startup path. An application already holding
the document can resolve ahead and pass the manifest URL directly, so the cost is opt-out.

Each engine gains a `Smil ->` branch on its exhaustive `when` over `MediaSource`, unreachable by
construction — ceremony for an invariant the type system cannot state.

`MimeType` gains no `SMIL` member. Every member maps to an engine concept in `toExoMimeType()` and
in Shaka's progressive check, and SMIL has none because it never arrives there. The cost is that two
types now answer "what kind of source is this", and an untyped attribute map on the third.
