# 6. The demo's stream-tester form is Compose, not DOM inputs on web

**Status:** Accepted

## Context

The sample is growing a Bitmovin/JW-style stream tester — URL fields, a DRM toggle, licence server and
header inputs — alongside a catalogue browser. Both are ways of building a `MediaItem` for the same
player.

Compose Multiplatform renders web into a single skiko `<canvas>`, so text fields are drawn rather than
being real DOM `<input>` elements. Text entry has historically been the weak point of that approach:
paste, selection, focus traversal and soft keyboards are reimplemented rather than inherited from the
browser. A stream tester is the most input-heavy UI the demo could have, and web is the build most
evaluators will touch, since it is the one linked from the Pages site.

The alternative was DOM inputs overlaid on the canvas for web only — which is what Bitmovin and JW
themselves do, their forms being ordinary HTML beside a player rather than inside it. That would mean an
`expect`/`actual` form layer and two implementations of every control.

A throwaway spike (`?spike`, since deleted — see history) put the real widgets on the canvas: two URL
fields, a DRM switch revealing scheme radios, licence and header inputs, and a panel echoing each
field's length and contents so silent mangling would be visible rather than assumed. Paste, selection,
tab traversal and overflow scrolling all behaved.

## Decision

Build the form in Compose, shared across Android, iOS and Web. No DOM input layer, no `expect`/`actual`
for form controls.

## Consequences

One implementation, and the form is a live exercise of the shared API — the same reason the tester is
worth building at all: it forces every `MediaItem` and `DrmConfig` field to be reachable and
comprehensible from outside, and gaps surface in the demo rather than in someone's integration.

The finding is a point-in-time observation on current Compose and skiko, from a desktop browser. It was
not tested against a mobile soft keyboard, and it is not a guarantee for future versions. If web text
entry regresses, the fallback is the rejected option and it is confined to the sample.

Accepting Compose input also accepts its accessibility ceiling on web: canvas-drawn fields do not carry
native semantics, so screen-reader and password-manager behaviour is whatever Compose provides, not
whatever the browser would have given a real `<input>`.
