package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * [T-thinking-auto-level] AUTO ("let the vendor decide") semantics: NO thinking
 * control field may be emitted on any resolver path, custom rules included.
 */
class ThinkingAutoLevelTest {

    @After
    fun tearDown() {
        ThinkingRuleResolver.setAllCustomRules(emptyMap())
    }

    private fun ctx(
        modelId: String = "some-model",
        instanceId: String? = null,
        level: ThinkingLevel = ThinkingLevel.AUTO,
        isMistral: Boolean = false,
        isDashScope: Boolean = false,
        isOpenRouter: Boolean = false,
        isOfficialDeepSeek: Boolean = false,
        usesUnifiedReasoningEffort: Boolean = false,
    ) = ThinkingResolveContext(
        modelId = modelId,
        instanceId = instanceId,
        supportsReasoning = true,
        declaredEffortValues = null,
        declaresNoEffortTiers = false,
        level = level,
        maxTokens = 4096,
        isOpenRouter = isOpenRouter,
        usesUnifiedReasoningEffort = usesUnifiedReasoningEffort,
        isMistral = isMistral,
        isDashScope = isDashScope,
        isXAI = false,
        isOfficialDeepSeek = isOfficialDeepSeek,
        offEffort = null,
    )

    private fun resolve(c: ThinkingResolveContext): JSONObject {
        val body = JSONObject()
        ThinkingRuleResolver.apply(body, c)
        return body
    }

    @Test
    fun `auto emits no thinking field on the generic path`() {
        val body = resolve(ctx())
        assertFalse(body.has("reasoning_effort"))
        assertFalse(body.has("thinking"))
        assertFalse(body.has("enable_thinking"))
        assertFalse(body.has("extra_body"))
        assertFalse(body.has("reasoning"))
    }

    @Test
    fun `auto emits nothing even where vendor rules would have matched`() {
        // deepseek-v4-official / qwen-dashscope / openrouter all have built-in
        // rules that fire for these shapes at any enabled level — AUTO must
        // short-circuit BEFORE the rule table.
        val body = resolve(ctx("deepseek-v4-pro", isOfficialDeepSeek = true))
        assertFalse(body.has("thinking"))
        assertFalse(body.has("reasoning_effort"))

        val qwen = resolve(ctx("qwen3-32b", isDashScope = true))
        assertFalse(qwen.has("enable_thinking"))
        assertFalse(qwen.has("thinking_budget"))

        val or = resolve(ctx("anthropic/claude-sonnet-4-6", isOpenRouter = true))
        assertFalse(or.has("reasoning"))
    }

    @Test
    fun `custom rules do not override auto`() {
        ThinkingRuleResolver.setCustomRules(
            "inst-A",
            listOf(
                ThinkingRule(
                    kind = ThinkingRule.Kind.CUSTOM,
                    scope = ThinkingRule.Scope.AllModels,
                    wireFormat = ThinkingWireFormat.ReasoningEffort("none"),
                    label = "force-effort",
                ),
            ),
        )
        val body = resolve(ctx(instanceId = "inst-A"))
        assertFalse("a custom rule must not resurrect a thinking field under AUTO", body.has("reasoning_effort"))
    }

    @Test
    fun `auto is still counted as enabled`() {
        assertFalse(ThinkingLevel.AUTO == ThinkingLevel.OFF)
        assertTrue(ThinkingLevel.AUTO.isEnabled)
    }

    @Test
    fun `the auto trace is identifiable`() {
        val body = JSONObject()
        val trace = ThinkingRuleResolver.apply(body, ctx())
        assertEquals("auto-omit", trace.formatSource)
        assertEquals("auto", trace.matchedRuleLabel)
        assertEquals(emptyList<String>(), trace.emittedKeys)
    }

    @Test
    fun `auto empty keys in trace match no key emission`() {
        val body = JSONObject()
        val trace = ThinkingRuleResolver.apply(body, ctx("qwen3-32b", isDashScope = true))
        assertTrue(trace.emittedKeys.isEmpty())
        assertTrue(body.length() == 0)
    }
}
