package com.openminis.app.ui.chat

// [fix/audit-b22 / T2-L3] RUNTIME-DEAD while AGGREGATE_MESSAGE_ITEMS = true
// (ChatScreen.kt) — same as StableChatRowLedger: the aggregate render path
// never reaches this segmenter. Kept for the Stage-E fallback + its tests.

/**
 * One stable markdown fragment slot produced by [AppendOnlyMarkdownSegmenter].
 *
 * Invariants (the whole point of the segmenter):
 *  - A [settled] slot's `rawText` NEVER changes after publication.
 *  - The live (non-settled) slot is always the LAST slot; it may only grow
 *    (append) while the stream is producing content.
 *  - `ordinal` is a monotonic per-message sequence number — it is the stable
 *    part of the row key (`mdslot:<messageId>:<blockId>:<ordinal>`) so
 *    LazyColumn anchors never see keys deleted or reordered mid-stream.
 */
data class StableMarkdownSlot(
    val ordinal: Int,
    val rawText: String,
    val settled: Boolean,
)

/**
 * Append-only markdown fragment segmenter for a single streaming text block.
 *
 * Replaces the "re-split + coalesce on every tick / at stream end" behaviour
 * of [buildFlatChatItems] for the LIVE tail of an active assistant turn.
 *
 * The contract:
 *  - [update] takes the cumulative markdown snapshot of ONE text block plus a
 *    `streamEnded` flag. It returns the current list of stable slots.
 *  - Published (settled) slots are immutable: once a fragment boundary has
 *    closed them they are never modified, never deleted, never reordered.
 *  - New fragment boundaries only append NEW slots at the tail; the previous
 *    live slot is settled in place with its content frozen.
 *  - A regressive snapshot (shorter + prefix — see
 *    [shouldIgnoreRegressiveStreamingSnapshot]) is ignored entirely.
 *  - Any other non-append divergence (the model rewrote earlier text) cannot
 *    be repaired by key churn: the fresh content is absorbed into the live
 *    slot and [invariantErrorCount] is incremented. Published keys stay put.
 *  - `streamEnded=true` only settles the last slot — it does NOT re-split,
 *    does NOT coalesce, does NOT change keys.
 *
 * The split boundary rules deliberately match [splitMarkdownIntoBlockTexts]:
 * blank lines outside code fences and complete fenced code blocks are
 * closable boundaries; an unterminated fence keeps everything inside the live
 * slot.
 */
class AppendOnlyMarkdownSegmenter {

    private val slots = mutableListOf<StableMarkdownSlot>()
    private var lastCumulativeText: String = ""

    /** Number of times a non-append divergence was absorbed instead of re-keyed. */
    var invariantErrorCount: Int = 0
        private set

    /** Current stable slot list. Callers should treat this as immutable. */
    fun snapshot(): List<StableMarkdownSlot> = slots.toList()

    /**
     * Advance the segmenter with the latest cumulative text snapshot.
     *
     * @return the current stable slot list (same instance semantics as
     *         [snapshot], refreshed).
     */
    fun update(cumulativeText: String, streamEnded: Boolean): List<StableMarkdownSlot> {
        // Empty snapshot or a regressive one (shorter, same prefix) can never
        // change published slots. streamEnded may still settle the live slot.
        if (cumulativeText.isEmpty() ||
            shouldIgnoreRegressiveStreamingSnapshot(lastCumulativeText, cumulativeText)
        ) {
            if (streamEnded) settleLiveSlot()
            return snapshot()
        }
        lastCumulativeText = cumulativeText

        val fresh = splitMarkdownIntoBlockTexts(cumulativeText)
        if (fresh.isEmpty()) {
            // Defensive: splitter returned nothing for non-empty input.
            if (streamEnded) settleLiveSlot()
            return snapshot()
        }

        // ── 1. Match the published settled prefix against the fresh split ──
        // The splitter is deterministic: as long as the cumulative text only
        // grows, the first N fragments are byte-identical to the previously
        // published settled slots. Any mismatch is a non-append divergence.
        val settledCount = slots.count { it.settled }
        var matched = 0
        while (matched < settledCount &&
            matched < fresh.size &&
            slots[matched].rawText == fresh[matched]
        ) {
            matched++
        }

        if (matched < settledCount) {
            // Non-append divergence: earlier fragments changed (model rewrote
            // text mid-stream, or a retry/fallback re-emitted a different
            // prefix). KEY INVARIANT preserved: the published settled prefix
            // stays put and the slot COUNT stays `settledCount + 1` — no key
            // churn, no LazyColumn anchor jump. Only the live slot's content
            // is re-derived from the fresh split's tail AFTER the settled
            // prefix, so we never re-emit text the settled slots already show.
            // The length-growth of a settled fragment (A → A+Δ, e.g. "致命伤"
            // → "致命伤致命") is deliberately dropped: the settled slot is
            // immutable, and absorbing Δ would duplicate its prefix — the exact
            // `致命伤致命伤` artifact. (The first fix attempted here rewrote the
            // tail with ALL fresh fragments, which let the slot count track
            // fresh.size and regressed the scrolling-jump fix.)
            invariantErrorCount++
            return absorbDivergence(fresh, settledCount, streamEnded)
        }

        // ── 2. Prefix OK — rebuild slots = frozen prefix + fresh tail ──
        val remaining = fresh.subList(settledCount, fresh.size)
        val rebuilt = ArrayList<StableMarkdownSlot>(settledCount + remaining.size)
        for (i in 0 until settledCount) rebuilt.add(slots[i]) // frozen, reused by reference
        for (i in remaining.indices) {
            val isLast = i == remaining.lastIndex
            rebuilt.add(
                StableMarkdownSlot(
                    ordinal = settledCount + i,
                    rawText = remaining[i],
                    // streamEnded settles even the last one; otherwise only
                    // the tail fragment stays live (and may grow next tick).
                    settled = streamEnded || !isLast,
                )
            )
        }
        slots.clear()
        slots.addAll(rebuilt)
        return snapshot()
    }

    /**
     * Divergence path (absorb, NOT rewrite): keep the settled slots frozen,
     * and re-derive ONLY the live slot's content from the fresh split's tail
     * AFTER the settled prefix — i.e. everything the fresh split produces
     * beyond the frozen settled slots. The slot COUNT stays
     * settledCount + 1 (identical to the old absorb behaviour and to any
     * normal-append tick), so LazyColumn keys never churn and anchors never
     * jump. Because the live slot starts AFTER the settled prefix, it can
     * never re-emit a settled slot's text — no duplication.
     *
     * If the fresh split shrank below `settledCount` (the model deleted whole
     * paragraphs), we keep the previous live slot content — settled slots are
     * immutable and there is no sensible fresh tail to show.
     *
     * @param fresh        the full fresh split of the cumulative text.
     * @param settledCount number of published settled slots (frozen).
     */
    private fun absorbDivergence(fresh: List<String>, settledCount: Int, streamEnded: Boolean): List<StableMarkdownSlot> {
        val rebuilt = ArrayList<StableMarkdownSlot>(settledCount + 1)
        for (i in 0 until settledCount) rebuilt.add(slots[i])

        if (fresh.size <= settledCount) {
            // Fresh split shrank below (or equal to) the settled prefix — there
            // is no fresh tail to absorb. Keep ONLY the frozen settled slots:
            // appending an empty live slot here would publish a new key for no
            // content (LazyColumn churn — the exact jump regression). The prior
            // old-live content is dropped deliberately: showing it would
            // duplicate a paragraph the model has now removed.
            slots.clear()
            slots.addAll(rebuilt)
            if (streamEnded) settleLiveSlot()
            return snapshot()
        }

        val liveText = fresh.subList(settledCount, fresh.size).joinToString("\n\n")

        rebuilt.add(
            StableMarkdownSlot(
                ordinal = settledCount,
                rawText = liveText,
                settled = streamEnded,
            )
        )
        slots.clear()
        slots.addAll(rebuilt)
        return snapshot()
    }

    private fun settleLiveSlot() {
        if (slots.isEmpty()) return
        val lastIdx = slots.lastIndex
        if (!slots[lastIdx].settled) {
            slots[lastIdx] = slots[lastIdx].copy(settled = true)
        }
    }
}

