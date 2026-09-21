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
 *
 * [fix/silent-auto-compact-notice-leak] The fixtures here must be built from
 * PRODUCTION shapes, and the first version was not. `appendSystemInfo` sets
 * `toolName = iconKind` (ChatViewModel.kt:1871), and the hard-trim notice
 * (ChatContextWindow.kt:430), the context-full notice
 * (ChatContextWindowExt.kt:316) and the failure banner
 * (ChatSessionLifecycle.kt:418) all pass `iconKind = "compact"` — the SAME
 * value the divider uses. The old fixture gave the trim notice
 * `toolName = "trim"`, a value no production call site ever produces, so the
 * test stayed green while the real notice was being deleted with the card.
 * `row(toolName = ...)` below is therefore only ever called with the literal
 * values the app actually emits.
 */
class SilentCompactTest {

    private fun row(
        id: String,
        role: String = "user",
        isCompactedHistory: Boolean = false,
        toolName: String? = null,
        payload: String = "",
    ): ChatMessage = ChatMessage(
        id = id,
        role = role,
        content = "body of $id",
        isCompactedHistory = isCompactedHistory,
        toolBlocks = if (toolName != null) {
            listOf(AssistantBlock(id = "$id-block", kind = "info", toolName = toolName, toolArgs = payload))
        } else emptyList(),
    )

    /** The divider card: `iconKind = "compact"` + the summary payload. */
    private fun divider(id: String) = row(id, role = "system", toolName = "compact", payload = "SUMMARY")

    /**
     * A notice sharing the divider's iconKind but carrying no payload —
     * the shape of the hard-trim / context-full / failure rows.
     */
    private fun compactNotice(id: String) = row(id, role = "system", toolName = "compact")

    private fun neutralize(messages: List<ChatMessage>) = neutralizeCompactArtifacts(messages)

    // ── divider stripping ──────────────────────────────────────────

    @Test
    fun `every compact divider is dropped`() {
        val h = listOf(
            row("u1"),
            divider("d1"),
            row("a1", role = "assistant"),
            divider("d2"),
        )
        val out = neutralize(h)
        assertEquals(listOf("u1", "a1"), out.map { it.id })
    }

    @Test
    fun `a compact notice without a payload survives the strip`() {
        // [fix/silent-auto-compact-notice-leak] This is the case the first
        // fixture missed: a system row whose toolName IS "compact" (as the
        // hard-trim / context-full notices are) but which is not the card.
        // Dropping it silently deleted a "context reached the limit" notice.
        val h = listOf(
            row("u1"),
            compactNotice("trim"),
            divider("d1"),
            row("u2"),
        )
        val out = neutralize(h)
        assertEquals(listOf("u1", "trim", "u2"), out.map { it.id })
    }

    @Test
    fun `other system rows survive the strip`() {
        val h = listOf(
            row("u1"),
            row("notice", role = "system", toolName = "info"),
            row("memory", role = "system", toolName = "memory"),
            row("thinking", role = "system", toolName = "thinking"),
            divider("d1"),
        )
        val out = neutralize(h)
        assertEquals(listOf("u1", "notice", "memory", "thinking"), out.map { it.id })
    }

    @Test
    fun `a system row with no blocks is kept`() {
        // Defensive: firstOrNull() is null here, so the toolName match cannot
        // fire — the row must not be dropped by accident.
        val h = listOf(row("u1"), row("empty", role = "system"))
        val out = neutralize(h)
        assertEquals(listOf("u1", "empty"), out.map { it.id })
    }

    @Test
    fun `a divider row keeps its payload so the detail sheet is reachable`() {
        // The divider is identified BY its payload; assert the two are still
        // paired so a future refactor cannot make every row look like a card.
        assertTrue(divider("d1").isCompactDividerRow())
        assertFalse(compactNotice("n1").isCompactDividerRow())
        assertFalse(row("u1").isCompactDividerRow())
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
            divider("d1"),
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
            divider("d1"),
            row("u2"),
            compactNotice("n1"),
            divider("d2"),
            row("u3"),
            row("a1", role = "assistant"),
        )
        val out = neutralize(h)
        assertEquals(listOf("u1", "u2", "n1", "u3", "a1"), out.map { it.id })
    }

    @Test
    fun `a transcript with no compact rows is returned unchanged`() {
        val h = listOf(row("u1"), row("a1", role = "assistant"), row("u2"))
        val out = neutralize(h)
        assertEquals(h.map { it.id }, out.map { it.id })
        assertFalse(out.any { it.isCompactedHistory })
    }
}
