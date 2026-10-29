package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-R4-P F-04/F-05] Table-driven coverage for the two pure decisions the
 * compacted-slice repair is built on.
 *
 * Both are pure functions on purpose: the slice they guard is assembled from
 * [ChatViewModel.agentHistory] plus a persisted marker, and the defects they
 * fix (`leading ASSISTANT` / `consecutive USER` slices that strict gateways
 * reject with a 400; a marker whose anchor was rewound past) only show up as a
 * provider error or as silently re-injected deleted content on a device.
 */
class CompactSliceRepairTest {

    private fun u(text: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
    )

    private fun a(text: String) = LLMMessage(
        role = LLMMessage.Role.ASSISTANT,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
    )

    private fun roles(list: List<LLMMessage>) =
        list.joinToString(",") { it.role.name.first().toString() }

    private fun texts(m: LLMMessage) =
        m.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("|") { it.text }

    @Test
    fun `legal alternating slices are untouched`() {
        val cases = listOf(
            mutableListOf(u("u1"), a("a1"), u("u2"), a("a2")),
            mutableListOf(u("u1"), a("a1")),
            mutableListOf(u("only")),
        )
        for (slice in cases) {
            val before = roles(slice)
            normalizeSliceRoleAlternation(slice)
            assertEquals("a legal slice must not be rewritten", before, roles(slice))
        }
    }

    @Test
    fun `a leading assistant is peeled and the user text survives`() {
        // Shape 1: the boundary guard pulled the paired tool_use assistant into
        // the slice; the alternation peel removed it, then sanitize dropped the
        // now-orphaned tool_result, leaving this as the head.
        val slice = mutableListOf(a("a0"), u("u1"), a("a1"))
        normalizeSliceRoleAlternation(slice)
        assertEquals("U,A", roles(slice))
        assertEquals("u1", texts(slice[0]))
    }

    @Test
    fun `peeling that exposes a same-role pair merges it`() {
        val slice = mutableListOf(a("a0"), u("u1"), u("u2"), a("a1"))
        normalizeSliceRoleAlternation(slice)
        assertEquals("U,A", roles(slice))
        assertEquals("u1|u2", texts(slice[0]))
    }

    @Test
    fun `consecutive user turns are merged, not dropped`() {
        // Shape 2: the standalone summary USER was appended after a
        // tool_result-only USER.
        val slice = mutableListOf(u("u1"), a("a1"), u("u2"), u("<context-summary>s</context-summary>"))
        normalizeSliceRoleAlternation(slice)
        assertEquals("U,A,U", roles(slice))
        assertEquals("u2|<context-summary>s</context-summary>", texts(slice[2]))
    }

    @Test
    fun `a content-only message keeps its text through the merge`() {
        // Serialization reads contentParts when present and ignores content —
        // the merge must lift the content-only side into a Text part.
        val slice = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = "typed"),
            LLMMessage(role = LLMMessage.Role.USER, content = "summary"),
            a("a1"),
        )
        normalizeSliceRoleAlternation(slice)
        assertEquals("U,A", roles(slice))
        assertEquals("typed|summary", texts(slice[0]))
        assertEquals("typed\nsummary", slice[0].content)
    }

    @Test
    fun `consecutive assistants merge instead of breaking alternation`() {
        val slice = mutableListOf(u("u1"), a("a1"), a("a2"))
        normalizeSliceRoleAlternation(slice)
        assertEquals("U,A", roles(slice))
        assertEquals("a1|a2", texts(slice[1]))
    }

    @Test
    fun `a slice with no user turn left becomes empty`() {
        // Degenerate but must not throw/NPE: the caller sends what remains.
        val slice = mutableListOf(a("a1"))
        normalizeSliceRoleAlternation(slice)
        assertTrue(slice.isEmpty())
    }

    @Test
    fun `the marker prune decision table`() {
        // Refuse to judge an anchorless (legacy/unknown) marker: "I cannot tell
        // where it sits" must not read as "it is orphaned".
        assertFalse(compactMarkerIsOrphaned(emptyList(), setOf("m1")))
        // Anchor still live → keep (retry of a LATER message does not orphan it).
        assertFalse(compactMarkerIsOrphaned(listOf("m4"), setOf("m1", "m4")))
        // Any live anchor is enough — multi-anchor markers keep the marker.
        assertFalse(compactMarkerIsOrphaned(listOf("m4", "m9"), setOf("m4")))
        // Every anchor gone → the summary now describes deleted turns.
        assertTrue(compactMarkerIsOrphaned(listOf("m4"), setOf("m1", "m2")))
        assertTrue(compactMarkerIsOrphaned(listOf("m4"), emptySet()))
    }
}
