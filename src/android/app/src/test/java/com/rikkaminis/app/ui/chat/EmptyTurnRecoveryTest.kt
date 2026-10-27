package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.LLMMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [port-v114 S4 / T-android-empty-turn-reminder-any-tail] The agent loop must recover from an
 * empty model turn regardless of what the history tail is — not only when the
 * tail is a tool_result.
 *
 * The trigger upstream: a request ending on a statement the model owes no
 * reply to (upstream's case is a finished sub-agent callback) returned an empty
 * turn ~7/10 times, so Retry kept showing "Model returned an empty response".
 * Before the fix the recovery guard checked `prior row is all-tool_result`, so
 * a TEXT tail fell straight through to the error hint with no retry.
 *
 * These pin [EmptyTurnRecovery.plan], the decision the loop delegates to. The
 * loop itself needs a live ViewModel; the placement decision does not, so the
 * DECISION is tested here and the loop is a thin adapter over it.
 *
 * Fork adaptation notes (port-v114 S4): upstream's `AgentCallback.isCallbackText`
 * sanity assertion is dropped — RikkaMinis has no such recogniser (grep: 0 hits),
 * so the callback fixture stands in for "any statement-shaped user text tail".
 * Upstream's `shouldClassifyAsEmptyTurn` is not ported either: RikkaMinis's loop
 * already narrows the terminal-stop case in place (`!hasVisibleContent &&
 * finishedCleanly`, with `turnTruncated` excluded), so those cases are owned by
 * AgentLoopEngine rather than by a second predicate here.
 */
class EmptyTurnRecoveryTest {

    private fun user(vararg parts: AgentContentPart) =
        LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = parts.toList())

    private fun userText(t: String) =
        LLMMessage(role = LLMMessage.Role.USER, content = t, contentParts = listOf(AgentContentPart.Text(t)))

    private fun assistant(t: String = "") =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = t, contentParts = if (t.isEmpty()) emptyList() else listOf(AgentContentPart.Text(t)))

    private fun toolResult(id: String, out: String) =
        user(AgentContentPart.ToolResult(id = id, name = "shell_execute", content = out))

    private fun toolUse(id: String) =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "", contentParts = listOf(AgentContentPart.ToolUse(id, "shell_execute", JSONObject())))

    /** The empty assistant turn the loop appends before calling plan(). */
    private val emptyAssistant = assistant("")

    private val REAL_CALLBACK =
        "<agent_callback kind=\"finished\" job=\"09ed2635\" title=\"总结苹果维基前三段\" status=\"done\">\n" +
            "<result>前三段总结……</result>\n</agent_callback>"

    // ── the reported failure: statement-shaped (callback) tail ─────────────

    @Test
    fun `a finished-callback tail is nudged, not given up on`() {
        // Sanity: the fixture really is statement-shaped user text — a notice
        // the model owes no reply to.
        assertTrue(REAL_CALLBACK.contains("<agent_callback kind=\"finished\""))

        val history = listOf(
            userText("请派一个子代理…"),
            assistant("已派出"),
            userText(REAL_CALLBACK),   // the orphan callback tail
            emptyAssistant,            // loop appended this before recovery
        )
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false)
        assertTrue("callback tail must be nudged", plan is EmptyTurnRecovery.Plan.NudgeExistingPart)
        plan as EmptyTurnRecovery.Plan.NudgeExistingPart
        assertEquals("nudge the callback user row (index 2)", 2, plan.tailIndex)
        assertEquals(false, plan.isToolResult)
    }

    @Test
    fun `the nudge lands on the callback text and reads as continue`() {
        val history = listOf(userText(REAL_CALLBACK), emptyAssistant)
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false) as EmptyTurnRecovery.Plan.NudgeExistingPart
        // Apply the placement the loop would apply.
        val tail = history[plan.tailIndex]
        val part = tail.contentParts[plan.partIndex] as AgentContentPart.Text
        val nudged = part.text + EmptyTurnRecovery.REMINDER
        assertTrue(nudged.startsWith(REAL_CALLBACK))
        assertTrue(nudged.contains("<system-reminder>"))
        assertTrue(nudged.contains("Do not return an empty response"))
    }

    // ── the original case must be unchanged ────────────────────────────────

    @Test
    fun `a tool_result tail still nudges the tool_result (byte-identical intent)`() {
        val history = listOf(
            toolUse("call_1"),
            toolResult("call_1", "ok"),
            emptyAssistant,
        )
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false)
        assertTrue(plan is EmptyTurnRecovery.Plan.NudgeExistingPart)
        plan as EmptyTurnRecovery.Plan.NudgeExistingPart
        assertEquals(1, plan.tailIndex)
        assertEquals("tool_result must win over any text part", true, plan.isToolResult)
    }

    @Test
    fun `tool_result is preferred when the tail carries both a text and a tool_result part`() {
        val history = listOf(
            user(AgentContentPart.Text("here"), AgentContentPart.ToolResult("call_1", "shell_execute", "ok")),
            emptyAssistant,
        )
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false) as EmptyTurnRecovery.Plan.NudgeExistingPart
        assertEquals(true, plan.isToolResult)
    }

    // ── other statement-shaped tails (systematic coverage) ─────────────────

    @Test
    fun `a plain statement-shaped user notice with no question is nudged`() {
        // e.g. a scheduled-task trigger notice, or any auto-injected text.
        val history = listOf(userText("[Scheduled task fired: nightly summary is ready.]"), emptyAssistant)
        assertTrue(EmptyTurnRecovery.plan(history, alreadyFired = false) is EmptyTurnRecovery.Plan.NudgeExistingPart)
    }

    @Test
    fun `an empty-output tool_result is still nudged`() {
        val history = listOf(toolUse("call_1"), toolResult("call_1", ""), emptyAssistant)
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false)
        assertTrue(plan is EmptyTurnRecovery.Plan.NudgeExistingPart)
        assertEquals(true, (plan as EmptyTurnRecovery.Plan.NudgeExistingPart).isToolResult)
    }

    @Test
    fun `an image-only user tail with no appendable part gets a standalone nudge`() {
        val history = listOf(
            user(AgentContentPart.ImageData(byteArrayOf(1, 2, 3), "image/png")),
            emptyAssistant,
        )
        assertEquals(EmptyTurnRecovery.Plan.AppendStandalone, EmptyTurnRecovery.plan(history, alreadyFired = false))
    }

    // ── the guards: must NOT fire ──────────────────────────────────────────

    @Test
    fun `once fired in a run, it never fires again (no loop)`() {
        val history = listOf(userText(REAL_CALLBACK), emptyAssistant)
        assertEquals(EmptyTurnRecovery.Plan.GiveUp, EmptyTurnRecovery.plan(history, alreadyFired = true))
    }

    @Test
    fun `two empty turns back to back give up rather than fabricate a turn`() {
        // Tail (row -2) is an assistant row: the model stopped after its own
        // turn with no user message to nudge. Fabricating one would be wrong.
        val history = listOf(userText("hi"), assistant("some text"), emptyAssistant)
        assertEquals(EmptyTurnRecovery.Plan.GiveUp, EmptyTurnRecovery.plan(history, alreadyFired = false))
    }

    @Test
    fun `a history too short to have a tail gives up`() {
        assertEquals(EmptyTurnRecovery.Plan.GiveUp, EmptyTurnRecovery.plan(listOf(emptyAssistant), alreadyFired = false))
        assertEquals(EmptyTurnRecovery.Plan.GiveUp, EmptyTurnRecovery.plan(emptyList(), alreadyFired = false))
    }

    /**
     * Mirrors the loop's mutation for a [EmptyTurnRecovery.Plan] (AgentLoopEngine
     * runAgentLoop, `when (val plan = recoveryPlan)`): drop the
     * empty assistant row, then append REMINDER to the chosen part or add a
     * standalone user row. Kept verbatim so the one-shot + placement rules can
     * be exercised end to end without a ViewModel.
     */
    private fun applyPlan(history: MutableList<LLMMessage>, plan: EmptyTurnRecovery.Plan) {
        when (plan) {
            is EmptyTurnRecovery.Plan.NudgeExistingPart -> {
                history.removeAt(history.size - 1)
                val tail = history[plan.tailIndex]
                val newParts = tail.contentParts.toMutableList()
                newParts[plan.partIndex] = when (val part = newParts[plan.partIndex]) {
                    is AgentContentPart.ToolResult -> part.copy(content = part.content + EmptyTurnRecovery.REMINDER)
                    is AgentContentPart.Text -> AgentContentPart.Text(part.text + EmptyTurnRecovery.REMINDER)
                    else -> part
                }
                history[plan.tailIndex] = tail.copy(contentParts = newParts)
            }
            is EmptyTurnRecovery.Plan.AppendStandalone -> {
                history.removeAt(history.size - 1)
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = EmptyTurnRecovery.REMINDER,
                        contentParts = listOf(AgentContentPart.Text(EmptyTurnRecovery.REMINDER)),
                    ),
                )
            }
            EmptyTurnRecovery.Plan.GiveUp -> Unit
        }
    }

    private fun reminderCount(history: List<LLMMessage>): Int =
        history.sumOf { m ->
            m.contentParts.sumOf { p ->
                when (p) {
                    is AgentContentPart.Text -> p.text.windowed(EmptyTurnRecovery.REMINDER.length).count { it == EmptyTurnRecovery.REMINDER }
                    is AgentContentPart.ToolResult -> p.content.windowed(EmptyTurnRecovery.REMINDER.length).count { it == EmptyTurnRecovery.REMINDER }
                    else -> 0
                }
            }
        }

    /** Image-only user tail + empty reply: treated as a statement, reminder injected as its own row. */
    @Test
    fun `image-only tail - the standalone reminder is a user text row placed after the image`() {
        val history = mutableListOf(
            userText("what is this?"),
            assistant("a chart"),
            user(AgentContentPart.ImageData(byteArrayOf(9, 9, 9), "image/jpeg")),
            emptyAssistant,
        )
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false)
        assertEquals(EmptyTurnRecovery.Plan.AppendStandalone, plan)
        applyPlan(history, plan)
        assertEquals("empty assistant dropped, reminder row appended", 4, history.size)
        val tail = history.last()
        assertEquals(LLMMessage.Role.USER, tail.role)
        assertTrue(tail.contentParts.single() is AgentContentPart.Text)
        assertTrue("the image row is untouched", history[2].contentParts.single() is AgentContentPart.ImageData)
        assertEquals(1, reminderCount(history))
    }

    /**
     * Callback + real user text in the same tail row. The nudge lands on the
     * LAST text part (the user's own words), never on the callback body — the
     * callback is not what the model is being asked to answer.
     */
    @Test
    fun `callback followed by real user text - the user's text is nudged, not the callback`() {
        val history = mutableListOf(
            user(AgentContentPart.Text(REAL_CALLBACK), AgentContentPart.Text("please summarise it in one line")),
            emptyAssistant,
        )
        val plan = EmptyTurnRecovery.plan(history, alreadyFired = false) as EmptyTurnRecovery.Plan.NudgeExistingPart
        assertEquals("indexOfLast — the user's text, not the callback", 1, plan.partIndex)
        assertFalse(plan.isToolResult)
        applyPlan(history, plan)
        val parts = history[0].contentParts.filterIsInstance<AgentContentPart.Text>()
        assertEquals("callback body left byte-identical", REAL_CALLBACK, parts[0].text)
        assertTrue(parts[1].text.endsWith(EmptyTurnRecovery.REMINDER))
        assertEquals(1, reminderCount(history))
    }

    /**
     * The one-shot ceiling, end to end: reminder injected once; the retry is
     * empty AGAIN; the second decision must be GiveUp (error surfaced, no
     * loop), and the history still holds exactly ONE reminder.
     */
    @Test
    fun `a second empty turn after the reminder gives up - exactly one reminder ever lands`() {
        val history = mutableListOf(userText(REAL_CALLBACK), emptyAssistant)
        var fired = false
        val first = EmptyTurnRecovery.plan(history, alreadyFired = fired)
        assertTrue(first is EmptyTurnRecovery.Plan.NudgeExistingPart)
        fired = true
        applyPlan(history, first)
        assertEquals(1, reminderCount(history))

        // Retry round produced another empty assistant turn.
        history.add(emptyAssistant)
        val second = EmptyTurnRecovery.plan(history, alreadyFired = fired)
        assertEquals("guard must stop the loop", EmptyTurnRecovery.Plan.GiveUp, second)
        applyPlan(history, second)
        assertEquals("no second reminder", 1, reminderCount(history))
        // A tail that would otherwise be nudgeable still gives up once fired.
        assertEquals(EmptyTurnRecovery.Plan.GiveUp,
            EmptyTurnRecovery.plan(listOf(toolUse("c"), toolResult("c", "x"), emptyAssistant), alreadyFired = true))
    }

    /**
     * The reminder is an agentHistory-only mutation: it must not be written to
     * the DB and must not become a UI row. The loop code between the two plan
     * branches and the GiveUp branch performs no repository write and no
     * message-list publish. Source-grep drift guard.
     */
    @Test
    fun `the reminder is neither persisted nor shown - loop branches touch only agentHistory`() {
        val engine = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt")
        val start = engine.indexOf("is EmptyTurnRecovery.Plan.NudgeExistingPart -> {")
        val end = engine.indexOf("EmptyTurnRecovery.Plan.GiveUp -> {", start)
        assertTrue("recovery branches not found in AgentLoopEngine", start > 0 && end > start)
        val branches = engine.substring(start, end)
        for (forbidden in listOf("chatRepository.", "appendMessage(", "_messages.", "updateAssistantMessage(", "insertMessage(")) {
            assertFalse("recovery branch must not call $forbidden", branches.contains(forbidden))
        }
        assertTrue("both branches drop the empty turn from agentHistory", branches.contains("agentHistory.removeAt(host.agentHistory.size - 1)"))
        assertTrue(branches.contains("EmptyTurnRecovery.REMINDER"))
        // Format: a <system-reminder> element, so the UI-side synthetic-row
        // filter (`trimStart().startsWith("<system-reminder>")`) recognises it.
        assertTrue(EmptyTurnRecovery.REMINDER.trimStart().startsWith("<system-reminder>"))
        assertTrue(EmptyTurnRecovery.REMINDER.trimEnd().endsWith("</system-reminder>"))
        // [B-05 deep-audit-1037] The synthetic-row filter moved into the ONE
        // shared predicate (`userPartsJsonHasVisibleContent` in
        // ChatTranscriptRebuild.kt); the ViewModel's visible-row loop asks it
        // instead of re-spelling the check inline.
        val rebuild = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatTranscriptRebuild.kt")
        assertTrue(rebuild.contains("startsWith(\"<system-reminder>\")"))
        val vm = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
        assertTrue(vm.contains("userPartsJsonHasVisibleContent("))
    }

    /** Walks up from user.dir so the test runs from the module dir or the repo root. */
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

    // ── [fix/rv1014-g2] AppendStandalone must bridge the USER tail ─────────

    @Test
    fun appendStandalone_bridgesUserTail_noConsecutiveUser() {
        // History at the AppendStandalone decision: a USER statement tail and
        // the empty assistant turn the loop just appended (last entry).
        val history = mutableListOf(
            userText("hi"),
            assistant("done"),
            userText("please continue"), // USER tail — the plan pre-condition
            emptyAssistant,
        )
        EmptyTurnRecovery.appendStandaloneReminderTurn(history, EmptyTurnRecovery.REMINDER)
        // The empty assistant is dropped; a bridge (empty ASSISTANT) is
        // inserted before the reminder turn, so roles strictly alternate.
        assertEquals(
            listOf(
                LLMMessage.Role.USER,
                LLMMessage.Role.ASSISTANT,
                LLMMessage.Role.USER,
                LLMMessage.Role.ASSISTANT,
                LLMMessage.Role.USER,
            ),
            history.map { it.role },
        )
        // The reminder turn carries the reminder in content AND parts.
        val reminderTurn = history.last()
        assertEquals(EmptyTurnRecovery.REMINDER, reminderTurn.content)
        assertTrue(reminderTurn.contentParts.size == 1)
        assertTrue((reminderTurn.contentParts.first() as AgentContentPart.Text).text == EmptyTurnRecovery.REMINDER)
        // The bridge is a blind-content assistant row (same shape as the
        // interruption bridge).
        val bridge = history[3]
        assertEquals(LLMMessage.Role.ASSISTANT, bridge.role)
        assertTrue(bridge.content.isEmpty())
    }

    @Test
    fun appendStandalone_noBridgeOnAssistantTail() {
        // The function is generic: a non-USER tail gets no bridge (the plan
        // never routes here, but the bridge is only needed for USER tails).
        val history = mutableListOf(userText("hi"), assistant(""))
        EmptyTurnRecovery.appendStandaloneReminderTurn(history, EmptyTurnRecovery.REMINDER)
        assertEquals(
            listOf(LLMMessage.Role.USER, LLMMessage.Role.ASSISTANT, LLMMessage.Role.USER),
            history.map { it.role },
        )
    }

    @Test
    fun appendStandalone_defectDifferential_withoutBridge_yieldsConsecutiveUser() {
        // Documents WHY the bridge matters: the old inline mutation (drop +
        // blind USER append) leaves two consecutive user contents, and Gemini
        // maps messages 1:1 with no consecutive-role merge on the request
        // side. This mirrors the pre-fix behaviour verbatim.
        val history = mutableListOf(
            userText("please continue"),
            emptyAssistant,
        )
        history.removeAt(history.size - 1)
        history.add(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = EmptyTurnRecovery.REMINDER,
                contentParts = listOf(AgentContentPart.Text(EmptyTurnRecovery.REMINDER)),
            ),
        )
        assertEquals(LLMMessage.Role.USER, history[0].role)
        assertEquals(LLMMessage.Role.USER, history[1].role) // two consecutive USER — the defect
    }

    @Test
    fun engine_appendStandalone_arm_delegatesToThePureMutation() {
        // The loop arm must route through the shared mutation (so it stays
        // tested) instead of inlining its own history surgery.
        val engine = readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt")
        assertTrue(
            "AppendStandalone arm must call appendStandaloneReminderTurn",
            engine.contains("EmptyTurnRecovery.appendStandaloneReminderTurn(host.agentHistory, EmptyTurnRecovery.REMINDER)"),
        )
    }
}
