/*
 * Copyright 2026 The Outis Authors.
 *
 * Licensed under the Apache License, Version 2.0. See LICENSE for details.
 */

package dev.nonbinary.outis.core

/**
 * Normalized, engine-agnostic playback error.
 *
 * [nativeCause] retains the original (`ExoPlaybackException`, `NSError`, Shaka error object) for
 * adapters that want engine-specific detail. [Category] is a small, stable taxonomy; `DRM` is
 * emitted by the Android (Media3) and Web (Shaka) engines on key-system/license failures — iOS does
 * not yet surface it.
 */
data class PlayerError(
    /** Coarse, cross-engine classification — the only field safe to branch on in shared code. */
    val category: Category,
    /**
     * The **engine's own** error code rendered as a string (Media3's `errorCodeName`, the `NSError`
     * code, the numeric Shaka code), or `null` when the engine gave none. Values and their meaning
     * differ per platform, so treat it as diagnostic text for logs and bug reports — never key
     * behaviour off it; use [category] for that.
     */
    val code: String? = null,
    /**
     * Human-readable description from the engine, or `null` when it supplied none. Not localised and
     * not stable across engine versions — **do not** show it verbatim to end users.
     */
    val message: String? = null,
    /**
     * The original engine exception/object (`ExoPlaybackException`, `NSError`, Shaka error object), or
     * `null` when the error was synthesised by the SDK itself (e.g. an unsupported DRM scheme detected
     * before playback) or the engine exposed no object. Cast it in platform-specific code only.
     */
    val nativeCause: Any? = null,
) {
    /**
     * The stable taxonomy playback failures are normalised into. Deliberately small: engines classify
     * on wildly different code spaces, so anything finer would not mean the same thing twice.
     */
    enum class Category {
        /**
         * The media itself is unusable or unreachable: bad/unresolvable URL, 404 or other bad HTTP
         * status, malformed container/manifest, or a live stream that fell irrecoverably behind its
         * window. Retrying the same source is unlikely to help.
         */
        SOURCE,

        /** Transport-level failure — connection refused, timeout, TLS or DNS error. Usually retryable. */
        NETWORK,

        /**
         * Key-system or licence failure: no CDM for the requested scheme, licence server rejection,
         * or expired/absent keys. **Only Android (Media3) and Web (Shaka) emit this** — iOS currently
         * reports FairPlay failures as [SOURCE].
         */
        DRM,

        /** Decoding failed: no decoder for the codec/profile, decoder init failure, or corrupt samples. */
        DECODER,

        /** The render pipeline failed — surface/output problems, not decoding of the samples themselves. */
        RENDERER,

        /** The engine reported a failure that maps to none of the above; inspect [code] and [nativeCause]. */
        UNKNOWN,
    }
}
