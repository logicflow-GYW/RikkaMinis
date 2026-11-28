package com.rikkaminis.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

/** Pure decisions used by the live streaming markdown renderer. */
class StreamingMarkdownTextTest {

    // ── StreamingBlockLedger (dv21-B4-B05) ──────────────────────────────────
    //
    // The streaming body's block state used to be a bare `List<MdBlock>` in an
    // unkeyed `remember`, which produced two field-visible defects:
    //
    //  (a) a body REUSED for another message drew the outgoing message's blocks
    //      for one frame and then re-parsed a list the cache already had
    //      (recorded as FROZEN_MISS), and
    //  (b) the frame where a stream converged never deposited its result
    //      (`displayContent` had already reached its final value, so the
    //      content-keyed effect did not re-run, and the coroutine still alive
    //      kept the `isStreaming = true` it captured) — long streamed answers
    //      stayed out of blocksLru and re-parsed on every scroll-back.
    //
    // The ledger's four decisions are pure, so they are pinned here. The
    // Compose key wiring around them cannot run in a JVM unit test and is
    // pinned by chat_ui_interaction_check (family H) instead.
    //
    // Expected values are dead literals (list identity, booleans), never
    // derived from the ledger's own constants.

    private fun para(raw: String): MdBlock = MdBlock.Paragraph(raw)

    @Test fun `a live stream is never seeded from the cache`() {
        val cached = listOf(para("cached"))
        // Mid-stream: no seed, even though the peek would answer.
        assertNull(
            StreamingBlockLedger.warmSeed("growing", isStreaming = true) { cached },
        )
        // Control arm: the same peek DOES answer once the stream converged —
        // without this the assertion above would pass for a ledger that never
        // seeded at all.
        assertSame(
            cached,
            StreamingBlockLedger.warmSeed("growing", isStreaming = false) { cached },
        )
    }

    @Test fun `a seed claims its source, a missing seed claims nothing`() {
        val cached = listOf(para("cached"))
        val seeded = StreamingBlockLedger.seed(cached, "text")
        assertSame("the seed list itself is handed to the renderer", cached, seeded.blocks)
        assertEquals("text", seeded.source)

        val empty = StreamingBlockLedger.seed(null, "text")
        assertTrue(empty.blocks.isEmpty())
        assertNull("an empty initial list is not a parse of 'text'", empty.source)
        assertFalse(
            "…so the convergence frame must not claim it as one",
            StreamingBlockLedger.isConvergedWith(empty, "text", isStreaming = false),
        )
    }

    @Test fun `a reused body draws the seed of the new content, not the outgoing list`() {
        val outgoing = listOf(para("old message"))
        val incoming = listOf(para("new message"))
        val published = StreamingBlockLedger.publish(outgoing, "old message")

        assertSame(
            "the cached blocks of the new content win the very frame they appear",
            incoming,
            StreamingBlockLedger.rendered(published, incoming),
        )
        assertSame(
            "mid-stream (no seed) the previous list is kept — never blanked",
            outgoing,
            StreamingBlockLedger.rendered(published, null),
        )
        assertSame(
            "adopting an already-adopted seed is idempotent",
            incoming,
            StreamingBlockLedger.rendered(StreamingBlockLedger.seed(incoming, "new message"), incoming),
        )
    }

    @Test fun `the convergence frame is a deposit, not a re-parse`() {
        val final = listOf(para("final"))
        val published = StreamingBlockLedger.publish(final, "final text")
        assertTrue(
            StreamingBlockLedger.isConvergedWith(published, "final text", isStreaming = false),
        )
    }

    @Test fun `a converging stream that is still live is not a convergence frame`() {
        val prefix = listOf(para("prefix"))
        val published = StreamingBlockLedger.publish(prefix, "prefix")
        // Still streaming: the same content must keep re-parsing (it is growing).
        assertFalse(
            StreamingBlockLedger.isConvergedWith(published, "prefix", isStreaming = true),
        )
    }

    @Test fun `a list left over from an earlier chunk is never deposited as the final one`() {
        // The in-flight parse for the final text was cancelled when the content
        // last changed, so the list in hand is still the previous chunk's.
        // Depositing it under the final content's key would render a truncated
        // message on every later scroll-back — worse than a cold cache.
        val prefix = listOf(para("chunk one"))
        val published = StreamingBlockLedger.publish(prefix, "chunk one")
        assertFalse(
            StreamingBlockLedger.isConvergedWith(published, "chunk one chunk two", isStreaming = false),
        )
    }

    @Test fun `publishing replaces the source the convergence verdict reads`() {
        val a = StreamingBlockLedger.publish(listOf(para("a")), "a")
        val b = StreamingBlockLedger.publish(listOf(para("b")), "b")
        assertFalse(StreamingBlockLedger.isConvergedWith(a, "b", isStreaming = false))
        assertTrue(StreamingBlockLedger.isConvergedWith(b, "b", isStreaming = false))
        assertTrue(StreamingBlockLedger.isConvergedWith(a, "a", isStreaming = false))
    }

}
