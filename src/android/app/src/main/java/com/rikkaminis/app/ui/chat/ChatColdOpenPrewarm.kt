package com.rikkaminis.app.ui.chat

/**
 * Select the exact markdown strings rendered by message-level assistant rows.
 * Text blocks retain their order, with message content as the fallback.
 * Newest-first collection, source cap, character budget and deduplication are
 * shared by the awaited newest-row warm and the async viewport prewarm.
 */

/** Character budget for the async cold-open prewarm pass (was a local 96_000). */
internal const val COLD_OPEN_PREWARM_CHAR_BUDGET = 96_000

/**
 * Character budget for the AWAITED newest-row warm (block-parse inside the
 * row-build job, before the rows are published). This one delays the first
 * paint by its parse time — a few ms for a typical answer, and the reason the
 * cap exists at all: a pathological session must not stall its own open. Past
 * the cap the newest row's remaining fragments keep today's behaviour (parsed
 * off-main at composition time, corrected by the bounded catch-up).
 */
internal const val COLD_OPEN_NEWEST_ROW_CHAR_BUDGET = 48_000

/** Fragments cap for that same awaited warm (a row with a pathological block count). */
internal const val COLD_OPEN_NEWEST_ROW_MAX_SOURCES = 32

internal fun markdownSourcesForRow(item: FlatChatItem): List<String> = when (item) {
    is FlatChatItem.AssistantMessageItem -> {
        val texts = item.message.toolBlocks
            .filter { it.kind == "text" }
            .map { it.content }
            .filter { it.isNotEmpty() }
        if (texts.isNotEmpty()) {
            texts
        } else {
            listOfNotNull(item.message.content.takeIf { it.isNotEmpty() })
        }
    }
    is FlatChatItem.UserBubble -> emptyList()
}

/**
 * The fragments of the row the opening snap resolves against — the LAST
 * [FlatChatItem] (the bottom sentinel / resume banner are extra LazyColumn
 * items, not members of `flatItems`). These are block-parsed before the rows
 * are published so the newest row's first layout is already its final height.
 *
 * Bounded by [charBudget] / [COLD_OPEN_NEWEST_ROW_MAX_SOURCES] because this
 * parse runs INSIDE the row-build job on the critical path to the first paint.
 */
internal fun newestRowMarkdownSources(
    rows: List<FlatChatItem>,
    charBudget: Int = COLD_OPEN_NEWEST_ROW_CHAR_BUDGET,
): List<String> = collectColdOpenPrewarmSources(
    rowsNewestFirst = listOfNotNull(rows.lastOrNull()),
    maxSources = COLD_OPEN_NEWEST_ROW_MAX_SOURCES,
    charBudget = charBudget,
)

/**
 * Newest-first source selection for the async prewarm pass: at most
 * [maxSources] fragments and at most [charBudget] characters of distinct
 * markdown, in the order the rows appear from the bottom of the list upwards.
 */
internal fun collectColdOpenPrewarmSources(
    rowsNewestFirst: List<FlatChatItem>,
    maxSources: Int,
    charBudget: Int,
): List<String> {
    if (maxSources <= 0 || charBudget <= 0) return emptyList()
    val out = LinkedHashSet<String>()
    var chars = 0
    for (item in rowsNewestFirst) {
        for (raw in markdownSourcesForRow(item)) {
            if (out.size >= maxSources || chars >= charBudget) return out.toList()
            if (out.add(raw)) chars += raw.length
        }
    }
    return out.toList()
}
