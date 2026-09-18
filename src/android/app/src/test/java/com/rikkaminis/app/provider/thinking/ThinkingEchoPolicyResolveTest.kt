package com.rikkaminis.app.provider.thinking

import com.rikkaminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-deepseek-v4-thinking-echo] The rule table's echo requirement must be reachable
 * INDEPENDENT of the local thinking level.
 *
 * `ThinkingRuleResolver.apply()` bails out before matching when the level is AUTO (AUTO
 * means "send no thinking control"), and the provider's relay-host table returns before
 * the resolver runs at all — so reading the requirement off the trace alone would have
 * left the 2026-09-18 400 (`deepseek-v4-flash` on a relay) reproducible with the default
 * Auto setting. `echoPolicyFor` exists for exactly that gap; these tests pin it.
 */
class ThinkingEchoPolicyResolveTest {

    @After
    fun tearDown() {
        ThinkingRuleResolver.setAllCustomRules(emptyMap())
    }

    private fun ctx(
        modelId: String,
        level: ThinkingLevel,
        instanceId: String? = null,
        isOfficialDeepSeek: Boolean = false,
        unifiedGateway: Boolean = false,
        isMistral: Boolean = false,
    ) = ThinkingResolveContext(
        modelId = modelId,
        instanceId = instanceId,
        supportsReasoning = null,
        declaredEffortValues = null,
        level = level,
        maxTokens = 8192,
        isOpenRouter = unifiedGateway,
        usesUnifiedReasoningEffort = unifiedGateway,
        isMistral = isMistral,
        isDashScope = false,
        isOfficialDeepSeek = isOfficialDeepSeek,
        offEffort = null,
    )

    @Test
    fun `deepseek-v4 declares the tool-call echo on every level`() {
        for (level in listOf(ThinkingLevel.OFF, ThinkingLevel.AUTO, ThinkingLevel.HIGH)) {
            val policy = ThinkingRuleResolver.echoPolicyFor(ctx("deepseek-v4-flash", level))
            assertEquals("level=$level", ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY, policy?.timing)
        }
    }

    @Test
    fun `official deepseek endpoint declares it too - the vendor rule, not the relay`() {
        val policy = ThinkingRuleResolver.echoPolicyFor(
            ctx("deepseek-v4-pro", ThinkingLevel.AUTO, isOfficialDeepSeek = true),
        )
        assertEquals(ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY, policy?.timing)
    }

    @Test
    fun `models with no echo rule stay null - legacy behaviour untouched`() {
        assertNull(ThinkingRuleResolver.echoPolicyFor(ctx("glm-5.3-flash", ThinkingLevel.HIGH)))
        assertNull(ThinkingRuleResolver.echoPolicyFor(ctx("claude-opus-4-6", ThinkingLevel.HIGH)))
    }

    @Test
    fun `a unified gateway shadowing the wire rule does not shadow the echo requirement`() {
        // Ark/Azure/Venice/OpenRouter claim every model with their own ReasoningEffort
        // rule (AllModels), which legitimately wins for the WIRE shape. The echo is a
        // property of the MODEL, so it must still be found.
        val policy = ThinkingRuleResolver.echoPolicyFor(
            ctx("deepseek-v4-flash", ThinkingLevel.HIGH, unifiedGateway = true),
        )
        assertEquals(ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY, policy?.timing)
    }

    @Test
    fun `mistral keeps its NEVER - the closed schema still wins first`() {
        val policy = ThinkingRuleResolver.echoPolicyFor(
            ctx("mistral-large", ThinkingLevel.HIGH, isMistral = true),
        )
        assertEquals(ReasoningEchoPolicy.Timing.NEVER, policy?.timing)
    }

    @Test
    fun `a custom rule outranks the built-in (first match wins, same as apply)`() {
        val instance = "custom-instance"
        ThinkingRuleResolver.setAllCustomRules(
            mapOf(
                instance to listOf(
                    ThinkingRule(
                        kind = ThinkingRule.Kind.CUSTOM,
                        scope = ThinkingRule.Scope.ModelPattern("deepseek-v4*"),
                        wireFormat = null,
                        label = "prohibit echo",
                        reasoningEcho = ReasoningEchoPolicy(
                            "reasoning_content",
                            ReasoningEchoPolicy.Timing.NEVER,
                        ),
                    ),
                ),
            ),
        )
        val policy = ThinkingRuleResolver.echoPolicyFor(ctx("deepseek-v4-flash", ThinkingLevel.HIGH, instance))
        assertEquals(ReasoningEchoPolicy.Timing.NEVER, policy?.timing)
        // Other instances are untouched by the custom rule.
        assertEquals(
            ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY,
            ThinkingRuleResolver.echoPolicyFor(ctx("deepseek-v4-flash", ThinkingLevel.HIGH))?.timing,
        )
    }

    @Test
    fun `the trace carries the same policy on the non-AUTO path`() {
        val c = ctx("deepseek-v4-flash", ThinkingLevel.HIGH)
        val trace = ThinkingRuleResolver.apply(JSONObject(), c)
        assertEquals(ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY, trace.reasoningEcho?.timing)
    }

    @Test
    fun `AUTO leaves the trace empty but not the echo lookup`() {
        // apply() short-circuits on AUTO (no wire control), so the trace cannot carry the
        // requirement — this is the asymmetry echoPolicyFor was added for.
        val c = ctx("deepseek-v4-flash", ThinkingLevel.AUTO)
        val trace = ThinkingRuleResolver.apply(JSONObject(), c)
        assertNull(trace.reasoningEcho)
        assertEquals(
            ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY,
            ThinkingRuleResolver.echoPolicyFor(c)?.timing,
        )
    }
}
