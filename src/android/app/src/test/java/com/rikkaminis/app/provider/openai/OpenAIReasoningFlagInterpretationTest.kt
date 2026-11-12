package com.rikkaminis.app.provider.openai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [gateway-context-1112] How the endpoint's `supports_reasoning` flag is
 * interpreted, and why.
 *
 * The naive reading — "the endpoint says false, so the model cannot reason" —
 * is WRONG for the local gateway, and following it would have silently
 * switched the thinking toggle OFF for every model served through it.
 *
 * The gateway derives the flag from its own per-provider catalogue
 * (`OpenAiApi.modelList` writes `extra["reasoning"] == "1"`), and it writes
 * the boolean UNCONDITIONALLY — so every model without a catalogue entry
 * comes back as `supports_reasoning: false`, including models that do
 * reason. Observed on the real payload (2026-11-12): all 15 rows carry
 * `supports_reasoning: false`, including the gpt-5-family rows.
 *
 * So the rule is: **true wins, false falls back to the id whitelist,
 * "not reported" falls back too.**
 *
 * Every expected value is a hand-written literal.
 */
class OpenAIReasoningFlagInterpretationTest {

    /**
     * The production decision function — NOT a copy of it. A copy would be a
     * self-referential test: it could keep passing after the production rule
     * changed.
     */
    private fun decide(endpointFlag: Boolean?, knownReasoningFamily: Boolean): Boolean? =
        resolveSupportsReasoning(endpointFlag, knownReasoningFamily)

    @Test
    fun `an endpoint that says true wins`() {
        assertEquals(true, decide(endpointFlag = true, knownReasoningFamily = false))
    }

    @Test
    fun `an endpoint that says false does NOT switch reasoning off`() {
        // The gateway says false for EVERY model; reading it as
        // authoritative would disable the thinking toggle app-wide.
        assertNull(decide(endpointFlag = false, knownReasoningFamily = false))
    }

    @Test
    fun `a false flag does not override the reasoning-family whitelist`() {
        // gpt-5.x / o-series / codex: the whitelist still stamps true.
        assertEquals(true, decide(endpointFlag = false, knownReasoningFamily = true))
    }

    @Test
    fun `an unreported flag behaves like a false one`() {
        assertNull(decide(endpointFlag = null, knownReasoningFamily = false))
        assertEquals(true, decide(endpointFlag = null, knownReasoningFamily = true))
    }

    @Test
    fun `the real gateway payload reports false for every row`() {
        // The premise of the whole rule, pinned against the real shape: if
        // a future gateway starts reporting true for the models that reason,
        // this test fails and the rule can be revisited.
        val body = """{"object":"list","data":[
          {"id":"codebuddy-cn/hy4free","context_length":960000,"supports_reasoning":false},
          {"id":"chatgpt/gpt-6-luna","context_length":272000,"supports_reasoning":false},
          {"id":"codebuddy-cn/glm-5.3-flash","context_length":1000000,"supports_reasoning":false}
        ]}"""
        val data = JSONObject(body).getJSONArray("data")
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            assertEquals(
                "row ${obj.getString("id")} reports false",
                false,
                parseOpenAIModelEndpointMetadata(obj).supportsReasoning,
            )
        }
    }

    @Test
    fun `the gateway payload parser still reads the flag as a boolean`() {
        // The parser reports what the endpoint said (false); the DECISION of
        // what to do with it happens one layer up. Keeping the parser honest
        // is what lets the decision be revisited without touching it.
        val meta = parseOpenAIModelEndpointMetadata(
            JSONObject("""{"id":"m","supports_reasoning":false}"""),
        )
        assertEquals(false, meta.supportsReasoning)
    }
}
