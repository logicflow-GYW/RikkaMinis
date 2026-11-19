package com.rikkaminis.app.provider.gemini

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FULLSCAN-1119 F-11] `thinkingConfig.thinkingBudget` comes from the
 * per-family ladder in `ThinkingRuleResolver` (2.5 Pro 128-32768, 2.5 Flash
 * 0-16384), which knows the MODEL's ceiling but not THIS request's
 * `maxOutputTokens`. A small output budget therefore shipped a bigger thinking
 * budget: Gemini answers 400, and where the server accepts it the reply body
 * arrives empty because thinking consumed the whole allowance.
 *
 * Value tables only, driven through the real [clampGeminiThinkingBudget] on
 * real `org.json` objects. The wiring anchors live in
 * `GeminiThinkingBudgetWiringTest`; what the provider actually sends is in
 * `GeminiThinkingBudgetWireTest`.
 */
class GeminiThinkingBudgetClampTest {

    private fun config(budget: Int?, includeThoughts: Boolean = true): JSONObject =
        JSONObject().apply {
            put("thinkingBudget", budget?.toString() ?: JSONObject.NULL)
            if (includeThoughts) put("includeThoughts", true)
        }

    // -- value tables ---------------------------------------------------------

    @Test
    fun `a budget above the output budget is pressed down to it`() {
        // 2.5 Pro HIGH asks for 16384 while the request only allows 1024 out.
        val clamped = clampGeminiThinkingBudget(config(16384), 1024)!!
        assertEquals(1024, clamped.getInt("thinkingBudget"))
        assertTrue("includeThoughts survives the clamp", clamped.getBoolean("includeThoughts"))
    }

    @Test
    fun `a budget at the boundary is left alone`() {
        val clamped = clampGeminiThinkingBudget(config(1024), 1024)!!
        assertEquals(1024, clamped.getInt("thinkingBudget"))
    }

    @Test
    fun `a budget under the output budget is untouched`() {
        val clamped = clampGeminiThinkingBudget(config(2048), 1_000_000)!!
        assertEquals(2048, clamped.getInt("thinkingBudget"))
    }

    @Test
    fun `a zero budget stays zero`() {
        // 2.5 Flash OFF sends thinkingBudget=0 (thinking disabled); a clamp
        // must not turn "off" into something.
        val clamped = clampGeminiThinkingBudget(config(0), 512)!!
        assertEquals(0, clamped.getInt("thinkingBudget"))
    }

    @Test
    fun `no thinking config stays absent`() {
        assertNull(clampGeminiThinkingBudget(null, 1024))
    }

    @Test
    fun `a level-shaped 3_x config is not reinterpreted as a token count`() {
        val levelConfig = JSONObject().apply { put("thinkingLevel", "high") }
        val clamped = clampGeminiThinkingBudget(levelConfig, 128)!!
        assertEquals("high", clamped.getString("thinkingLevel"))
        assertFalse("no budget must be invented", clamped.has("thinkingBudget"))
    }

    @Test
    fun `the input object is never mutated`() {
        val input = config(16384)
        val clamped = clampGeminiThinkingBudget(input, 1024)!!
        assertEquals("the resolver's object keeps its value", 16384, input.getInt("thinkingBudget"))
        assertEquals(1024, clamped.getInt("thinkingBudget"))
        // Unchanged configs are returned as-is (no pointless copy).
        val untouched = config(2048)
        assertSame(untouched, clampGeminiThinkingBudget(untouched, 4096))
    }

    @Test
    fun `a negative output budget cannot produce a negative thinking budget`() {
        val clamped = clampGeminiThinkingBudget(config(2048), -5)!!
        assertEquals(0, clamped.getInt("thinkingBudget"))
    }
}
