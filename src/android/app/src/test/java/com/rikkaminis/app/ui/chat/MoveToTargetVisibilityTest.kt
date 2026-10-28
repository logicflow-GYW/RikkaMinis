package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FIX-C8/F-11] Which sessions the move-to picker offers.
 *
 * The defect: the sheet filtered only `it.id != currentSessionId`, so the rows
 * the history drawer deliberately hides — ghost sessions materialised by a
 * /memory or /thinking toggle, abandoned drafts with no message and no preview
 * — were offered as move targets. Tapping one moved the composer's stashed
 * input into a chat the user cannot then find in the drawer.
 *
 * The predicate delegates to the drawer's own [isDrawerRowVisible] (dv24-b3)
 * instead of copying its three terms, so the two lists cannot drift apart; the
 * delegation itself is pinned below as a property over the whole input grid.
 */
class MoveToTargetVisibilityTest {

    private val sourcePath =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/MoveToSessionSheet.kt"

    private val drawerSourcePath =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatHistoryDrawer.kt"

    // ── ghost rows (the regression) ────────────────────────────────────────

    @Test
    fun `an unpinned empty session with no preview is not a target`() {
        assertFalse(isMoveToTargetVisible("s2", "s1", pinnedAt = null, messageCount = 0, lastMessage = null))
    }

    @Test
    fun `a whitespace-only preview does not make a ghost row a target`() {
        assertFalse(isMoveToTargetVisible("s2", "s1", pinnedAt = null, messageCount = 0, lastMessage = "   "))
    }

    @Test
    fun `a blank preview does not make a ghost row a target`() {
        assertFalse(isMoveToTargetVisible("s2", "s1", pinnedAt = null, messageCount = 0, lastMessage = ""))
    }

    // ── real sessions stay offered ─────────────────────────────────────────

    @Test
    fun `a session with messages is a target`() {
        assertTrue(isMoveToTargetVisible("s2", "s1", pinnedAt = null, messageCount = 3, lastMessage = null))
    }

    @Test
    fun `a pinned empty session is a target`() {
        assertTrue(isMoveToTargetVisible("s2", "s1", pinnedAt = 1_700_000_000_000L, messageCount = 0, lastMessage = null))
    }

    @Test
    fun `a preview-only session is a target`() {
        assertTrue(isMoveToTargetVisible("s2", "s1", pinnedAt = null, messageCount = 0, lastMessage = "hi"))
    }

    // ── the self filter the caller always had ──────────────────────────────

    @Test
    fun `the current session is never a target even when it has content`() {
        assertFalse(isMoveToTargetVisible("s1", "s1", pinnedAt = 1L, messageCount = 9, lastMessage = "hi"))
    }

    // ── anti-drift: the drawer predicate is the only definition ────────────

    @Test
    fun `visibility is exactly the drawer predicate plus the self filter`() {
        val lastMessages = listOf(null, "", "  ", "text")
        val counts = listOf(0, 1, 5)
        val pinned = listOf(null, 0L, 1L)
        for (lm in lastMessages) for (c in counts) for (p in pinned) {
            val expected = (p != null || c > 0 || !lm.isNullOrBlank())
            assertEquals(
                "lastMessage=$lm count=$c pinned=$p",
                expected,
                isMoveToTargetVisible("s2", "s1", p, c, lm),
            )
            assertEquals(
                "the drawer itself must agree",
                expected,
                isDrawerRowVisible(pinnedAt = p, messageCount = c, lastMessage = lm),
            )
            assertFalse(isMoveToTargetVisible("s1", "s1", p, c, lm))
        }
    }

    // ── source shape ───────────────────────────────────────────────────────

    @Test
    fun `the sheet filters through the predicate and reads real message counts`() {
        val src = java.io.File(sourcePath).readText()
        assertTrue(src.contains("isMoveToTargetVisible("))
        assertTrue("counts must come from the drawer's live map, not a hardcoded 0",
            src.contains("chatRepository.observeMessageCountsPerSession().first()"))
        assertFalse("the id-only filter is the defect",
            src.contains("chatRepository.dao.listSessions().filter { it.id != currentSessionId }"))
    }

    @Test
    fun `the drawer still owns the three-term definition`() {
        val src = java.io.File(drawerSourcePath).readText()
        assertTrue(src.contains("internal fun isDrawerRowVisible("))
        assertTrue(src.contains("pinnedAt != null || messageCount > 0 || !lastMessage.isNullOrBlank()"))
    }
}
