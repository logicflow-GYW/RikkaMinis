package com.rikkaminis.app.provider.openai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/responses-replay-item-id] Real-device repro (2026-11-05 00:47 / 00:49 /
 * 00:54): a /responses tool round on an Azure multi-resource relay failed with
 * `400 The requested item was created under a different Azure OpenAI resource`,
 * and retryFromMessage re-sent the same poisoned history and failed identically.
 * The cause was the replayed `function_call` carrying the server-assigned item
 * id; rikkahub replays the same item on the same relay without it.
 *
 * Expected values are hand-written literals (evidence-discipline): nothing in
 * this class is read back out of the helper under test.
 */
class OpenAIResponsesToolReplayTest {

    private val callId = "call_9f5bf4357cf993e4"
    private val toolName = "shell_execute"
    private val args = "{\"command\":\"pwd\"}"

    @Test
    fun `replay emits exactly the four required protocol fields`() {
        val out = OpenAIResponsesToolReplay.functionCall(callId, toolName, args)
        assertEquals(
            setOf("type", "call_id", "name", "arguments"),
            out.keySet().toSet(),
        )
    }

    @Test
    fun `replay never carries a server-assigned item id`() {
        val out = OpenAIResponsesToolReplay.functionCall(callId, toolName, args)
        assertFalse(
            "an item id makes stateless relays look the item up by id and 400",
            out.has("id"),
        )
    }

    @Test
    fun `field values are exactly the arguments passed in`() {
        val out = OpenAIResponsesToolReplay.functionCall(callId, toolName, args)
        assertEquals("function_call", out.getString("type"))
        assertEquals("call_9f5bf4357cf993e4", out.getString("call_id"))
        assertEquals("shell_execute", out.getString("name"))
        assertEquals("{\"command\":\"pwd\"}", out.getString("arguments"))
    }

    /**
     * A green unit test on the helper alone does not prove the production
     * /responses builder actually uses it. This pins the one call site and
     * keeps the removed synthetic-id fallback from quietly coming back.
     */
    @Test
    fun `production replay site delegates to the helper and has no synthetic id fallback`() {
        val text = openAIProviderSource().readText()
        assertEquals(
            "the /responses assistant tool-use replay must go through the helper",
            1,
            Regex("OpenAIResponsesToolReplay\\.functionCall\\(").findAll(text).count(),
        )
        assertFalse(
            "the synthetic item-id fallback is the defect being removed",
            text.contains("fc_syn_"),
        )
    }

    /**
     * [review-2026-11-05] The defect's blast radius is the ASSEMBLY, not the helper:
     * `split("call|fc") -> cap(64) -> helper`. Both replayed items must address the
     * SAME call_id, otherwise the relay answers `No tool output found for call_id`
     * instead of the item-id 400 (same symptom class, different line).
     */
    @Test
    fun `combined history id is split so both replayed items share one call id`() {
        val historical = "call_9f5bf4357cf993e4|fc_0a1b2c3d4e5f"
        // Same two rules the production assembly applies: split on '|', cap at 64.
        val callHalf = historical.substringBefore('|').take(64)
        val assistant = OpenAIResponsesToolReplay.functionCall(callHalf, toolName, args)
        val output = JSONObject().apply {
            put("type", "function_call_output")
            put("call_id", callHalf)
            put("output", "ok")
        }
        assertEquals("call_9f5bf4357cf993e4", assistant.getString("call_id"))
        assertEquals(assistant.getString("call_id"), output.getString("call_id"))
        assertFalse(assistant.getString("call_id").contains('|'))
        assertFalse(assistant.has("id"))
    }

    @Test
    fun `over-long call ids are capped symmetrically on both items`() {
        val historical = "call_" + "a".repeat(90) + "|fc_zzz"
        val callHalf = historical.substringBefore('|').take(64)
        assertEquals(64, callHalf.length)
        val assistant = OpenAIResponsesToolReplay.functionCall(callHalf, toolName, args)
        val output = JSONObject().apply {
            put("type", "function_call_output")
            put("call_id", callHalf)
            put("output", "x")
        }
        assertEquals("call_" + "a".repeat(59), assistant.getString("call_id"))
        assertEquals(assistant.getString("call_id"), output.getString("call_id"))
    }

    /**
     * Pins the production ASSEMBLY, not just the helper: the assistant replay must
     * split the combined id and cap the call half before handing it over, and the
     * tool-output item must cap the same half. Without this, someone could keep the
     * helper clean while wiring the raw `call|fc` string into it.
     */
    @Test
    fun `production assembly splits the combined id and caps both call_id sites`() {
        val body = responsesBuilderSource()
        val assistantSite = Regex(
            "splitResponsesAPIIds\\(tu\\.id\\)[\\s\\S]{0,700}?" +
                "OpenAIResponsesToolReplay\\.functionCall\\([\\s\\S]{0,120}?" +
                "capResponsesId\\(callId\\)",
        ).find(body)
        assertTrue(
            "assistant replay must split(call|fc) -> helper(cap(call))",
            assistantSite != null,
        )
        assertTrue(
            "function_call_output must cap the same call half",
            body.contains("put(\"call_id\", capResponsesId(callId))"),
        )
    }

    /**
     * The strongest form of the original defect: keep calling the helper, then bolt
     * an item id onto the result (`.apply { put("id", …) }`). A shape test would pass;
     * this one scans the WHOLE `/responses` body builder, so any `put("id")` anywhere
     * inside it turns red. `store:false` means a replayed item id references nothing.
     */
    @Test
    fun `responses body builder never emits an id key`() {
        val body = responsesBuilderSource()
        assertFalse(
            "the /responses builder must not emit any item id (see OpenAIResponsesToolReplay)",
            body.contains("put(\"id\""),
        )
    }

    /** Slice of `buildResponsesAPIBody` up to the next private helper. */
    private fun responsesBuilderSource(): String {
        val text = openAIProviderSource().readText()
        val start = text.indexOf("private fun buildResponsesAPIBody(")
        val end = text.indexOf("private fun combineResponsesAPIIds(", start)
        assertTrue("buildResponsesAPIBody not found", start >= 0 && end > start)
        return text.substring(start, end)
    }

    /** Tolerate both the gradle module cwd and a repo-root cwd in the JVM probe. */
    private fun openAIProviderSource(): File {
        val rel = "src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt"
        val direct = File(rel)
        if (direct.isFile) return direct
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val nested = File(dir, "src/android/app/$rel")
            if (nested.isFile) return nested
            dir = dir.parentFile
        }
        error("OpenAIProvider.kt not found from cwd=${File(".").absolutePath}")
    }
}
