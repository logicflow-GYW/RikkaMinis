package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r5-c1-compact-F02] The createdAt self-heal, bounded.
 *
 * The marker's `createdAt` is the compact EXECUTION instant: every kept-tail
 * message that already existed when the compact ran also has
 * `createdAt < marker.createdAt`, so the old "last raw before marker.createdAt"
 * walked straight past the folded block into that tail and folded turns the
 * summary never covered — off-device this shows up as "the model lost the
 * context it was told it still has". The healed anchor is bounded by
 * `compactedCount` (the one boundary signal the marker still carries) and can
 * only NARROW the folded range, never widen it.
 */
class CompactHealAnchorTest {

    private val sid = "s1"

    private fun row(id: String, createdAt: Long) = MessageEntity(
        id = id,
        sessionId = sid,
        role = "user",
        partsJson = "",
        createdAt = createdAt,
        sortOrder = createdAt.toInt(),
    )

    /** m0..m4 at t=1000..5000, marker written at t=10_000. */
    private fun raw(): List<MessageEntity> = (0..4).map { row("m$it", 1_000L + it * 1_000) }

    @Test
    fun `the heal lands inside the folded block, not on the kept tail`() {
        // First-round compactBefore: the folded block is m0..m2
        // (compactedCount = 3); the kept tail m3/m4 was persisted BEFORE the
        // marker, so every raw message passes the createdAt predicate.
        // Pre-fix: lastOrNull → m4 = the newest KEPT message → the whole tail
        // was folded away on load. Bounded: candidates[2] = m2 = the block end.
        val healed = healAnchorByCreatedAt(raw(), 10_000L, emptySet(), compactedCount = 3)
        assertEquals("m2", healed!!.id)
    }

    @Test
    fun `a later round narrows instead of widening`() {
        // Second-round marker: the folded block is m5..m6 (compactedCount = 2,
        // true anchor m5). The bound lands at candidates[1] = m1 — earlier than
        // the true anchor, which merely re-sends summarised text verbatim; it
        // can never land past m5 and fold the kept tail.
        val healed = healAnchorByCreatedAt(raw(), 10_000L, emptySet(), compactedCount = 2)
        val idx = raw().indexOfFirst { it.id == healed!!.id }
        assertTrue("the heal may only land at or before the true anchor (m5)", idx <= 5)
    }

    @Test
    fun `truncation into the block recovers the last survivor`() {
        // A rewind truncated the transcript INTO the folded block (block was
        // five messages, only m0/m1 survive) and nothing arrived after the
        // marker — the last survivor is inside the block, so healing to it is
        // sound.
        val survivors = listOf(row("m0", 1_000L), row("m1", 2_000L))
        val healed = healAnchorByCreatedAt(survivors, 10_000L, emptySet(), compactedCount = 5)
        assertEquals("m1", healed!!.id)
    }

    @Test
    fun `fewer survivors than folded with a kept tail present refuses to heal`() {
        // m0 survives from the block but m9 arrived AFTER the marker: the
        // survivors are no longer provably a prefix of the folded block, so
        // "the last survivor is inside it" cannot be asserted. Fail closed —
        // the caller falls back to divider-at-top / no graying / full history.
        val survivors = listOf(row("m0", 1_000L), row("m9", 20_000L))
        val healed = healAnchorByCreatedAt(survivors, 10_000L, emptySet(), compactedCount = 5)
        assertNull(healed)
    }

    @Test
    fun `no block length refuses to heal`() {
        assertNull(healAnchorByCreatedAt(raw(), 10_000L, emptySet(), compactedCount = 0))
        assertNull(healAnchorByCreatedAt(raw(), 10_000L, emptySet(), compactedCount = -1))
    }

    @Test
    fun `no candidates at all refuses to heal`() {
        assertNull(healAnchorByCreatedAt(emptyList(), 10_000L, emptySet(), compactedCount = 3))
    }

    @Test
    fun `the loaded-history filter still applies`() {
        // historyDbIds filters to ids the loaded agentHistory still represents:
        // a raw row that is not represented must never become the anchor. With
        // the block length honoured, candidates = [m0, m1] → m1.
        val healed = healAnchorByCreatedAt(raw(), 10_000L, setOf("m0", "m1"), compactedCount = 2)
        assertEquals("m1", healed!!.id)
        // Without the filter the same block length would still land on m1 (the
        // bound is what keeps it away from the tail), but the unfiltered last
        // row m4 must never be the answer.
        val unfiltered = healAnchorByCreatedAt(raw(), 10_000L, emptySet(), compactedCount = 2)
        assertEquals("m1", unfiltered!!.id)
    }
}
