package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R5-C C-C-04①② / C-C-05 / C-C-06 / C-C-07①②] Wiring pins for the fixes that
 * only exist as call sites inside the ViewModel / executor / cleanup files — the
 * pure decisions are pinned by StopCleanupPlanTest and ToolLoopDetectorTest, so
 * losing a call (or moving it to the wrong side of its gate) would otherwise be
 * invisible to the suite.
 *
 * Each assertion fails when the call disappears, when the gate guard is
 * dropped, or when the single-sourced constant is inlined again.
 */
class R5C3EditToolGatesWiringTest {

    private val base = "src/android/app/src/main/java/com/rikkaminis/app/"

    private val vm by lazy { readRepoFile(base + "ui/chat/ChatViewModel.kt") }
    private val queue by lazy { readRepoFile(base + "ui/chat/ChatQueueInterruption.kt") }
    private val retryOps by lazy { readRepoFile(base + "ui/chat/ChatRetryOps.kt") }
    private val routing by lazy { readRepoFile(base + "ui/chat/ChatModelRouting.kt") }
    private val prompt by lazy { readRepoFile(base + "ui/chat/ChatPromptAndTools.kt") }
    private val plan by lazy { readRepoFile(base + "ui/chat/StopCleanupPlan.kt") }

    // ── C-C-05: edit sends skip the context gates ───────────────────────────

    @Test
    fun `edit sends skip all three context gates`() {
        val probe = vm.indexOf("val editingSend = _editingMessageId.value != null")
        assertTrue("edit-send probe missing", probe > 0)
        assertTrue(
            "the pre-send context check must be guarded by the edit probe",
            vm.contains("if (!editingSend && !checkContextBeforeSend())"),
        )
        val compact = vm.indexOf("if (!editingSend) maybeTriggerAutoCompact()")
        val await = vm.indexOf("if (!editingSend) awaitAutoCompactIfNeeded()")
        assertTrue("auto-compact trigger must be skipped for edits ($compact > $probe)", compact > probe)
        assertTrue("auto-compact await must be skipped for edits ($await > $probe)", await > probe)
    }

    @Test
    fun `a refused send cannot leave edit state behind`() {
        val stash = vm.indexOf("pendingExhaustedText = trimmed")
        assertTrue("EXHAUSTED stash missing", stash > 0)
        val window = vm.substring(maxOf(0, stash - 700), stash)
        assertTrue(
            "a refused send must clear the edit id",
            window.contains("_editingMessageId.value = null"),
        )
        assertTrue(
            "a refused send must clear the pre-edit attachments",
            window.contains("_preEditAttachments.value = null"),
        )
    }

    // ── C-C-04①: in-place sub-message cut clears an anchored marker ─────────

    @Test
    fun `sub-message truncation drops the marker anchored on the rewritten row`() {
        val helper = vm.indexOf("internal suspend fun ChatViewModel.dropCompactMarkerIfAnchoredOnRow(")
        assertTrue("anchored-marker helper missing", helper > 0)
        val body = vm.substring(helper, minOf(vm.length, helper + 800))
        assertTrue(
            "helper must bail out unless the marker anchors on the truncated row",
            body.contains("if (!compactMarkerAnchoredOnRow(_cachedLatestMarker, truncatedRowId)) return"),
        )
        assertTrue("helper must delete the stale marker row", body.contains("dao.deleteCompactMarkers("))
        val call = vm.indexOf("dropCompactMarkerIfAnchoredOnRow(sid, row.id)")
        assertTrue("helper must be CALLED from the sub-message cut ($call < $helper)", call in 1 until helper)
    }

    // ── C-C-04②: both sibling rollback paths clean marker + pressure ────────

    @Test
    fun `both sibling rollback paths drop the marker and reset pressure`() {
        for ((name, src) in listOf("ChatRetryOps.kt" to retryOps, "ChatModelRouting.kt" to routing)) {
            val rollback = src.indexOf("rollbackIncompleteTurn()")
            val marker = src.indexOf("dropCompactMarkerIfRewound(")
            val pressure = src.indexOf("resetContextPressureState()")
            assertTrue("$name: rollback missing", rollback > 0)
            assertTrue("$name: marker rewind missing", marker > rollback)
            assertTrue("$name: pressure reset missing", pressure > rollback)
        }
    }

    // ── C-C-06: reminder written after the slot, orphan cleaned up ──────────

    @Test
    fun `resume writes the reminder only after the slot it waits on`() {
        val write = vm.indexOf("val reminder = CONTINUATION_REMINDER_TEXT")
        assertTrue("reminder write missing", write > 0)
        val slot = vm.lastIndexOf("SessionConcurrencyManager.acquireSlot(activeSessionId)", write)
        assertTrue("reminder write must come after its slot acquisition ($slot < $write)", slot in 1 until write)
        val claim = vm.indexOf("resume streamJob aborted after slot (claim revoked")
        assertTrue("claim re-validation missing", claim in slot until write)
        assertTrue(
            "reminder text must stay single-sourced in StopCleanupPlan.kt",
            !vm.contains("The user stopped the previous response") &&
                plan.contains("The user stopped the previous response"),
        )
    }

    @Test
    fun `stop cleanup removes the orphan reminder and keeps the bubble resumable`() {
        assertTrue(
            "cleanup must probe the reminder tail explicitly",
            queue.contains("isContinuationReminderTail(it.role, it.content)"),
        )
        assertTrue(
            "the placeholder arm must honour the plan's reminder drop",
            queue.contains("if (stopPlan.dropContinuationReminder) dropContinuationReminderTail()"),
        )
        assertTrue(
            "the orphan remover must exist",
            queue.contains("internal fun ChatViewModel.dropContinuationReminderTail()"),
        )
        assertTrue(
            "the DB delete must refuse a partial delete when the row is not the tail",
            queue.contains("is not the DB tail — kept (no partial delete)"),
        )
    }

    // ── C-C-07①: dispatch is budgeted before the child runs ─────────────────

    @Test
    fun `sub-agent dispatch charges the run's budget before the child runs`() {
        val at = prompt.indexOf("internal suspend fun ChatViewModel.executeSpawnAgentTool(")
        assertTrue("the dispatch function is gone", at > 0)
        val body = prompt.substring(at)
        val call = body.indexOf("ChatMutationMethods.prompt(")
        assertTrue("the dispatch call site is gone", call > 0)
        assertTrue(
            "dispatch must refuse past the run deadline before prompting",
            body.indexOf("activeRunBudget?.isExpired() == true") in 0 until call,
        )
        assertTrue(
            "what the child spends must be charged to THIS run's provider budget",
            body.indexOf("DIMENSION_PROVIDER_ATTEMPTS") in 0 until call,
        )
    }

    // ── C-C-07②: provider-null fallback obeys the final-failure predicate ───

    @Test
    fun `provider-null title fallback obeys the final-failure predicate`() {
        val predicate = prompt.indexOf("fun titleFailureIsFinal(): Boolean")
        val nullBranch = prompt.indexOf("if (provider == null) {")
        assertTrue("titleFailureIsFinal missing", predicate > 0)
        assertTrue("provider==null branch missing", nullBranch > predicate)
        val window = prompt.substring(nullBranch, minOf(prompt.length, nullBranch + 1600))
        assertTrue(
            "the fallback must be gated by titleFailureIsFinal",
            window.contains("if (!titleFailureIsFinal()) {"),
        )
        assertTrue(
            "the gated fallback must be the first-message title write",
            window.contains("applyFallbackTitleFromFirstMessage(\"no provider available\")"),
        )
    }

    /** Walks up from user.dir so the test runs from the module dir or the repo root. */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
