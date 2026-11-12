package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/memcache-prefix-split] Pins the stable→volatile ordering contract of
 * [MemoryPromptOrder].
 *
 * The bug: the daily-log memory fragment sat inside the byte-stable prefix
 * of the system prompt; every memory_write changed those bytes and defeated
 * prefix-based prompt caching (OpenAI/DeepSeek head matching, Anthropic
 * cache_control breakpoints) for the whole next turn.
 *
 * Expectations are LITERAL (no derivation from the constants under test) —
 * see evidence-discipline "装置自指陷阱".
 */
class ChatPromptMemoryOrderTest {

    private fun frag(key: MemoryPromptOrder.Key, text: String) =
        MemoryPromptOrder.Fragment(key, text)

    private val all = listOf(
        frag(MemoryPromptOrder.Key.SKILL, "SKILL-BODY"),
        frag(MemoryPromptOrder.Key.GLOBAL_MEMORY, "GLOBAL-BODY"),
        frag(MemoryPromptOrder.Key.DAILY_MEMORY, "DAILY-BODY"),
        frag(MemoryPromptOrder.Key.ROLLUP, "ROLLUP-BODY"),
        frag(MemoryPromptOrder.Key.ROLLUP_SIZE_HINT, "HINT-BODY"),
    )

    // ── 1. Standing region membership: volatile keys must NOT appear ──

    @Test
    fun standingRegion_excludesDailyMemory() {
        val keys = MemoryPromptOrder.orderInStandingRegion(all).map { it.key }
        assertTrue(
            "DAILY_MEMORY leaked into the standing region — this is exactly the cache-prefix bug",
            MemoryPromptOrder.Key.DAILY_MEMORY !in keys,
        )
    }

    @Test
    fun standingRegion_excludesRollupSizeHint() {
        val keys = MemoryPromptOrder.orderInStandingRegion(all).map { it.key }
        assertTrue(
            "ROLLUP_SIZE_HINT leaked into the standing region",
            MemoryPromptOrder.Key.ROLLUP_SIZE_HINT !in keys,
        )
    }

    // ── 2. Volatile tail membership: standing keys must NOT appear ──

    @Test
    fun volatileTail_excludesStandingKeys() {
        val keys = MemoryPromptOrder.orderInVolatileTail(all).map { it.key }
        for (k in listOf(
            MemoryPromptOrder.Key.SKILL,
            MemoryPromptOrder.Key.GLOBAL_MEMORY,
            MemoryPromptOrder.Key.ROLLUP,
        )) {
            assertTrue("$k leaked into the volatile tail", k !in keys)
        }
    }

    // ── 3. Exact ordering (literals, not derived from the enum order) ──

    @Test
    fun standingRegion_orderIsSkillThenGlobalThenRollup() {
        val keys = MemoryPromptOrder.orderInStandingRegion(all).map { it.key }
        assertEquals(
            listOf(
                MemoryPromptOrder.Key.SKILL,
                MemoryPromptOrder.Key.GLOBAL_MEMORY,
                MemoryPromptOrder.Key.ROLLUP,
            ),
            keys,
        )
    }

    @Test
    fun volatileTail_orderIsDailyThenHint() {
        val keys = MemoryPromptOrder.orderInVolatileTail(all).map { it.key }
        assertEquals(
            listOf(
                MemoryPromptOrder.Key.DAILY_MEMORY,
                MemoryPromptOrder.Key.ROLLUP_SIZE_HINT,
            ),
            keys,
        )
    }

    // ── 4. THE contract: memory_write must not move standing bytes ──

    @Test
    fun memoryWrite_keepsStandingPrefixByteStable() {
        // Turn N: daily fragment v1. Turn N+1: memory_write appended to the
        // daily log → daily fragment v2 (new bytes). Everything above
        // "Runtime context:" must be byte-identical across the two turns.
        val turnN = MemoryPromptOrder.join(
            MemoryPromptOrder.orderInStandingRegion(all),
        )
        val turnNPlus1 = MemoryPromptOrder.join(
            MemoryPromptOrder.orderInStandingRegion(
                all.map {
                    if (it.key == MemoryPromptOrder.Key.DAILY_MEMORY) {
                        it.copy(text = it.text + "\n<!-- 2026-11-12 12:00:00 -->\n## new memory entry")
                    } else {
                        it
                    }
                },
            ),
        )
        assertEquals(turnN, turnNPlus1)
    }

    @Test
    fun memoryWrite_volatileTailDoesChange() {
        // Negative control for the test above: the volatile tail DOES carry
        // the new memory bytes (if it didn't, the ordering would be pointless).
        val before = MemoryPromptOrder.join(
            MemoryPromptOrder.orderInVolatileTail(all),
        )
        val after = MemoryPromptOrder.join(
            MemoryPromptOrder.orderInVolatileTail(
                all.map {
                    if (it.key == MemoryPromptOrder.Key.DAILY_MEMORY) {
                        it.copy(text = it.text + "\n## new memory entry")
                    } else {
                        it
                    }
                },
            ),
        )
        assertTrue(before != after)
        assertTrue(after.contains("## new memory entry"))
    }

    // ── 5. join() reproduces the buildSystemPrompt joiner exactly ──

    @Test
    fun join_usesDoubleNewlineBetweenFragments() {
        val out = MemoryPromptOrder.join(
            listOf(
                frag(MemoryPromptOrder.Key.GLOBAL_MEMORY, "A"),
                frag(MemoryPromptOrder.Key.ROLLUP, "B"),
            ),
        )
        assertEquals("A\n\nB", out)
    }

    @Test
    fun join_singleFragment_noJoiner() {
        val out = MemoryPromptOrder.join(listOf(frag(MemoryPromptOrder.Key.SKILL, "A")))
        assertEquals("A", out)
    }

    @Test
    fun join_emptyList_emptyString() {
        assertEquals("", MemoryPromptOrder.join(emptyList()))
    }

    // ── 6. Input order independence (fragments may be built in any order) ──

    @Test
    fun ordering_isInputOrderIndependent() {
        val shuffled = listOf(
            frag(MemoryPromptOrder.Key.DAILY_MEMORY, "D"),
            frag(MemoryPromptOrder.Key.ROLLUP, "R"),
            frag(MemoryPromptOrder.Key.SKILL, "S"),
            frag(MemoryPromptOrder.Key.GLOBAL_MEMORY, "G"),
        )
        assertEquals(
            "S\n\nG\n\nR",
            MemoryPromptOrder.join(MemoryPromptOrder.orderInStandingRegion(shuffled)),
        )
        assertEquals(
            "D",
            MemoryPromptOrder.join(MemoryPromptOrder.orderInVolatileTail(shuffled)),
        )
    }

    // ── 7. Missing fragments (memory off / no rollup yet) degrade cleanly ──

    @Test
    fun missingFragments_standingskipsThem() {
        val onlyDaily = listOf(frag(MemoryPromptOrder.Key.DAILY_MEMORY, "D"))
        assertEquals("", MemoryPromptOrder.join(MemoryPromptOrder.orderInStandingRegion(onlyDaily)))
        assertEquals("D", MemoryPromptOrder.join(MemoryPromptOrder.orderInVolatileTail(onlyDaily)))
    }

    // ── 8. Sub-agent context assembly ──────────────────────────────────────
    // Regression: the sub-agent call site originally routed EVERYTHING through
    // orderInStandingRegion, whose membership filter silently dropped
    // DAILY_MEMORY (the sub-agent has no "Runtime context:" anchor to emit it
    // after). Probe-reproduced on 2026-11-12; these three tests pin the fix.
    // Fixture note: the sub-agent call site adds only SKILL/GLOBAL/ROLLUP/
    // DAILY — never ROLLUP_SIZE_HINT — so use a production-shaped set here.

    private val subagentAll = all.filter { it.key != MemoryPromptOrder.Key.ROLLUP_SIZE_HINT }

    @Test
    fun subagentContext_includesDailyMemory() {
        val keys = MemoryPromptOrder.orderForSubagentContext(subagentAll).map { it.key }
        assertTrue(
            "DAILY_MEMORY dropped from sub-agent context — the sub-agent just lost its recent memory",
            MemoryPromptOrder.Key.DAILY_MEMORY in keys,
        )
    }

    @Test
    fun subagentContext_dailyRidesLast() {
        val keys = MemoryPromptOrder.orderForSubagentContext(subagentAll).map { it.key }
        assertEquals(
            listOf(
                MemoryPromptOrder.Key.SKILL,
                MemoryPromptOrder.Key.GLOBAL_MEMORY,
                MemoryPromptOrder.Key.ROLLUP,
                MemoryPromptOrder.Key.DAILY_MEMORY,
            ),
            keys,
        )
    }

    @Test
    fun subagentContext_memoryWrite_keepsHeadByteStable() {
        // The sub-agent's cache invariant: its prompt head (skill + global +
        // rollup + separator) stays byte-stable across a memory_write; only
        // the trailing daily fragment carries new bytes. Both full contexts
        // must therefore start with the same literal head, while differing
        // overall (negative control).
        val head = "SKILL-BODY\n\nGLOBAL-BODY\n\nROLLUP-BODY\n\n"
        val before = MemoryPromptOrder.join(
            MemoryPromptOrder.orderForSubagentContext(subagentAll),
        )
        val after = MemoryPromptOrder.join(
            MemoryPromptOrder.orderForSubagentContext(
                subagentAll.map {
                    if (it.key == MemoryPromptOrder.Key.DAILY_MEMORY) {
                        it.copy(text = it.text + "\n<!-- 2026-11-12 12:00:00 -->\n## new memory entry")
                    } else {
                        it
                    }
                },
            ),
        )
        assertTrue("head bytes moved across a memory_write", before.startsWith(head))
        assertTrue("head bytes moved across a memory_write", after.startsWith(head))
        assertTrue("volatile tail did not change (ordering would be pointless)", before != after)
    }
}
