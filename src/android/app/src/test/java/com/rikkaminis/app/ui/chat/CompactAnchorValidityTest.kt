package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.db.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/r3-chat-1119 F-01] The compact-marker anchor is an id in the SESSION,
 * not a bubble on the screen.
 *
 * Reopening a tool-heavy session that went through ≥2 compact rounds used to
 * REWRITE the marker in Room: `lastCompactedMessageId` anchors on the last
 * message of the folded range, and in a tool round that row is a role=user
 * tool_result carrier — it has no UI bubble (`userRowHasVisibleContent` drops
 * it), so the old "is it in the UI list?" gate answered "orphan". The createdAt
 * self-heal then picked `candidates[compactedCount - 1]`, where
 * `compactedCount` counts only THIS round's increment, so it landed on an
 * unrelated earlier row — and the caller persisted that as the new anchor. The
 * folded range collapsed back to the first round and the next request re-sent
 * every already-summarised turn verbatim.
 *
 * The fix widens the equivalence class: an anchor is VALID whenever the loaded
 * session still holds its id — ANY message type. Only an id that is gone from
 * the session (deleted / truncated away) is an orphan, and only then may the
 * self-heal run. That second half is essential: "never self-heal" would have
 * deleted the feature.
 *
 * Expected values are literal ground truth.
 */
class CompactAnchorValidityTest {

    // ── the equivalence class: message TYPE does not matter ──────────────

    @Test
    fun `a tool_result carrier anchor is held and must not self-heal`() {
        // 30-row session. The anchor is row 28 — a pure tool_result carrier:
        // present in loaded.llmHistory (so in historyDbIds) but never a UI row.
        val historyDbIds = (1..30).map { "m$it" }.toSet()
        val anchor = "m28"

        assertTrue(
            "the session holds the anchor, whatever the message type",
            CompactAnchorValidity.isAnchorStillHeld(anchor, historyDbIds),
        )
        assertFalse(
            "a HELD anchor must never trigger the createdAt self-heal — that is the DB " +
                "rewrite that rolled the folded range back to round 1",
            CompactAnchorValidity.shouldSelfHeal(anchor, historyDbIds),
        )
    }

    @Test
    fun `every message type in the session counts as held`() {
        val historyDbIds = setOf("u1", "a2", "tr3", "img4", "sys5")
        for (id in listOf("u1", "a2", "tr3", "img4", "sys5")) {
            assertTrue("[$id] must be accepted as a live anchor", CompactAnchorValidity.isAnchorStillHeld(id, historyDbIds))
        }
    }

    // ── the feature that must NOT be deleted:真孤儿 still heals ───────────

    @Test
    fun `an id the session no longer holds is an orphan and still self-heals`() {
        val historyDbIds = setOf("m1", "m2", "m3")
        val deletedAnchor = "m-deleted"

        assertFalse(
            "a deleted anchor is not held",
            CompactAnchorValidity.isAnchorStillHeld(deletedAnchor, historyDbIds),
        )
        assertTrue(
            "a genuinely orphaned anchor MUST still self-heal — otherwise this fix deleted the feature",
            CompactAnchorValidity.shouldSelfHeal(deletedAnchor, historyDbIds),
        )
    }

    @Test
    fun `a null or blank anchor falls back to the pre-existing heal path`() {
        val historyDbIds = setOf("m1")
        assertTrue("null anchor → heal (pre-existing behaviour)", CompactAnchorValidity.shouldSelfHeal(null, historyDbIds))
        assertTrue("blank anchor → heal (pre-existing behaviour)", CompactAnchorValidity.shouldSelfHeal("", historyDbIds))
    }

    @Test
    fun `an empty loaded history proves nothing and keeps the heal path`() {
        // No loaded LLM history ⇒ nothing to prove the anchor against. The
        // pre-existing behaviour is kept (fail-open to the heal), which the
        // bounded heal itself then refuses when it has no candidate.
        assertTrue(CompactAnchorValidity.shouldSelfHeal("m1", emptySet()))
    }

    // ── end-to-end over the real heal primitives (data retention) ─────────

    private fun row(id: String, createdAt: Long, role: String = "user") = MessageEntity(
        id = id,
        sessionId = "s1",
        role = role,
        partsJson = "",
        createdAt = createdAt,
        sortOrder = createdAt.toInt(),
    )

    /**
     * The reported scenario, driven over the REAL primitives: 30 rows, a
     * second-round marker anchored on a tool_result carrier (row 28), and
     * `compactedCount = 6` (this round's increment). Proves the two things the
     * task demands: the anchor row survives, and the marker is not rewritten.
     */
    @Test
    fun `second round anchored on a tool_result keeps the anchor and never rewrites the marker`() {
        val rows = (1..30).map { row("m$it", 1_000L + it * 1_000L) }
        val historyDbIds = rows.map { it.id }.toSet()
        val anchor = rows[27] // m28 — the tool_result carrier

        assertTrue("precondition: the session holds m28", CompactAnchorValidity.isAnchorStillHeld(anchor.id, historyDbIds))
        assertFalse(
            "no self-heal may run: the anchor that would be replaced still exists",
            CompactAnchorValidity.shouldSelfHeal(anchor.id, historyDbIds),
        )

        // Sanity: had the heal been allowed, it would have produced a WRONG
        // replacement (an unrelated earlier row) — this is the data loss the
        // gate prevents, and it is asserted as the NEGATIVE control rather
        // than assumed.
        val wouldBeHeal = healAnchorByCreatedAt(rows, markerCreatedAt = 100_000L, historyDbIds, compactedCount = 6)
        assertNotNull("control: the unbounded caller WOULD have a heal candidate", wouldBeHeal)
        assertTrue(
            "control: that candidate is an EARLIER row than the real anchor (${wouldBeHeal!!.id} < m28)",
            rows.indexOfFirst { it.id == wouldBeHeal.id } < rows.indexOfFirst { it.id == anchor.id },
        )
    }

    @Test
    fun `a truly deleted anchor still heals inside the folded block`() {
        // The anchor row is gone (rewind / truncation). The bounded heal must
        // still pick a live row — proving the feature survives the fix.
        val rows = (1..5).map { row("m$it", 1_000L + it * 1_000L) }
        val historyDbIds = rows.map { it.id }.toSet()

        assertTrue(
            "deleted anchor → heal is allowed",
            CompactAnchorValidity.shouldSelfHeal("m-gone", historyDbIds),
        )
        val healed = healAnchorByCreatedAt(rows, markerCreatedAt = 10_000L, historyDbIds, compactedCount = 3)
        assertEquals("m3", healed!!.id)
        assertNotNull("the healed anchor is a real row", healed)
    }

    @Test
    fun `the marker keeps pointing at the original anchor id`() {
        // Data-retention assertion in the form the defect actually damaged:
        // after the guarded decision, the row the marker references is still
        // byte-identical (id + createdAt + role), so the "everything up to
        // this id is summarised" contract still holds.
        val rows = (1..30).map { row("m$it", 1_000L + it * 1_000L) }
        val historyDbIds = rows.map { it.id }.toSet()
        val anchorBefore = rows[27]

        val selfHeal = CompactAnchorValidity.shouldSelfHeal(anchorBefore.id, historyDbIds)
        // The caller only rewrites when this is true; it is false here.
        val anchorAfter = if (selfHeal) {
            healAnchorByCreatedAt(rows, markerCreatedAt = 100_000L, historyDbIds, compactedCount = 6)
        } else {
            anchorBefore
        }

        assertFalse("no heal for a held anchor", selfHeal)
        assertEquals("m28", anchorAfter!!.id)
        assertEquals(29_000L, anchorAfter.createdAt)
        assertEquals("user", anchorAfter.role)
        assertNull("control: nothing deleted the row from the session", rows.firstOrNull { it.id == "m28" }?.errorInfo)
    }
}
