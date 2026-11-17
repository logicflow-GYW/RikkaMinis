package com.rikkaminis.app.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sub-agent dispatch is an app-level capability (rework 2026-11-17):
 * the tool takes a complete task, dispatch runs in a real session, and the
 * skill system is not consulted at all. These tests pin the pure surface —
 * tool shape, prompt shape, and the four outcomes the caller can receive.
 *
 * The Android-bound half (creating the session, blocking for the answer)
 * lives in `executeSpawnAgentTool` and is covered by CI / device tests.
 */
class SubagentSkillTest {

    // ── Tool definition ─────────────────────────────────────────────────

    @Test
    fun `definition requires a complete task and no skill`() {
        val d = SubagentSkill.definition()
        assertEquals("spawn_agent", d.name)
        assertEquals(listOf("tool_title", "task"), d.required)
        assertTrue("task must be a parameter", d.parameters.containsKey("task"))
        assertFalse(
            "dispatch is app-level: no skill parameter may come back",
            d.parameters.containsKey("skill_name"),
        )
        assertFalse(
            "dispatch is app-level: no query parameter may come back",
            d.parameters.containsKey("query"),
        )
    }

    @Test
    fun `definition tells the model the child cannot see this conversation`() {
        val desc = SubagentSkill.definition().description
        assertTrue(desc.contains("self-contained"))
        assertTrue(desc.contains("cannot see this conversation"))
        assertTrue("recursion must stay forbidden in the description", desc.contains("Do NOT dispatch"))
    }

    // ── Dispatch prompt ─────────────────────────────────────────────────

    @Test
    fun `wait timeout is inside the prompt api's accepted range`() {
        // ChatMutationMethods clamps waitTimeout to 1..1800. A zero/negative
        // value would be clamped to 1s (every dispatch times out) and a huge
        // one would be silently cut — both read as "the sub-agent failed".
        assertTrue(
            "WAIT_TIMEOUT_SECONDS must be a real, bounded wait",
            SubagentSkill.WAIT_TIMEOUT_SECONDS in 1..1800,
        )
    }

    @Test
    fun `buildTaskPrompt carries the task verbatim`() {
        val task = "Audit /var/minis/shared/work/x for the F-12 defect.\nReport to /tmp/r.md."
        val p = SubagentSkill.buildTaskPrompt(task)
        assertTrue("the task text must arrive unchanged", p.contains(task))
    }

    @Test
    fun `buildTaskPrompt states the answer-only contract`() {
        val p = SubagentSkill.buildTaskPrompt("do x")
        assertTrue(p.contains("returned verbatim"))
        assertTrue("the child must know the parent sees only its reply", p.contains("cannot see your intermediate"))
    }

    @Test
    fun `buildTaskPrompt forbids recursion`() {
        val p = SubagentSkill.buildTaskPrompt("do x")
        assertTrue(p.contains("Do not dispatch another sub-agent"))
        assertTrue(p.contains("do not create new sessions"))
    }

    @Test
    fun `buildTaskPrompt trims surrounding whitespace and has no trailing blank lines`() {
        val p = SubagentSkill.buildTaskPrompt("\n\n  do x  \n\n")
        assertTrue(p.contains("## Task\ndo x"))
        assertFalse(p.endsWith("\n"))
        assertFalse(p.endsWith(" "))
    }

    // ── Result mapping: four distinguishable outcomes ───────────────────

    @Test
    fun `completed dispatch returns the assistant text as success`() {
        val r = SubagentSkill.resultOf(
            status = "Completed", text = "all done", sessionId = "s1", timedOut = false,
        )
        assertTrue(r.success)
        assertTrue(r.output.contains("all done"))
        assertTrue("the caller must be able to follow up", r.output.contains("session: s1"))
    }

    @Test
    fun `completed dispatch with empty text says so instead of returning blank`() {
        val r = SubagentSkill.resultOf(
            status = "Completed", text = "   ", sessionId = "", timedOut = false,
        )
        assertTrue(r.success)
        assertTrue(r.output.contains("without producing text"))
        assertFalse("no session id means no session footer", r.output.contains("session:"))
    }

    @Test
    fun `timeout is a failure that tells the caller how to recover`() {
        val r = SubagentSkill.resultOf(
            status = "Timeout", text = "", sessionId = "s2", timedOut = true,
            timeoutSeconds = 42,
        )
        assertFalse("a timeout must never read as an answer", r.success)
        assertTrue(r.output.contains("42s"))
        assertTrue(r.output.contains("minis-sessions-cli messages --id s2"))
        assertTrue(r.output.contains("--session s2"))
    }

    @Test
    fun `a refused dispatch is a failure carrying the status and reason`() {
        val r = SubagentSkill.resultOf(
            status = "Error", text = "provider disabled", sessionId = "s3", timedOut = false,
        )
        assertFalse(r.success)
        assertTrue(r.output.contains("status=Error"))
        assertTrue(r.output.contains("provider disabled"))
    }

    @Test
    fun `a refusal with no reason still names the status`() {
        val r = SubagentSkill.resultOf(
            status = "Cancelled", text = "", sessionId = "", timedOut = false,
        )
        assertFalse(r.success)
        assertTrue(r.output.contains("status=Cancelled"))
        assertTrue(r.output.contains("no reason given"))
    }

    // ── Reply reading: a JSON null must never become the text "null" ─────

    @Test
    fun `a json null responseText reads as empty`() {
        // `ChatMutationMethods.prompt` writes `JSONObject.NULL` when the turn
        // produced no text. Android's `optString(name, fallback)` returns the
        // STRING "null" for it (JSON-java returns the fallback) — the JVM json
        // jar cannot reproduce the Android side, which is exactly why the
        // reading must go through isNull rather than optString.
        val reply = JSONObject().put("status", "Completed").put("responseText", JSONObject.NULL)
        assertEquals("", SubagentSkill.replyText(reply))
    }

    @Test
    fun `replyText returns real text and treats an absent field as empty`() {
        assertEquals("done", SubagentSkill.replyText(JSONObject().put("responseText", "done")))
        assertEquals("", SubagentSkill.replyText(JSONObject()))
    }

    @Test
    fun `an empty reply maps to the no-text envelope, never to the word null`() {
        val reply = JSONObject().put("responseText", JSONObject.NULL)
        val r = SubagentSkill.resultOf(
            status = "Completed",
            text = SubagentSkill.replyText(reply),
            sessionId = "s9",
            timedOut = false,
        )
        assertTrue(r.success)
        assertTrue(r.output.contains("without producing text"))
        assertFalse("the literal null must never reach the model", r.output.contains("null"))
    }

    @Test
    fun `a refusal with a json null reason says no reason given`() {
        val reply = JSONObject().put("responseText", JSONObject.NULL)
        val r = SubagentSkill.resultOf(
            status = "Error",
            text = SubagentSkill.replyText(reply),
            sessionId = "",
            timedOut = false,
        )
        assertFalse(r.success)
        assertTrue(r.output.contains("status=Error"))
        assertTrue(r.output.contains("no reason given"))
        assertFalse("a refusal must not read as the reason 'null'", r.output.contains("null"))
    }

    // ── Budget-derived wait ─────────────────────────────────────────────

    @Test
    fun `no budget attached means the plain cap`() {
        assertEquals(900, SubagentSkill.effectiveWaitSeconds(budgetRemainingMs = null, capSeconds = 900))
    }

    @Test
    fun `a healthy budget does not shorten the cap`() {
        assertEquals(900, SubagentSkill.effectiveWaitSeconds(3_600_000L, 900))
        assertEquals(900, SubagentSkill.effectiveWaitSeconds(900_000L, 900))
    }

    @Test
    fun `the wait shrinks to what the deadline has left`() {
        assertEquals(30, SubagentSkill.effectiveWaitSeconds(30_000L, 900))
        assertEquals(5, SubagentSkill.effectiveWaitSeconds(5_999L, 900))
    }

    @Test
    fun `a sub-second remainder still waits a real second`() {
        assertEquals(1, SubagentSkill.effectiveWaitSeconds(0L, 900))
        assertEquals(1, SubagentSkill.effectiveWaitSeconds(999L, 900))
        assertEquals(1, SubagentSkill.effectiveWaitSeconds(-1_000L, 900))
    }

    @Test
    fun `an absurd budget cannot overflow the int conversion`() {
        assertEquals(900, SubagentSkill.effectiveWaitSeconds(Long.MAX_VALUE, 900))
    }

    @Test
    fun `a zero cap stays zero instead of inventing a wait`() {
        assertEquals(0, SubagentSkill.effectiveWaitSeconds(60_000L, 0))
    }

    // ── Skill plumbing kept for SkillRepository ─────────────────────────

    @Test
    fun `extractFrontmatterBlock keeps delimiters and stops at the close marker`() {
        val raw = "---\nname: x\ndescription: y\n---\nbody"
        assertEquals("---\nname: x\ndescription: y\n---", SubagentSkill.extractFrontmatterBlock(raw))
    }

    @Test
    fun `extractFrontmatterBlock is empty for body-only content`() {
        assertEquals("", SubagentSkill.extractFrontmatterBlock("just a body"))
    }

    @Test
    fun `extractFrontmatterBlock is empty when the block never closes`() {
        assertEquals("", SubagentSkill.extractFrontmatterBlock("---\nname: x\nbody without close"))
    }
}
