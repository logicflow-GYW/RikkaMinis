package com.rikkaminis.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1029 R3 B11] F-03 — the pinned block is budgeted as a WHOLE.
 *
 * The budget used to live only on the fresh side ([extractPinnedUserMessages])
 * while [appendPinnedSection] glued `carriedInner + pinned` with no cap, so
 * every compact round grew the block by up to another full budget and the
 * block (which rides every request inside `<context-summary>`) marched the
 * session into EXHAUSTED. [fitPinnedPayload] now fits the merged payload.
 *
 * Expectations are literals, never derived from production constants
 * (evidence-discipline: a derived expectation mutates into PASS).
 */
class CompactPinBudgetTest {

    /** 4 ASCII chars = 1 token under [ContextCompactor.estimateTokens]. */
    private fun ascii(chars: Int) = "A".repeat(chars)

    private val headerPrefix = "Verbatim user messages from the earlier (compacted) conversation"

    // ── under budget: today's behaviour, byte-identical ────────────────

    @Test
    fun `under budget payload passes through unchanged`() {
        val carried = "old-one\n\nold-two"
        val fit = fitPinnedPayload(carried, listOf("new-one"), tokenLimit = 8_000L)
        assertEquals(carried, fit.carriedInner)
        assertEquals(listOf("new-one"), fit.pinned)
    }

    @Test
    fun `empty pin stays empty`() {
        val fit = fitPinnedPayload(null, emptyList(), tokenLimit = 8_000L)
        assertNull(fit.carriedInner)
        assertTrue(fit.pinned.isEmpty())
    }

    @Test
    fun `non positive limit pins nothing`() {
        val fit = fitPinnedPayload("old", listOf("new"), tokenLimit = 0L)
        assertNull(fit.carriedInner)
        assertTrue(fit.pinned.isEmpty())
    }

    // ── over budget: newest wins, oldest carried goes ─────────────────

    @Test
    fun `over budget keeps the newest entries and drops the oldest carried ones`() {
        val c1 = ascii(400) // 100 tokens
        val c2 = "B".repeat(400) // 100 tokens
        val n1 = "C".repeat(400) // 100 tokens
        val n2 = "D".repeat(400) // 100 tokens
        // 400 tokens offered into a 350 budget: n2 + n1 + c2 fit (300), c1
        // would make 400 — it is the oldest pin, so it is the one that goes.
        val fit = fitPinnedPayload("$c1\n\n$c2", listOf(n1, n2), tokenLimit = 350L)
        assertEquals(c2, fit.carriedInner)
        assertEquals(listOf(n1, n2), fit.pinned)
    }

    @Test
    fun `header survives a partial carried trim`() {
        val c1 = ascii(400) // 100 tokens
        val c2 = "B".repeat(400) // 100 tokens
        // Production wiring (CompactOrchestration:602-604 + :653): the carried
        // payload travels as the explicit third argument, read back out of the
        // PREVIOUS round's summary; the first argument is the fresh LLM output.
        val carried1 = pinnedSectionInner(appendPinnedSection("S1", listOf(c1)))!!
        val carried2 = pinnedSectionInner(appendPinnedSection("S2", listOf(c2), carried1))!!
        assertTrue(carried2.startsWith(headerPrefix))
        assertTrue("both rounds must be pinned while under budget", carried2.contains(c1) && carried2.contains(c2))
        val fresh = "E".repeat(4_000) // 1_000 tokens
        // 1_200 offered into 1_150: fresh + c2 fit (1_100), c1 would make 1_200.
        val fit = fitPinnedPayload(carried2, listOf(fresh), tokenLimit = 1_150L)
        val newCarried = fit.carriedInner!!
        assertTrue(
            "the block lost its header — the model no longer sees where the text came from: $newCarried",
            newCarried.startsWith(headerPrefix),
        )
        assertTrue("the newest carried entry must survive", newCarried.endsWith(c2))
        assertFalse("the oldest carried entry must be the one dropped", newCarried.contains(c1))
        assertEquals(listOf(fresh), fit.pinned)
    }

    @Test
    fun `a newest entry larger than the whole budget is still pinned alone`() {
        val huge = ascii(80_000) // 20_000 tokens
        val fit = fitPinnedPayload("old context", listOf(huge), tokenLimit = 100L)
        // The single-oversized exemption is kept (dropping the user's actual
        // last paste would defeat the pin) but it must CONSUME the budget:
        // nothing older rides along.
        assertNull(fit.carriedInner)
        assertEquals(listOf(huge), fit.pinned)
    }

    @Test
    fun `a fully dropped carried payload gets the header back`() {
        // carried = header + an 8_000-token entry; fresh = 4_000 tokens. The
        // newest entry consumes the whole budget, so the carried payload is
        // dropped outright — and the block must not lose its header line.
        val carried = pinnedSectionInner(
            appendPinnedSection("S1", listOf(ascii(32_000))),
        )!!
        val out = appendPinnedSection("rewritten", listOf("B".repeat(16_000)), carried)
        val inner = pinnedSectionInner(out)!!
        assertTrue(
            "the block lost its header when the carried payload was dropped: $inner",
            inner.startsWith(headerPrefix),
        )
        assertFalse("the dropped carried entry is still there", inner.contains(ascii(32_000)))
        assertTrue("the fresh entry was dropped too", inner.contains("B".repeat(16_000)))
    }

    // ── the defect itself: repeated rounds ────────────────────────────

    @Test
    fun `eight rounds of large pastes cannot grow the block past the budget`() {
        // Production wiring, same as CompactPinTest.rewriteRound: each round's
        // LLM output is fresh text, the pin payload travels through
        // pinnedSectionInner → appendPinnedSection's carriedInner.
        var carried: String? = null
        for (round in 1..8) {
            val out = appendPinnedSection("REWRITTEN#$round", listOf("r$round-" + ascii(16_000)), carried)
            carried = pinnedSectionInner(out)
        }
        val inner = carried!!
        val tokens = ContextCompactor.estimateTokens(inner)
        // Each entry is 4_000 tokens; uncapped, eight rounds accumulated
        // ~32_000 tokens. The ceiling is the budget plus the header line.
        assertTrue("pinned block grew to $tokens tokens", tokens <= 8_100L)
        assertTrue("the newest round must survive", inner.contains("r8-"))
        assertTrue("the second newest round must survive", inner.contains("r7-"))
        assertFalse("the third newest round must be gone", inner.contains("r6-"))
    }
}
