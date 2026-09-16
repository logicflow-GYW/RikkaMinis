package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.deeplink.DeepLinkPathGuard

/**
 * Pure reason classification for an unresolvable minis:// resource link.
 * JVM-pure (no Android imports) so it is unit-testable without Robolectric.
 *
 * [ChatLinkResolver] hands this to [ChatLinkAction.MissingFile]; the chat UI
 * picks the toast string from it. Only ILLEGAL_PATH is determinable at
 * resolve time — cleaned-up vs stale both surface as a missing file, so we
 * do NOT guess between them (refuse-instead-of-guess).
 */
enum class ChatLinkMissingReason {
    /** The path itself was malformed — dot-segment (`..`) escape, `\`, or
     *  `:` smuggling, rejected by [DeepLinkPathGuard] before the file system
     *  was ever consulted. Mirrors the guard the sandbox-path resolver
     *  applies downstream ([PRootKernel] `safeResolveWithin`). */
    ILLEGAL_PATH,

    /** Well-formed path, no file on disk (cleaned up or cross-session stale). */
    FILE_MISSING,
}

/**
 * Classify an unresolvable minis:// link by re-running the same segment
 * guard the path resolver applies: the URL's path (after the scheme,
 * percent-decoded) goes through [DeepLinkPathGuard.hasUnsafeSegment]. A
 * link that the guard would reject was malformed from birth — the resolver
 * never even reached the file system.
 */
internal fun chatLinkMissingReason(rawUrl: String): ChatLinkMissingReason {
    val path = rawUrl.removePrefix("minis://").substringBefore('?')
    val decoded = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
    return if (DeepLinkPathGuard.hasUnsafeSegment(decoded)) {
        ChatLinkMissingReason.ILLEGAL_PATH
    } else {
        ChatLinkMissingReason.FILE_MISSING
    }
}
