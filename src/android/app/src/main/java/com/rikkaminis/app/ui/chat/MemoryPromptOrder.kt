package com.rikkaminis.app.ui.chat

/**
 * [fix/memcache-prefix-split 2026-11-12] Stable/volatile split for the
 * injected memory fragments in the system prompt.
 *
 * ## The bug this ordering fixes
 *
 * The system prompt used to inject the daily-log memory fragment BEFORE
 * "Runtime context:", inside what the surrounding comment called the
 * byte-stable prefix. But the daily-log fragment changes on EVERY
 * memory_write (it contains today's log, which memory_write appends to).
 * Prefix-based prompt caches — OpenAI/DeepSeek head matching, Anthropic
 * cache_control breakpoints — went stale on the next turn after every
 * memory write, paying full ingestion for system + tools + history.
 *
 * Fix: keep only SLOW-CHANGING fragments (GLOBAL.md, MEMORY-ROLLUP.md) in
 * the standing region, and move the per-turn VOLATILE pieces (recent daily
 * logs + the rollup size hint, which reflects daily-log sizes) after
 * "Runtime context:". Within a day, everything above "Runtime context:"
 * is then byte-stable across memory_write.
 *
 * Mirrors the same root-cause fix upstream: OpenMinis-Linux
 * `MemoryBlockSplit` [GH#357] — "daily logs must live in the dynamic tail;
 * otherwise every memory_write rewrites bytes inside the cached prefix and
 * the whole prompt cache misses".
 *
 * Pure and total: no Android types, no I/O — pinned by
 * ChatPromptMemoryOrderTest.
 */
internal object MemoryPromptOrder {

    /** A named fragment with its injection text (already fully rendered). */
    data class Fragment(val key: Key, val text: String)

    /** Injection keys, in stable→volatile order. */
    enum class Key { SKILL, GLOBAL_MEMORY, ROLLUP, DAILY_MEMORY, ROLLUP_SIZE_HINT }

    /**
     * Assemble the fragment tail in cache-stable order.
     *
     * STANDING region (byte-stable within a day, survives memory_write):
     * skill → global memory → rollup.
     * VOLATILE tail (after "Runtime context:" in the real prompt):
     * daily memory → rollup size hint.
     *
     * The caller emits the standing fragments before its runtime context
     * block and the volatile ones after it; this function owns only the
     * relative ordering and the exact joiner between fragments.
     */
    fun orderInStandingRegion(all: List<Fragment>): List<Fragment> =
        all.filter { it.key in STANDING }.sortedBy { STANDING.indexOf(it.key) }

    fun orderInVolatileTail(all: List<Fragment>): List<Fragment> =
        all.filter { it.key in VOLATILE }.sortedBy { VOLATILE.indexOf(it.key) }

    /** Joins fragments the way buildSystemPrompt does: "\n\n" between blocks. */
    fun join(fragments: List<Fragment>): String =
        fragments.joinToString("\n\n") { it.text }

    /**
     * The standing set — anything here is allowed above "Runtime context:".
     * DAILY_MEMORY and ROLLUP_SIZE_HINT are deliberately absent: both change
     * on memory_write (the hint reflects daily-log byte sizes).
     */
    val STANDING: List<Key> = listOf(Key.SKILL, Key.GLOBAL_MEMORY, Key.ROLLUP)

    /** The volatile set — must be emitted AFTER "Runtime context:". */
    val VOLATILE: List<Key> = listOf(Key.DAILY_MEMORY, Key.ROLLUP_SIZE_HINT)
}
