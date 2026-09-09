package com.rikkaminis.app.deeplink

/**
 * Path-traversal guard for the `minis://session/<id>/<resource-path>` deep
 * link family.
 *
 * The resource path is meant to address a file *under* `/var/minis/` (e.g.
 * `/browser/snake.html`). A crafted link could smuggle dot-segments (`..`) or
 * separator characters (`\`, `:`) into that tail and escape the sandbox when
 * the path is later concatenated / resolved against the host filesystem.
 *
 * `DeepLinkHandler` rejects the whole link at parse time (returns
 * [DeepLinkAction.Unknown] rather than degrading silently), and
 * `ChatScreen` re-checks via `PRootKernel.resolveHostPath` — two independent
 * layers of defence, since `resolveHostPath`'s `safeResolveWithin` also
 * normalizes dot-segments and enforces a prefix guard on its own.
 *
 * Kept JVM-pure (no Android imports) so the guard is unit-testable without
 * Robolectric.
 */
object DeepLinkPathGuard {

    /**
     * True when a single path [segment] can escape the `/var/minis` root:
     *
     *  - `.` / `..` — dot-segments that climb directories (or refer to the
     *    current directory).
     *  - contains `:` — scheme/ADS-style separator; can also smuggle a
     *    Windows drive prefix (`C:`).
     *  - contains `\` — Windows path separator; the sandbox paths are
     *    `/`-separated, so a backslash is always suspicious.
     */
    fun isUnsafeSegment(segment: String): Boolean {
        if (segment == "." || segment == "..") return true
        if (segment.contains(':') || segment.contains('\\')) return true
        return false
    }

    /**
     * True when any non-empty segment of [resourcePath] (split on `/`) is
     * unsafe. Empty segments (from `//`) are ignored on purpose — they are
     * legitimate double-slashes that the resolver collapses, not a traversal.
     */
    fun hasUnsafeSegment(resourcePath: String): Boolean =
        resourcePath.split('/').any { it.isNotEmpty() && isUnsafeSegment(it) }
}
