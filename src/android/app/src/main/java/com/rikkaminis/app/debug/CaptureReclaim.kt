package com.rikkaminis.app.debug

/**
 * [WAVE-1118 D9-F08] A debug capture's frame must be released on the failure
 * path of `PixelCopy`.
 *
 * Both screenshot channels allocate a full-screen `ARGB_8888` bitmap, hand it to
 * `PixelCopy.request`, and — when the copy is refused (`result != SUCCESS`, e.g.
 * a secure surface or a detaching window) — fall back to drawing the view onto a
 * second bitmap. The first frame used to be abandoned there: one full-screen
 * frame leaked per refused capture, in two files that are copies of each other.
 *
 * The encode half of the same family (`compress()` throwing before `recycle()`)
 * stays a plain `try/finally` in both files: that shape is what
 * `scripts/scan/discarded_return_guard.py` (arms B2/B3) reads and enforces, and
 * wrapping it in a helper moved the call out from under its `record-ok:`
 * exemption.
 *
 * Generic on the capture type on purpose: the decision — "release before the
 * replacement is allocated, and a failed release must not lose the frame we
 * return" — carries no Android, so it is JVM-testable.
 */

/**
 * [WAVE-1118 D9-F08] Release a capture that produced no usable frame, then
 * return [fallback]'s replacement.
 *
 * Order is the contract: release first, so the abandoned frame is not held
 * while a second full-screen bitmap is allocated. A release failure never
 * costs the caller the frame it asked for.
 */
internal inline fun <B> discardFailedCapture(capture: B, noinline release: (B) -> Unit, fallback: () -> B): B {
    runCatching { release(capture) }
    return fallback()
}
