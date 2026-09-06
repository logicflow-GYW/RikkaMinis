package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleCoding
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingWireFormat
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-worker-thinking-rules-restore] Cross-process custom-rule transport.
 *
 * The worker (`:modelservice`) rebuilds the resolver cache from the request JSON's
 * `thinking_rules` field. These tests pin the full round trip — encode (dispatcher
 * side) → decode/restore (worker side) → resolve — plus the empty-field no-op that
 * keeps requests without custom rules byte-identical to the pre-fix behaviour.
 *
 * Observed live 2026-09-06: a user-authored sensenova clamp rule resolved to the
 * BUILT-IN `deepseek-v4-relay` inside `:modelservice` (cache empty there) while the
 * main-process preview resolved to the custom rule — the cross-process sync class
 * of the knobs H1 (2026-09-05).
 */
class WorkerThinkingRulesRestoreTest {

    @After
    fun tearDown() {
        ThinkingRuleResolver.setAllCustomRules(emptyMap())
    }

    private fun clampRule() = ThinkingRule(
        kind = ThinkingRule.Kind.CUSTOM,
        scope = ThinkingRule.Scope.ModelPattern("deepseek-v4*"),
        wireFormat = ThinkingWireFormat.CustomPath(
            path = "reasoning_effort",
            values = mapOf(ThinkingLevel.HIGH to "xhigh"),
            offValue = "none",
        ),
        label = "sensenova ds clamp",
    )

    private fun ctx(modelId: String, instanceId: String?) = ThinkingResolveContext(
        modelId = modelId,
        instanceId = instanceId,
        supportsReasoning = true,
        declaredEffortValues = null,
        level = ThinkingLevel.HIGH,
        maxTokens = 16384,
        isOpenRouter = false,
        usesUnifiedReasoningEffort = false,
        isMistral = false,
        isDashScope = false,
        isOfficialDeepSeek = false,
        offEffort = null,
    )

    @Test
    fun `encode then restore round-trips a custom path rule`() {
        val rule = clampRule()
        val arr = JSONArray().put(ThinkingRuleCoding.encodeRuleJson(rule))
        val restored = ThinkingRuleResolver.restoreCustomRulesFromJson("inst-1", arr)
        assertEquals(1, restored)
        val rules = ThinkingRuleResolver.customRulesFor("inst-1")
        assertEquals(1, rules.size)
        assertEquals("sensenova ds clamp", rules[0].label)
        val fmt = rules[0].wireFormat
        assertTrue(fmt is ThinkingWireFormat.CustomPath)
        assertEquals("xhigh", (fmt as ThinkingWireFormat.CustomPath).values[ThinkingLevel.HIGH])
        assertEquals("none", fmt.offValue)
    }

    @Test
    fun `restored rule overrides the built-in deepseek relay in resolve`() {
        val arr = JSONArray().put(ThinkingRuleCoding.encodeRuleJson(clampRule()))
        ThinkingRuleResolver.restoreCustomRulesFromJson("inst-2", arr)
        val body = org.json.JSONObject()
        val trace = ThinkingRuleResolver.apply(body, ctx("deepseek-v4-flash", "inst-2"))
        assertEquals("sensenova ds clamp", trace.matchedRuleLabel)
        assertEquals("xhigh", body.optString("reasoning_effort"))
    }

    @Test
    fun `null or absent field is a no-op leaving built-in resolution`() {
        val restored = ThinkingRuleResolver.restoreCustomRulesFromJson("inst-3", null)
        assertEquals(0, restored)
        assertEquals(emptyList<ThinkingRule>(), ThinkingRuleResolver.customRulesFor("inst-3"))
        val body = org.json.JSONObject()
        val trace = ThinkingRuleResolver.apply(body, ctx("deepseek-v4-flash", "inst-3"))
        assertEquals("deepseek-v4-relay", trace.matchedRuleLabel)
    }

    @Test
    fun `decode degrades a corrupt row to null instead of throwing`() {
        val bad = org.json.JSONObject().put("label", "")
        assertNull(ThinkingRuleCoding.decodeRuleJson(bad))
        val arr = JSONArray().put(bad)
        assertEquals(0, ThinkingRuleResolver.restoreCustomRulesFromJson("inst-4", arr))
        assertEquals(emptyList<ThinkingRule>(), ThinkingRuleResolver.customRulesFor("inst-4"))
    }

    @Test
    fun `encode covers scope pattern and echoes`() {
        val rule = clampRule().copy(
            scope = ThinkingRule.Scope.AllModels,
            reasoningEcho = com.openminis.app.provider.thinking.ReasoningEchoPolicy(
                "reasoning_content",
                com.openminis.app.provider.thinking.ReasoningEchoPolicy.Timing.EVERY_TURN,
            ),
        )
        val o = ThinkingRuleCoding.encodeRuleJson(rule)
        assertEquals("allModels", o.optString("scopeKind"))
        val back2 = ThinkingRuleCoding.decodeRuleJson(o)
        assertEquals(
            "reasoning_content",
            (back2?.reasoningEcho as? com.openminis.app.provider.thinking.ReasoningEchoPolicy)?.fieldName,
        )
        val back = ThinkingRuleCoding.decodeRuleJson(o)
        assertNotNull(back)
        assertTrue(back?.scope is ThinkingRule.Scope.AllModels)
    }
}
