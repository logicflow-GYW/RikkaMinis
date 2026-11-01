package com.rikkaminis.app.guard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [dv1101-review F-01] Wiring contract: **every** entry point that starts a fresh user turn — or
 * rebuilds the conversation — must clear the loop guard's ladders, exactly like it already clears
 * `toolLoopDetector`.
 *
 * The defect this pins: the guard was wired at `sendMessage` only, while the old detector resets at
 * five other sites (clearChat / rerunFromToolBlock / retryFromMessage / switchModelAndRerun /
 * truncateBeforeEdit) and two more paths append a user row without going through sendMessage
 * (mid-stream queue injection, post-loop queue drain). After a guard stop, retry — the standard
 * recovery gesture — re-ran every managed action into a refusal.
 *
 * Shape test on purpose: the contract is "these call sites stay paired", which no behavioural test
 * can observe (each of them needs a full ViewModel + DB). The repo already governs wiring this way
 * (ComposerCommandHandoffTest, PasteFoldWiringTest).
 */
class ToolLoopScopeWiringTest {

    private val chatFiles = listOf(
        "src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt",
        "src/main/java/com/rikkaminis/app/ui/chat/ChatModelRouting.kt",
        "src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt",
    )

    /** How far after the anchor the guard reset may sit (comment blocks included). */
    private val window = 1400

    @Test
    fun everyOldDetectorResetAlsoClearsTheLoopGuard() {
        for (rel in chatFiles) {
            assertPairedEverywhere(rel, "toolLoopDetector.reset()")
        }
    }

    @Test
    fun everyFreshUserRowAlsoClearsTheLoopGuard() {
        // `"user", userPartsJson` is the user-message persist shape. Deliberately NOT the bare
        // `"user"` role: persistToolResultMessage also stores tool results under role=user, and a
        // tool result is not "the user spoke" — resetting there would let a failed tool call
        // launder a stop.
        //
        // Not every chat file persists user rows (ChatModelRouting reruns without one), so the
        // per-file requirement is conditional — but the GLOBAL count is asserted: the three known
        // sites (sendMessage + mid-stream injection + queue drain) all have to be paired.
        var total = 0
        for (rel in chatFiles) {
            total += assertPairedEverywhere(rel, "\"user\", userPartsJson)", requireAnchor = false)
        }
        assertTrue(
            "expected the three user-row sites to be paired, found $total anchor(s) — the shape moved",
            total >= 3,
        )
    }

    @Test
    fun sendMessageStillResetsAfterPersistingTheUserRow() {
        val text = source("src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
        val persist = text.indexOf("appendMessage(activeSessionId, \"user\", userPartsJson)")
        assertTrue("sendMessage's user-row persist moved — update this test", persist >= 0)
        val slice = text.substring(persist, minOf(text.length, persist + window))
        assertTrue(
            "sendMessage persists a user row but does not clear the loop guard within $window chars",
            slice.contains("ToolLoopScope.onUserTurn"),
        )
    }

    /** Returns how many anchor occurrences were seen (0 when [requireAnchor] is false). */
    private fun assertPairedEverywhere(rel: String, anchor: String, requireAnchor: Boolean = true): Int {
        val text = source(rel)
        var cursor = 0
        var seen = 0
        while (true) {
            val at = text.indexOf(anchor, cursor)
            if (at < 0) break
            seen++
            val line = text.substring(0, at).count { it == '\n' } + 1
            val slice = text.substring(at, minOf(text.length, at + window))
            assertTrue(
                "$rel:$line — '$anchor' has no ToolLoopScope.onUserTurn within $window chars. " +
                    "The loop guard must follow the old detector's resets (and every fresh user row).",
                slice.contains("ToolLoopScope.onUserTurn"),
            )
            cursor = at + anchor.length
        }
        if (requireAnchor) {
            assertTrue("$rel — anchor '$anchor' not found; the shape moved, update this test", seen > 0)
        }
        return seen
    }

    private fun source(rel: String): String = sourceFile(rel).readText()

    private fun sourceFile(rel: String): File {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        error("'$rel' not found from ${File(".").absoluteFile}")
    }
}
