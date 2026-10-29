package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r5-c1-compact-C01] The pin exists for ONE reason (see CompactPin.kt's
 * header: a ground-truth constraint stated once in round 1 is recalled 0/10 in
 * round 2 because later same-topic turns overwrite it). r3-b11 capped the block
 * to stop unbounded growth and — by trimming strictly newest-first — threw away
 * exactly that entry on every over-cap fold, i.e. the cap silently un-did the
 * feature.
 *
 * [extractPinnedUserMessages] kept its half of the promise ("recent N + the
 * earliest one, unconditionally"); [fitPinnedPayload] ate the other half. These
 * tests pin the two-exemption allocation: newest + earliest survive, the middle
 * is the growth that gets dropped.
 */
class CompactPinEarliestPinTest {

    private fun ascii(n: Int) = "a".repeat(n)

    @Test
    fun `a single over cap fold still pins the first user message`() {
        // Reported shape, end to end through the production wiring:
        //  early = 100 tokens, m1 = m2 = 4_000 tokens → total 8_100 > 8_000.
        //  extract keeps m2 + m1 (8_000) and re-adds the earliest (8_100);
        //  fit must keep {earliest, newest} and drop the middle m1.
        // Pre-fix: the newest-first walk kept m2 + m1 (8_000), saw 8_100 > cap
        // and dropped the earliest — the only entry the pin exists for.
        val early = "TASK:" + ascii(364) // 92 tokens
        val m1 = "m1-" + ascii(16_000) // 4_000 tokens
        val m2 = "m2-" + ascii(16_000) // 4_000 tokens
        val out = appendPinnedSection(
            "S",
            extractPinnedUserMessages(
                listOf(
                    LLMMessage(role = LLMMessage.Role.USER, content = early),
                    LLMMessage(role = LLMMessage.Role.USER, content = m1),
                    LLMMessage(role = LLMMessage.Role.USER, content = m2),
                ),
            ),
        )
        val inner = pinnedSectionInner(out)!!
        assertTrue("the earliest user message must survive the fold: $inner", inner.contains(early))
        assertTrue("the newest user message must survive the fold", inner.contains(m2))
        assertFalse("the middle entry is the growth that gets dropped", inner.contains(m1))
        // The ceiling is unchanged: budget + header line.
        assertTrue(ContextCompactor.estimateTokens(inner) <= 8_100L)
    }

    @Test
    fun `the original task statement survives eight rounds of folding`() {
        // Round 1 folds the statement in; rounds 2..8 keep adding ~4_000 tokens
        // of fresh text each. Pre-fix the statement died on the FIRST over-cap
        // round (round 3), so a long session never saw its own ground truth.
        val statement = "TASK: keep the plot consistent" + ascii(300)
        // Round 1's result must be stripped to the block inner too — handing the
        // full "S1\n\n<block>" back as carriedInner would make the previous
        // SUMMARY a carried entry (and the oldest exemption would then keep the
        // summary instead of the statement).
        var carried: String? = pinnedSectionInner(appendPinnedSection("S1", listOf(statement)))
        for (round in 2..8) {
            carried = pinnedSectionInner(
                appendPinnedSection("S$round", listOf("r$round-" + ascii(16_000)), carried),
            )
        }
        val inner = carried!!
        assertTrue("the original task statement must survive every round: $inner", inner.contains(statement))
        assertTrue("the newest round must survive", inner.contains("r8-"))
        assertTrue(ContextCompactor.estimateTokens(inner) <= 8_100L)
    }

    @Test
    fun `the allocation never exceeds the hard ceiling by more than one oversized newest`() {
        // Table of budgets × shapes: every result either fits the budget or is
        // exactly "one oversized newest entry" (the pre-existing exemption).
        val cases = listOf(
            listOf(100L to "a", 4000L to "b", 4000L to "c"),
            listOf(4000L to "x", 4000L to "y"),
            listOf(3900L to "p", 200L to "q", 3900L to "r"),
            listOf(8000L to "one"),
            listOf(9000L to "big", 50L to "small"),
        )
        for (row in cases) {
            val entries = row.map { (tokens, tag) -> tag + ascii((tokens * 4).toInt() - tag.length) }
            val costs = entries.map { ContextCompactor.estimateTokens(it) }
            val fit = fitPinnedPayload(entries.first(), entries.drop(1))
            val kept = listOfNotNull(fit.carriedInner) + fit.pinned
            if (kept.isEmpty()) continue
            val used = kept.sumOf { ContextCompactor.estimateTokens(it) }
            val ceiling = maxOf(8_000L, costs.last())
            assertTrue(
                "costs=$costs kept=$kept used=$used ceiling=$ceiling",
                used <= ceiling,
            )
        }
    }

    @Test
    fun `a block only summary has no body`() {
        // [fix/r5-c1-compact-F01] The guard that decides "nothing was summarised
        // so nothing may be folded" has to look THROUGH the appended block.
        val blockOnly = appendPinnedSection("", listOf("user said X"))
        assertTrue("the block was appended", pinnedSectionInner(blockOnly) != null)
        assertFalse("a block-only summary carries no body", summaryHasBody(blockOnly))
        assertFalse(summaryHasBody(""))
        assertFalse(summaryHasBody("   "))
        assertFalse(summaryHasBody(null))
        assertTrue(summaryHasBody("real summary text"))
        assertTrue("body + block is a real summary", summaryHasBody("real body\n\n$blockOnly"))
        assertEquals(
            "the untouched case must stay byte-identical",
            "real summary text",
            stripPinnedSection("real summary text"),
        )
    }
}
