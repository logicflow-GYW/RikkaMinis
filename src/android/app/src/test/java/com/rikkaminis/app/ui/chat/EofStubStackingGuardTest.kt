package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1018 ISSUE-12c] P2 — the error-shaped continuation branch
 * injected `eofStubReminder(...)` without the stacking guard its EOF-stub
 * sibling has.
 *
 * Reachability proof (why this is not a latent bug): the branch is entered by
 * `isErrorShapedFinish(turnFinishReason)` with non-empty turn text, and it
 * loops (`continue`) while `eofStubContinues < eofStubContinues()` — default
 * ceiling 2, so the SECOND consecutive error-shaped turn runs the same
 * injection with the first turn's reminder still sitting in history
 * (one slot behind this turn's assistant message). The model then reads a
 * pile of identical "continue where you left off" instructions.
 *
 * The guard must remove the stale reminder and NOTHING else: a first-time
 * continuation (previous tail = the user's real message, or a length-wall
 * reminder from the other family) must stay.
 */
class EofStubStackingGuardTest {

    private fun userSingleText(text: String) = LLMMessage(
        role = LLMMessage.Role.USER,
        content = text,
        contentParts = listOf(AgentContentPart.Text(text)),
    )

    @Test
    fun `stale stub reminder is recognised and removed`() {
        // Literal built by hand, NOT via eofStubReminder(): pinning the marker
        // against the production wording would make the test pass even if the
        // two drifted apart (self-referential harness).
        val stale = userSingleText(
            "<system-reminder>Your previous response was cut off by a network error mid-stream. " +
                "Continue exactly where you left off, starting from the character after: \"abc\". " +
                "Do not restart or repeat prior text. Finish the answer directly.</system-reminder>",
        )
        assertTrue(EofStubReminderStackGuard.isStaleEofStubReminder(stale))
    }

    @Test
    fun `first-time continuation is never blocked`() {
        val realUserTurn = userSingleText("please write the report")
        assertFalse(EofStubReminderStackGuard.isStaleEofStubReminder(realUserTurn))
    }

    @Test
    fun `length-wall reminders belong to the other family and stay`() {
        // Two reminder texts must not cross-match: removing the wrong one
        // would drop the model's continuation instruction entirely.
        val wall = userSingleText(lengthWallReminder("tail"))
        assertFalse(EofStubReminderStackGuard.isStaleEofStubReminder(wall))
        // …and the marker really is unique to eofStubReminder.
        assertTrue(eofStubReminder("tail").contains(EofStubReminderStackGuard.REMINDER_MARKER))
        assertFalse(wall.content.contains(EofStubReminderStackGuard.REMINDER_MARKER))
    }

    @Test
    fun `null tail (empty or one-entry history) is a clean no-match`() {
        assertFalse(EofStubReminderStackGuard.isStaleEofStubReminder(null))
    }

    @Test
    fun `assistant role never matches regardless of text`() {
        val assistant = LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "cut off by a network error",
            contentParts = listOf(AgentContentPart.Text("cut off by a network error")),
        )
        assertFalse(EofStubReminderStackGuard.isStaleEofStubReminder(assistant))
    }

    @Test
    fun `multi-part messages are not synthetic reminders`() {
        val multi = LLMMessage(
            role = LLMMessage.Role.USER,
            content = "cut off by a network error",
            contentParts = listOf(
                AgentContentPart.Text("cut off by a network error"),
                AgentContentPart.Text("and a second part"),
            ),
        )
        assertFalse(EofStubReminderStackGuard.isStaleEofStubReminder(multi))
    }

    @Test
    fun `plain content without a parts list does not match`() {
        // Real user messages sent through the UI carry content only — the
        // guard keys on the single-Text-part shape the injection uses.
        val plain = LLMMessage(role = LLMMessage.Role.USER, content = "cut off by a network error")
        assertFalse(EofStubReminderStackGuard.isStaleEofStubReminder(plain))
    }

    // ------------------------------------------------------------------
    // Wiring: BOTH injection sites must consult the shared guard
    // ------------------------------------------------------------------

    @Test
    fun `error-shaped and eof-stub branches share one guard`() {
        val src = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt")
        val calls = Regex("EofStubReminderStackGuard\\.isStaleEofStubReminder\\(")
            .findAll(src).map { it.range.first }.toList()
        // Exactly two call sites: the error-shaped continuation (added by this
        // fix) and the EOF-stub continuation (refactored onto the shared one).
        // The THIRD size-2 stacking guard in the file (length-wall family,
        // marker "cut off mid-sentence") is deliberately NOT merged — it is a
        // different reminder family and EofStubStackingGuardTest pins that the
        // two markers never cross-match.
        assertEquals(2, calls.size)
        // The old inline predicate must be gone (no second definition to drift).
        assertFalse(src.contains("val tailIsEofStubReminder ="))
        // Each site keeps the off-by-one shape: read size-2, drop it, add fresh.
        for (idx in calls) {
            val window = src.substring((idx - 500).coerceAtLeast(0), idx + 500)
            assertTrue("site near $idx must read agentHistory.size - 2", window.contains("agentHistory.size - 2"))
            assertTrue("site near $idx must removeAt the tail index", window.contains("removeAt("))
        }
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
