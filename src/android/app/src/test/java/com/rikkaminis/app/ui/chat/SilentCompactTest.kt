package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/silent-auto-compact] Behaviour tests for the silent auto-compact
 * contract, run against the REAL [neutralizeCompactArtifacts] source (not a
 * copy of its expression — a copied expression would pass even if production
 * drifted).
 *
 * The change makes auto-compaction invisible: no divider row, no graying.
 * Two things must therefore hold:
 *
 *  1. The divider-stripping pass must remove EVERY compact divider while
 *     leaving every other system row (context-full notices, trim notices,
 *     errors) alone — those are separate features that still surface.
 *  2. The graying flags must end up cleared, including on rows a previous
 *     MANUAL compact had dimmed. A silent pass that left stale flags would
 *     leave the transcript half-dimmed forever (the marker moved forward, so
 *     the old boundary no longer describes what is folded).
 */
class SilentCompactTest {

    private fun row(
        id: String,
        role: String = "user",
        isCompactedHistory: Boolean = false,
        toolName: String? = null,
    ): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = "body of $id",
        isCompactedHistory = isCompactedHistory,
        toolBlocks = if (toolName != null) {
            listOf(AssistantBlock(id = "$id-block", kind = "info", toolName = toolName))
        } else emptyList(),
    )

    private fun neutralize(messages: List<ChatMessage>) = neutralizeCompactArtifacts(messages)

    // ── divider stripping ──────────────────────────────────────────

    @Test
    fun `every compact divider is dropped`() {
        val h = listOf(
            row("u1"),
            row("d1", role = "system", toolName = "compact"),
            row("a1", role = "assistant"),
            row("d2", role = "system", toolName = "compact"),
        )
        val out = neutralize(h)
        assertEquals(listOf("u1", "a1"), out.map { it.id })
    }

    @Test
    fun `other system rows survive the strip`() {
        // A context-full notice and a trim notice are NOT compact dividers —
        // stripping them would silently delete unrelated features.
        val h = listOf(
            row("u1"),
            row("notice", role = "system", toolName = "info"),
            row("trim", role = "system", toolName = "trim"),
            row("d1", role = "system", toolName = "compact"),
        )
        val out = neutralize(h)
        assertEquals(listOf("u1", "notice", "trim"), out.map { it.id })
    }

    @Test
    fun `a system row with no blocks is kept`() {
        // Defensive: firstOrNull() is null here, so the toolName match cannot
        // fire — the row must not be dropped by accident.
        val h = listOf(row("u1"), row("empty", role = "system"))
        val out = neutralize(h)
        assertEquals(listOf("u1", "empty"), out.map { it.id })
    }

    // ── graying cleared ────────────────────────────────────────────

    @Test
    fun `stale graying from a previous manual compact is cleared`() {
        val h = listOf(
            row("u1", isCompactedHistory = true),
            row("a1", role = "assistant", isCompactedHistory = true),
            row("u2"),
        )
        val out = neutralize(h)
        assertTrue("no row may stay greyed", out.none { it.isCompactedHistory })
    }

    @Test
    fun `content of every surviving row is preserved`() {
        val h = listOf(
            row("u1", isCompactedHistory = true),
            row("d1", role = "system", toolName = "compact"),
            row("a1", role = "assistant"),
        )
        val out = neutralize(h)
        assertEquals(2, out.size)
        assertEquals("body of u1", out[0].content)
        assertEquals("body of a1", out[1].content)
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(neutralize(emptyList()).isEmpty())
    }

    // ── ordering is stable ─────────────────────────────────────────

    @Test
    fun `relative order of surviving rows is unchanged`() {
        val h = listOf(
            row("u1"),
            row("d1", role = "system", toolName = "compact"),
            row("u2"),
            row("d2", role = "system", toolName = "compact"),
            row("u3"),
            row("a1", role = "assistant"),
        )
        val out = neutralize(h)
        assertEquals(listOf("u1", "u2", "u3", "a1"), out.map { it.id })
    }

    @Test
    fun `a transcript with no compact rows is returned unchanged`() {
        val h = listOf(row("u1"), row("a1", role = "assistant"), row("u2"))
        val out = neutralize(h)
        assertEquals(h.map { it.id }, out.map { it.id })
        assertFalse(out.any { it.isCompactedHistory })
    }
}
