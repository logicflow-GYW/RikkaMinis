package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMMessage

/**
 * [fix/replay-guard-cost-1123] O(1) row lookup for [guardAnthropicReplayHistory].
 *
 * Before this class the guard ran two full-history scans for EVERY
 * replay-bearing message:
 *
 *     original.indices.singleOrNull { sameHistoryRow(message, original[it]) }
 *     messages.count { sameHistoryRow(message, it) } == 1
 *
 * A 591-row Anthropic session therefore paid ~700k row comparisons on every
 * request build (device log: "agentHistory rebuilt tookMs=935..1375"). Both
 * expressions are reproduced here as O(1) lookups with the SAME semantics:
 *
 *  - a persisted row matches by `(dbMessageId, role)` — exactly what
 *    [sameHistoryRow] compares when `a.dbMessageId != null`;
 *  - a dbMessageId that occurs more than once is AMBIGUOUS -> null, never the
 *    first index (`singleOrNull` contract);
 *  - a synthesized row (`dbMessageId == null`) matches by REFERENCE ONLY, and
 *    must still occur exactly once in `original` to resolve.
 *
 * The index is built lazily by the caller: a history with no replay-bearing
 * message never constructs it, so the OpenAI/Gemini path cannot be slowed down
 * by this change.
 */
internal class HistoryRowLocator(
    private val original: List<LLMMessage>,
    private val messages: List<LLMMessage>,
) {
    private data class RowKey(val dbMessageId: String, val role: LLMMessage.Role)

    /** Keys that occur exactly once in [original]; ambiguous keys are absent. */
    private val uniqueOriginalIndex: Map<RowKey, Int>

    /** How many [messages] rows carry each key (the `count == 1` arm). */
    private val messageOccurrences: Map<RowKey, Int>

    init {
        val firstIndex = HashMap<RowKey, Int>()
        val ambiguous = HashSet<RowKey>()
        original.forEachIndexed { index, row ->
            val id = row.dbMessageId ?: return@forEachIndexed
            val key = RowKey(id, row.role)
            if (firstIndex.containsKey(key)) ambiguous.add(key) else firstIndex[key] = index
        }
        for (key in ambiguous) firstIndex.remove(key)
        uniqueOriginalIndex = firstIndex

        val counts = HashMap<RowKey, Int>()
        for (row in messages) {
            val id = row.dbMessageId ?: continue
            val key = RowKey(id, row.role)
            counts[key] = (counts[key] ?: 0) + 1
        }
        messageOccurrences = counts
    }

    /** Index of the one [original] row this message provably came from, else null. */
    fun sourceIndexOf(message: LLMMessage): Int? {
        val id = message.dbMessageId
            ?: return original.indices.singleOrNull { original[it] === message }
        return uniqueOriginalIndex[RowKey(id, message.role)]
    }

    /** Occurrences of this message in the projected history (`== 1` arm). */
    fun occurrenceCount(message: LLMMessage): Int {
        val id = message.dbMessageId
            ?: return messages.count { it === message }
        return messageOccurrences[RowKey(id, message.role)] ?: 0
    }
}
