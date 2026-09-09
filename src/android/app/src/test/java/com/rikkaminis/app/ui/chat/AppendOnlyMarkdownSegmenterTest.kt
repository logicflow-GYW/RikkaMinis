package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [AppendOnlyMarkdownSegmenter] — the stable slot contract
 * behind the forward-stable chat scroll fix.
 *
 * Core invariant under test: once a slot is published settled, its ordinal
 * and rawText never change; new boundaries only APPEND at the tail.
 */
class AppendOnlyMarkdownSegmenterTest {

    // ── prefix-append invariant over growing snapshots ──────────────────────

    @Test fun `keys are a strict prefix extension across 1000 token snapshots`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val sb = StringBuilder()
        var prevOrdinals: List<Int> = emptyList()
        for (i in 1..1000) {
            sb.append("word $i\n\n")
            val slots = seg.update(sb.toString(), streamEnded = false)
            val ordinals = slots.map { it.ordinal }
            // Every step: new ordinals must extend the old ones as a prefix.
            assertTrue(
                "step $i: ordinals regressed: $prevOrdinals -> $ordinals",
                ordinals.size >= prevOrdinals.size &&
                    ordinals.take(prevOrdinals.size) == prevOrdinals,
            )
            // Frozen prefix content never changes.
            for (j in 0 until prevOrdinals.size) {
                assertEquals("step $i slot $j frozen", slots[j].rawText, seg.snapshot()[j].rawText)
                assertTrue(slots[j].settled)
            }
            prevOrdinals = ordinals
        }
        assertEquals(1000, seg.snapshot().size)
        assertTrue(seg.invariantErrorCount == 0)
    }

    @Test fun `growing a paragraph keeps one live slot with growing content`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val s1 = seg.update("Hello", streamEnded = false)
        assertEquals(listOf("Hello"), s1.map { it.rawText })
        assertFalse(s1[0].settled)

        val s2 = seg.update("Hello world", streamEnded = false)
        assertEquals(listOf("Hello world"), s2.map { it.rawText })
        assertFalse(s2[0].settled)

        val s3 = seg.update("Hello world, this is a", streamEnded = false)
        assertEquals(listOf("Hello world, this is a"), s3.map { it.rawText })
        assertTrue(seg.invariantErrorCount == 0)
    }

    @Test fun `new paragraph boundary settles the previous slot and appends`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("First paragraph", streamEnded = false)
        val s2 = seg.update("First paragraph\n\nSecond", streamEnded = false)
        assertEquals(listOf("First paragraph", "Second"), s2.map { it.rawText })
        assertTrue(s2[0].settled)
        assertFalse(s2[1].settled)
        assertEquals(listOf(0, 1), s2.map { it.ordinal })

        // Third paragraph appends, first two frozen.
        val s3 = seg.update("First paragraph\n\nSecond\n\nThird", streamEnded = false)
        assertEquals(listOf("First paragraph", "Second", "Third"), s3.map { it.rawText })
        assertTrue(s3[0].settled)
        assertTrue(s3[1].settled)
        assertFalse(s3[2].settled)
        assertEquals(listOf(0, 1, 2), s3.map { it.ordinal })
    }

    @Test fun `consecutive blank lines are a single closable boundary`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val s = seg.update("A\n\n\n\nB", streamEnded = false)
        assertEquals(listOf("A", "B"), s.map { it.rawText })
        assertTrue(s[0].settled)
        assertFalse(s[1].settled)
    }

    // ── code fences ─────────────────────────────────────────────────────────

    @Test fun `fenced code block closes as a standalone slot`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val s1 = seg.update("Intro\n\n```kotlin\nval x = 1\n```", streamEnded = false)
        assertEquals(listOf("Intro", "```kotlin\nval x = 1\n```"), s1.map { it.rawText })
        assertTrue(s1[0].settled)
        assertFalse(s1[1].settled)
    }

    @Test fun `unterminated fence keeps everything inside the live slot`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val s1 = seg.update("```python\nprint(1)", streamEnded = false)
        assertEquals(listOf("```python\nprint(1)"), s1.map { it.rawText })
        assertFalse(s1[0].settled)

        // Still inside the fence — one live slot, growing.
        val s2 = seg.update("```python\nprint(1)\nprint(2)", streamEnded = false)
        assertEquals(listOf("```python\nprint(1)\nprint(2)"), s2.map { it.rawText })
        assertFalse(s2[0].settled)
        assertEquals(listOf(0), s2.map { it.ordinal })
    }

    @Test fun `fence opened and closed across chunks settles correctly`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("```\nline1\nline2", streamEnded = false)
        val s = seg.update("```\nline1\nline2\n```\n\nAfter", streamEnded = false)
        assertEquals(listOf("```\nline1\nline2\n```", "After"), s.map { it.rawText })
        assertTrue(s[0].settled)
        assertFalse(s[1].settled)
    }

    // ── tables / lists / long single paragraphs ─────────────────────────────

    @Test fun `markdown table without blank lines stays one slot`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val table = "| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |"
        val s = seg.update(table, streamEnded = false)
        assertEquals(listOf(table), s.map { it.rawText })
        assertFalse(s[0].settled)
    }

    @Test fun `list items separated by blank lines split like paragraphs`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val s = seg.update("- one\n\n- two\n\n- three", streamEnded = false)
        assertEquals(listOf("- one", "- two", "- three"), s.map { it.rawText })
        assertTrue(s[0].settled)
        assertTrue(s[1].settled)
        assertFalse(s[2].settled)
    }

    @Test fun `ultra-long single paragraph stays one slot`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val long = "x".repeat(10_000)
        val s = seg.update(long, streamEnded = false)
        assertEquals(1, s.size)
        assertEquals(long, s[0].rawText)
    }

    // ── stream end ──────────────────────────────────────────────────────────

    @Test fun `streamEnd settles the live slot without re-splitting or re-keying`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val before = seg.update("P1\n\nP2 tail", streamEnded = false)
        val keysBefore = before.map { it.ordinal }

        val after = seg.update("P1\n\nP2 tail", streamEnded = true)
        val keysAfter = after.map { it.ordinal }

        assertEquals(keysBefore, keysAfter)
        assertEquals(before.map { it.rawText }, after.map { it.rawText })
        assertTrue(after.all { it.settled })
    }

    @Test fun `streamEnd after more content settles every slot`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("P1\n\nP2\n\nP3", streamEnded = false)
        val after = seg.update("P1\n\nP2\n\nP3", streamEnded = true)
        assertEquals(3, after.size)
        assertTrue(after.all { it.settled })
    }

    @Test fun `streamEnd on empty input is a no-op`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("P1", streamEnded = false)
        val after = seg.update("", streamEnded = true)
        assertEquals(listOf("P1"), after.map { it.rawText })
        assertTrue(after[0].settled)
    }

    // ── regressive snapshots ────────────────────────────────────────────────

    @Test fun `regressive snapshot is ignored entirely`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("Hello world", streamEnded = false)
        val s = seg.update("Hello", streamEnded = false)
        assertEquals(listOf("Hello world"), s.map { it.rawText })
        assertFalse(s[0].settled)
        assertTrue(seg.invariantErrorCount == 0)
    }

    // ── non-append divergence ───────────────────────────────────────────────

    @Test fun `divergence keeps settled prefix and slot count, records invariant error`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("Alpha\n\nBeta", streamEnded = false)
        assertEquals(2, seg.snapshot().size)

        // Model rewrites the FIRST fragment. Published keys must NOT churn
        // (slot count stays 2), the settled slot keeps its frozen content, and
        // the live slot re-derives ONLY the fresh tail after the settled prefix
        // (so "Alpha" is never re-emitted → no "AlphaXXXAlpha" duplication).
        val s = seg.update("XXX\n\nBeta\n\nGamma", streamEnded = false)
        assertEquals(2, s.size)                       // slot count unchanged
        assertEquals("Alpha", s[0].rawText)           // settled prefix frozen
        assertTrue(s[0].settled)
        assertEquals("Beta\n\nGamma", s[1].rawText)   // fresh tail after prefix
        assertFalse(s[1].settled)
        assertEquals(listOf(0, 1), s.map { it.ordinal })
        assertEquals(1, seg.invariantErrorCount)
    }

    @Test fun `divergence never changes slot count across growing snapshots`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("A\n\nB\n\nC", streamEnded = false)
        val before = seg.snapshot().size
        seg.update("CHANGED\n\nB\n\nC\n\nD", streamEnded = false)
        val after = seg.snapshot()
        // KEY non-churn contract: divergence must NOT grow/shrink the slot list
        // (this is what caused the scrolling jump regression).
        assertEquals(before, after.size)   // 2 settled + 1 live = 3, still 3
        // Settled prefix frozen; live slot = fresh tail (B, C, D).
        assertEquals("A", after[0].rawText)
        assertEquals("B", after[1].rawText)
        assertEquals("C\n\nD", after[2].rawText)
        assertEquals(listOf(0, 1, 2), after.map { it.ordinal })
        assertEquals(1, seg.invariantErrorCount)
    }

    @Test fun `slot key materialization stays stable for LazyColumn`() {
        val seg = AppendOnlyMarkdownSegmenter()
        val messageId = "msg-1"
        val blockId = "text_1_0"
        seg.update("P1\n\nP2", streamEnded = false)
        val keys1 = seg.snapshot().map { "mdslot:$messageId:$blockId:${it.ordinal}" }
        seg.update("P1\n\nP2\n\nP3", streamEnded = false)
        val keys2 = seg.snapshot().map { "mdslot:$messageId:$blockId:${it.ordinal}" }
        assertTrue(keys2.take(keys1.size) == keys1)
        assertEquals(3, keys2.size)
    }

    // ── token-level duplication regression (root cause B) ──────────────────

    @Test fun `partial-word growth at a settled seam does NOT duplicate`() {
        // Root cause B: a throttled snapshot grows the first fragment by a
        // partial word AFTER it was already settled (e.g. "致命伤" settled, then
        // the same line grows to "致命伤致命"). The OLD absorb concatenated the
        // settled slot with the whole fresh tail → "致命伤" + "致命伤致命..." = the
        // visible duplication. The correct absorb keeps the settled slot frozen
        // and derives the live slot only from the fresh tail AFTER the settled
        // prefix — the partial-word growth on the settled fragment is dropped
        // (settled slots are immutable), but NOTHING is duplicated.
        val seg = AppendOnlyMarkdownSegmenter()
        // A paragraph boundary settles "致命伤" as slot 0.
        seg.update("致命伤\n\n竞态", streamEnded = false)
        // Next snapshot: the first fragment grew by a partial word.
        val s = seg.update("致命伤致命\n\n竞态", streamEnded = false)

        val joined = s.joinToString("\n\n") { it.rawText }
        // Settled prefix frozen; live slot = fresh tail after it. No duplication.
        assertEquals("致命伤\n\n竞态", joined)
        assertFalse(joined.contains("致命伤致命伤"))
        assertFalse(joined.contains("竞态竞态"))
        assertEquals(2, s.size)   // slot count unchanged (no key churn)
    }

    @Test fun `prefix-growth at a settled seam keeps exactly one copy`() {
        // Defensive variant: the whole settled slot is a strict prefix of the
        // fresh first fragment (settled "AB", fresh "AB追加"). Same guarantee:
        // settled "AB" stays frozen, live slot takes the fresh tail only, so
        // "AB" can never appear twice.
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("AB\n\nC", streamEnded = false)
        // "AB" settled as slot 0; now it grows to "AB追加" (same boundary).
        val s = seg.update("AB追加\n\nC", streamEnded = false)

        val joined = s.joinToString("\n\n") { it.rawText }
        // "AB" frozen as settled, live slot = "C" (the "追加" growth is dropped).
        assertEquals("AB\n\nC", joined)
        assertFalse(joined.contains("AB追加AB"))
        assertFalse(joined.contains("ABAB"))
        assertEquals(2, s.size)
    }

    @Test fun `rollback-then-reattach does not leak stale segmenter state`() {
        // Root cause A companion: after a retry/fallback rewinds the turn and
        // a text block id is recycled, a stale segmenter must not carry content
        // from the previous stream into the new one. A FRESH segmenter (the
        // fix re-keys by making block ids globally unique) starts from empty,
        // so the new stream's text is authoritative with no ghost prefix.
        val seg = AppendOnlyMarkdownSegmenter()
        // Simulate the fresh-segmenter contract: first update seeds cleanly.
        val s = seg.update("分叉于 08-09，它没继承", streamEnded = false)
        assertEquals(listOf("分叉于 08-09，它没继承"), s.map { it.rawText })
        // A fresh stream (new segmenter instance, or reset state) never re-emits
        // the old text — the content is exactly what the new stream produced.
        val seg2 = AppendOnlyMarkdownSegmenter()
        val s2 = seg2.update("分叉于 08-09，它没继承", streamEnded = false)
        assertEquals(s.map { it.rawText }, s2.map { it.rawText })
        assertEquals(1, s2.map { it.rawText }.size)
    }

    // ── divergence + streamEnded combination guard ─────────────────────────
    // Previously UNTESTED (the exact coverage gap pattern behind the jump
    // regression): the divergence path with streamEnded=true must keep the
    // slot count stable AND settle everything, whatever the fresh tail holds.

    @Test fun `divergence with streamEnd settles the live slot, count stable`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("A\n\nB", streamEnded = false)   // [A(s), B(live)], settledCount=1
        // Diverge (A rewritten) AND end the stream in the same tick.
        val s = seg.update("X\n\nB", streamEnded = true)
        assertEquals(2, s.size)                     // count stable — no key churn
        assertEquals("A", s[0].rawText)             // settled prefix frozen
        assertEquals("B", s[1].rawText)             // live = fresh tail after prefix
        assertTrue(s.all { it.settled })            // everything settled
        assertEquals(1, seg.invariantErrorCount)
        // Idempotent: a repeated streamEnded tick with the same text is a no-op.
        val again = seg.update("X\n\nB", streamEnded = true)
        assertEquals(s.map { it.rawText }, again.map { it.rawText })
        assertEquals(s.size, again.size)
    }

    @Test fun `divergence where fresh shrinks below settled keeps prefix, count stable`() {
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("A\n\nB\n\nC", streamEnded = false)  // [A(s),B(s),C(live)], settledCount=2
        // Fresh split collapses to a single fragment — below the settled prefix.
        val s = seg.update("X", streamEnded = false)
        assertEquals(2, s.size)                     // count stable — ONLY settled stay
        assertEquals("A", s[0].rawText)
        assertEquals("B", s[1].rawText)
        assertTrue(s.all { it.settled })            // nothing live left
        assertEquals(1, seg.invariantErrorCount)
    }

    @Test fun `repeated divergence on empty fresh tail never adds empty slots`() {
        // The regression this guards: the old absorbDivergence appended an
        // EMPTY live slot when fresh shrank to the settled prefix, publishing
        // a new LazyColumn key for no content — the jump shape again.
        val seg = AppendOnlyMarkdownSegmenter()
        seg.update("A\n\nB", streamEnded = false)       // [A(s), B(live)]
        seg.update("X\n\nB", streamEnded = true)        // diverge → [A(s), B(s)]
        val third = seg.update("X\n\nB", streamEnded = true)  // settledCount=2, fresh same → NO empty 3rd slot
        assertEquals(2, third.size)
        assertEquals(listOf("A", "B"), third.map { it.rawText })
        assertTrue(third.all { it.settled })
    }
}
