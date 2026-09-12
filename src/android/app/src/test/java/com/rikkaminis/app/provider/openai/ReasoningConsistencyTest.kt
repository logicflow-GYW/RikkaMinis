package com.rikkaminis.app.provider.openai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T321-reasoning-consistency] Pins the "upstream billed reasoning but the
 * stream carried none" detector. Field shapes come from real captures:
 *
 *  - Chat Completions (OpenAI): `completion_tokens_details.reasoning_tokens`
 *  - Anthropic-style relays: top-level `reasoning_tokens`
 *  - The 2026-09-12 relay case: billed 2599 / 415 while every reasoning
 *    field arrived empty and the thinking was pasted into `content`.
 *
 * `reasoningLen` counts CHARS streamed through the reasoning fields (the same
 * counter the [T321] final tally logs), so the test uses realistic magnitudes.
 */
class ReasoningConsistencyTest {

    @Test
    fun `billed reasoning with zero streamed content is reported`() {
        // The 2026-09-12 relay case, verbatim: 2599 billed, 0 reasoning chars.
        val usage = JSONObject()
            .put("completion_tokens", 6287)
            .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 2599L))
        assertEquals(2599L, ReasoningConsistency.missingReasoningContent(usage, 0))
    }

    @Test
    fun `billed reasoning with streamed content is not flagged`() {
        val usage = JSONObject()
            .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 2599L))
        assertEquals(0L, ReasoningConsistency.missingReasoningContent(usage, 512))
        // Even a single char means the reasoning path works — not our bug.
        assertEquals(0L, ReasoningConsistency.missingReasoningContent(usage, 1))
    }

    @Test
    fun `top-level reasoning_tokens is detected too`() {
        // Anthropic-style relays put the count at the top level.
        val usage = JSONObject().put("reasoning_tokens", 415L)
        assertEquals(415L, ReasoningConsistency.missingReasoningContent(usage, 0))
    }

    @Test
    fun `camelCase fallback reasoningTokens is detected`() {
        val usage = JSONObject().put("reasoningTokens", 90L)
        assertEquals(90L, ReasoningConsistency.missingReasoningContent(usage, 0))
    }

    @Test
    fun `null usage or missing reasoning fields yield zero`() {
        assertEquals(0L, ReasoningConsistency.missingReasoningContent(null, 0))
        // A plain completion with no thinking billed and none streamed: normal.
        val usage = JSONObject().put("output_tokens", 5L)
        assertEquals(0L, ReasoningConsistency.missingReasoningContent(usage, 0))
        // Missing usage entirely: nothing to compare, stay quiet.
        assertEquals(0L, ReasoningConsistency.missingReasoningContent(JSONObject(), 0))
    }

    @Test
    fun `nested and top-level counts are maxed not summed`() {
        // Some relays emit both; double-counting would exaggerate the report.
        val usage = JSONObject()
            .put("reasoning_tokens", 10L)
            .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 200L))
        assertEquals(200L, ReasoningConsistency.billedReasoningTokens(usage))
        assertTrue(ReasoningConsistency.missingReasoningContent(usage, 0) > 0)
    }

    @Test
    fun `billedReasoningTokens on null usage is zero`() {
        assertEquals(0L, ReasoningConsistency.billedReasoningTokens(null))
        assertNotNull(ReasoningConsistency.billedReasoningTokens(JSONObject().put("x", 1)))
    }
}
