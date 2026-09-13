package com.rikkaminis.app.ui.chat

/**
 * [fix/open-row-first-frame-final] Cold-open markdown prewarm source selection.
 *
 * ## The bug this replaces
 *
 * ChatScreen's cold-open prewarm collected its raw markdown with
 *
 * ```
 * val raw = (item as? FlatChatItem.AssistantMarkdownBlock)?.rawText ?: continue
 * ```
 *
 * — a row type that only the LEGACY fragment generator emits. Under
 * `AGGREGATE_MESSAGE_ITEMS = true` (the default since Stage D) the flatten
 * pipeline emits exactly ONE row per message, [FlatChatItem.AssistantMessageItem],
 * so that cast failed for EVERY row, `raws` came back empty and the whole
 * "parallel viewport prewarm" pass silently did nothing. The comment above it
 * ("block-parse + inline-warm the newest markdown fragments off-main so the
 * first frame's rows compose as cache HITs") described a pass that had not run
 * once since the aggregate flip.
 *
 * Device forensics (2026-09-13, /data/local/tmp/rc2.log): no `coldPrewarm.done`
 * line in any session open, `coldOpen summary … prewarmMs=-1` on all of them,
 * and every open's newest row GREW after the INITIAL_OPEN snap resolved against
 * its first layout (`firstItem.placed` 1,065→5,255px; 13,350→25,995px) — which
 * is what produces both "opens one screen short" and the visible catch-up roll.
 *
 * ## Contract
 *
 * [markdownSourcesForRow] returns the exact strings the row's renderer will
 * feed to the markdown parser, so prewarming them is a pure win:
 *  - [FlatChatItem.AssistantMessageItem] → its `kind == "text"` block contents
 *    in order (mirrors `AssistantMessageView`); the message-level fallback
 *    content only when the message carries no text block at all.
 *  - [FlatChatItem.AssistantMarkdownBlock] / [FlatChatItem.AssistantText] /
 *    [FlatChatItem.AssistantLegacyContent] → their own markdown.
 *  - everything else (user bubbles, headers, thinking, tool pills, typing,
 *    errors, info rows) → empty, they render no markdown body.
 *
 * [collectColdOpenPrewarmSources] walks newest-row-first with a source cap and
 * a character budget, de-duplicating identical fragments (a budget must not be
 * spent twice on the same string). Newest-first matters: the opening snap
 * resolves against the LAST row, so its fragments are the ones that must be
 * warm before the rows are published.
 *
 * Pure — JVM-testable (no Compose / Android deps beyond the row model).
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
    is FlatChatItem.AssistantMarkdownBlock -> listOfNotNull(item.rawText.takeIf { it.isNotEmpty() })
    is FlatChatItem.AssistantText -> listOfNotNull(item.block.content.takeIf { it.isNotEmpty() })
    is FlatChatItem.AssistantLegacyContent -> listOfNotNull(item.content.takeIf { it.isNotEmpty() })
    else -> emptyList()
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
