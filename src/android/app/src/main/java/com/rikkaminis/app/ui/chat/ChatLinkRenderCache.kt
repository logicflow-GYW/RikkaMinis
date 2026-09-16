package com.rikkaminis.app.ui.chat

import androidx.compose.runtime.compositionLocalOf

/**
 * [23c-2] Memoizes render-time link resolution for markdown text blocks.
 *
 * MdText resolves every distinct `url` annotation during composition so
 * missing-file links can be styled as grey / disabled instead of looking
 * tappable. Resolution touches the filesystem (PRootKernel path lookups plus
 * `File.exists()`), so without a cache every recomposition of every text
 * block would re-walk it and drag down the chat list.
 *
 * Keyed by (url, sessionId). The owner (ChatScreen) clears it whenever the
 * message list grows — new messages can accompany tool-driven file changes,
 * so a stale "missing" verdict must not survive the next turn.
 */
class ChatLinkRenderCache(
    private val resolveFn: (url: String, sessionId: String?) -> ChatLinkAction,
    private val maxEntries: Int = 256,
) {
    private val cache = object : LinkedHashMap<Pair<String, String?>, ChatLinkAction>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<String, String?>, ChatLinkAction>,
        ): Boolean = size > maxEntries
    }

    fun resolve(url: String, sessionId: String?): ChatLinkAction {
        val key = url to sessionId
        cache[key]?.let { return it }
        val action = resolveFn(url, sessionId)
        cache[key] = action
        return action
    }

    fun clear() = cache.clear()
}

/**
 * Resolver handed to markdown text blocks for render-time link checks.
 * Provided by ChatScreen (backed by [ChatLinkRenderCache]); null in
 * non-chat contexts (standalone previews), which skip the check entirely
 * and render links as before.
 */
val LocalMarkdownLinkRenderResolver =
    compositionLocalOf<((url: String) -> ChatLinkAction?)?> { null }
