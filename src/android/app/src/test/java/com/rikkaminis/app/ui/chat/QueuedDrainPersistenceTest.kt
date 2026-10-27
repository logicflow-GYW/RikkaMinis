package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [B-04 deep-audit-1037] `drainQueuedPrompts` persisted a user row but threw
 * the appended entity away.
 *
 * The audited shape: `chatRepository.appendMessage(sid, "user", userPartsJson)`
 * sat at STATEMENT position, so nothing downstream knew the row's id. The
 * `LLMMessage` pushed onto `agentHistory` carried no `dbMessageId` (the twin
 * `injectQueuedPromptsAsNewTurn` did carry it), so the compaction anchors
 * (`isPersistedUserPrompt` resolves through the id) skipped the turn; and the
 * N queued placeholder bubbles were only flipped in place instead of being
 * collapsed into the ONE persisted row, so the screen showed N user rows while
 * the DB held 1 — the edit/retry cutoff walk (`truncateBeforeEdit`) counts
 * visible user rows, so a retry truncated the wrong number of DB rows.
 * The function also had no `combinedParts.isEmpty()` guard (the twin had one):
 * a queue whose text and attachments all vanished produced an empty user row,
 * a deterministic provider 400.
 *
 * These are placement contracts — no value-level assertion can express them —
 * so they are written as source slices. Every expected literal was read off the
 * real tree (see `nl -ba` in the fix report), never derived from a run.
 *
 * Source: `src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt`
 */
class QueuedDrainPersistenceTest {

    private val queueFile = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatQueueInterruption.kt"
    private val viewModelFile = "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"

    private fun drainBody(): String = slice(
        queueFile,
        "internal suspend fun ChatViewModel.drainQueuedPrompts(",
        "\ninternal ",
    )

    private fun injectBody(): String = slice(
        queueFile,
        "internal suspend fun ChatViewModel.injectQueuedPromptsAsNewTurn(",
        "\ninternal ",
    )

    // ── the appended entity is bound ───────────────────────────────────────

    @Test
    fun `the appended user entity is bound to a val`() {
        val body = drainBody()
        assertTrue(
            "drain must bind the appended entity: the id is what the UI row and the agentHistory entry carry",
            body.contains("val userEntity = chatRepository.appendMessage(sid, \"user\", userPartsJson)"),
        )
    }

    @Test
    fun `no appendMessage call sits at statement position in the drain`() {
        val body = drainBody()
        val dropped = body.lines().any { it.trim() == "chatRepository.appendMessage(sid, \"user\", userPartsJson)" }
        assertFalse("a statement-position append discards the id (the audited B-04 shape)", dropped)
    }

    // ── the persisted turn is resolvable downstream ─────────────────────────

    @Test
    fun `the agentHistory entry carries the persisted id`() {
        assertTrue(
            "without dbMessageId the compaction anchors skip the turn",
            drainBody().contains("dbMessageId = userEntity.id"),
        )
    }

    // ── the empty-content turn never reaches the provider ──────────────────

    @Test
    fun `the empty-content guard runs before the persist`() {
        val body = drainBody()
        val guard = body.indexOf("if (combinedParts.isEmpty())")
        val persist = body.indexOf("chatRepository.appendMessage(")
        assertTrue("the guard must exist", guard >= 0)
        assertTrue("the persist must exist", persist > 0)
        assertTrue("the guard must precede the persist (guard=$guard persist=$persist)", guard < persist)
    }

    @Test
    fun `the skipped turn drops its placeholders too`() {
        // The queue is emptied before the guard, so a bare `return` would leave
        // dashed bubbles with no queue entry behind them — never drained,
        // never withdrawable.
        val body = drainBody()
        val guard = body.indexOf("if (combinedParts.isEmpty())")
        val guardTail = body.substring(guard, minOf(body.length, guard + 500))
        assertTrue(
            "the guard branch must remove the queued placeholders",
            guardTail.contains("removeQueuedPlaceholders(queuedIds)"),
        )
    }

    // ── the UI converges to the ONE persisted row ──────────────────────────

    @Test
    fun `the drain collapses the placeholders into the single persisted row`() {
        val body = drainBody()
        assertTrue(
            "N placeholder bubbles must be removed through the single funnel",
            body.contains("removeQueuedPlaceholders(queuedIds)"),
        )
        assertTrue(
            "the merged bubble must use the persisted id",
            body.contains("id = userEntity.id,"),
        )
    }

    @Test
    fun `the merged bubble mirrors the attachment surfaces of the persisted row`() {
        val body = drainBody()
        assertTrue(body.contains("imageUris = prepared.imageUris,"))
        assertTrue(body.contains("attachmentNames = prepared.attachmentNames,"))
        assertTrue(body.contains("attachmentUris = prepared.nonImageUris,"))
    }

    // ── the two queue paths may not drift apart ────────────────────────────

    @Test
    fun `the twin inject path carries the same two contracts`() {
        val inject = injectBody()
        assertTrue(
            "the twin must keep binding the appended entity",
            inject.contains("val userEntity = chatRepository.appendMessage("),
        )
        assertTrue(
            "the twin must keep sending the persisted id",
            inject.contains("dbMessageId = userEntity.id"),
        )
    }

    @Test
    fun `the guard predicate from the twin is reused, not re-spelled`() {
        // Parity note: both functions guard on the same `combinedParts`
        // emptiness. A future third spelling of that decision is the defect
        // class this pair of assertions exists to catch.
        assertTrue(drainBody().contains("if (combinedParts.isEmpty())"))
        assertTrue(injectBody().contains("if (combinedParts.isEmpty())"))
    }

    // ── B-05 (3)(4): the cutoff walks ask the shared predicate ─────────────

    @Test
    fun `both edit-retry cutoff walks use the shared visible-user predicate`() {
        assertTrue(
            "ChatQueueInterruption.truncateBeforeEdit must ask the shared predicate",
            readRepoFile(queueFile).contains("val hasText = userPartsJsonHasVisibleContent(entity.partsJson)"),
        )
        assertTrue(
            "the ChatViewModel DB walk must ask the same predicate",
            readRepoFile(viewModelFile).contains("val hasText = userPartsJsonHasVisibleContent(entity.partsJson)"),
        )
    }

    @Test
    fun `the cutoff walks no longer spell the text-part test themselves`() {
        // The pre-fix shape: an inline JSONArray walk comparing `type` to
        // "text" and reading `value`. It must not come back in either walk.
        val queue = readRepoFile(queueFile)
        assertFalse(
            "no inline text-part extraction may survive in ChatQueueInterruption's cutoff walk",
            queue.contains("""if (o.optString("type") == "text")"""),
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun slice(relative: String, startMarker: String, endMarker: String): String {
        val text = readRepoFile(relative)
        val at = text.indexOf(startMarker)
        assertTrue("start marker not found: $startMarker", at >= 0)
        val end = text.indexOf(endMarker, at + startMarker.length)
        assertTrue("end marker not found after $startMarker", end > at)
        return text.substring(at, end)
    }

    private fun readRepoFile(relative: String): String {
        var dir = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
