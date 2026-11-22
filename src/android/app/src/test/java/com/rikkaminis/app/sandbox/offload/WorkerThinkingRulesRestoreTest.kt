package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.provider.thinking.ReasoningEchoPolicy
import com.rikkaminis.app.provider.thinking.RequestThinkingRules
import com.rikkaminis.app.provider.thinking.ThinkingRule
import com.rikkaminis.app.provider.thinking.ThinkingRuleCoding
import com.rikkaminis.app.provider.thinking.ThinkingRuleResolver
import com.rikkaminis.app.provider.thinking.ThinkingResolveContext
import com.rikkaminis.app.provider.thinking.ThinkingWireFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/request-rules-1120] Request-local custom-rule transport.
 *
 * The worker (`:modelservice`) decodes `thinking_rules` into a snapshot owned by ONE
 * request. It must never publish into [ThinkingRuleResolver]'s process-wide cache: a
 * second request on the same worker would then overwrite the first one's rules before
 * its provider builds the wire body (surgery audit D01).
 *
 * These tests pin the contract itself — interleaved A(old)/B(new) requests, instance
 * isolation, the old request WITHOUT the field (explicit empty snapshot, never an
 * inherited predecessor), and wire/echo coming from the same snapshot.
 */
class WorkerThinkingRulesRestoreTest {

    @After
    fun tearDown() {
        ThinkingRuleResolver.setAllCustomRules(emptyMap())
    }

    private fun clampRule(
        pattern: String = "deepseek-v4*",
        path: String = "reasoning_effort",
        label: String = "sensenova ds clamp",
        echo: ReasoningEchoPolicy? = null,
    ) = ThinkingRule(
        kind = ThinkingRule.Kind.CUSTOM,
        scope = ThinkingRule.Scope.ModelPattern(pattern),
        wireFormat = ThinkingWireFormat.CustomPath(
            path = path,
            values = mapOf(ThinkingLevel.HIGH to "xhigh"),
            offValue = "none",
        ),
        reasoningEcho = echo,
        label = label,
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

    private fun wire(rules: List<ThinkingRule>) = JSONArray().apply {
        rules.forEach { put(ThinkingRuleCoding.encodeRuleJson(it)) }
    }

    @Test
    fun `encode then snapshot round-trips a custom path rule`() {
        val snapshot = RequestThinkingRules.fromJson(wire(listOf(clampRule())))
        assertEquals(1, snapshot.size)
        val body = JSONObject()
        val trace = snapshot.apply(body, ctx("deepseek-v4-flash", "inst-1"))
        assertEquals("sensenova ds clamp", trace.matchedRuleLabel)
        assertEquals("xhigh", body.optString("reasoning_effort"))
    }

    @Test
    fun `interleaved requests on one instance keep their own snapshot`() {
        // A = the OLD rules the first dispatch carried; B = the NEW rules a later
        // dispatch carries. Both are decoded before either provider builds a body,
        // which is exactly the worker sequence that used to overwrite the cache.
        val a = RequestThinkingRules.fromJson(wire(listOf(clampRule(path = "old_toggle", label = "A-old"))))
        val b = RequestThinkingRules.fromJson(wire(listOf(clampRule(path = "new_toggle", label = "B-new"))))

        val bodyA = JSONObject()
        val traceA = a.apply(bodyA, ctx("deepseek-v4-flash", "same-instance"))
        val bodyB = JSONObject()
        b.apply(bodyB, ctx("deepseek-v4-flash", "same-instance"))

        assertEquals("A-old", traceA.matchedRuleLabel)
        assertTrue("A must use its own old rules", bodyA.has("old_toggle"))
        assertFalse("A must not pick up B's rules", bodyA.has("new_toggle"))
        assertTrue("B must use its own new rules", bodyB.has("new_toggle"))
        assertFalse(bodyB.has("old_toggle"))
    }

    @Test
    fun `different instances stay isolated through their snapshots`() {
        val one = RequestThinkingRules.fromJson(wire(listOf(clampRule(pattern = "deepseek-v4*", label = "one"))))
        val two = RequestThinkingRules.fromJson(wire(listOf(clampRule(pattern = "qwen*", label = "two"))))
        val oneBody = JSONObject()
        val twoBody = JSONObject()
        assertEquals("one", one.apply(oneBody, ctx("deepseek-v4-flash", "inst-one")).matchedRuleLabel)
        assertEquals("two", two.apply(twoBody, ctx("qwen3.8-max", "inst-two")).matchedRuleLabel)
        // A snapshot captured for another model still resolves built-ins, not the sibling's rule.
        val otherBody = JSONObject()
        val otherTrace = one.apply(otherBody, ctx("qwen3.8-max", "inst-one"))
        assertNotEquals("two", otherTrace.matchedRuleLabel)
    }

    @Test
    fun `a request without the field carries an explicit EMPTY snapshot`() {
        // No inheritance: a null field means "this request has no custom rules",
        // not "keep whatever the previous request restored".
        val legacy = RequestThinkingRules.fromJson(null)
        assertEquals(0, legacy.size)
        val body = JSONObject()
        val trace = legacy.apply(body, ctx("deepseek-v4-flash", "inst-3"))
        // Built-in vendor rules still apply (that is the whole point of an empty
        // custom list); what must NOT appear is any CUSTOM rule.
        assertEquals("deepseek-v4-relay", trace.matchedRuleLabel)
        assertFalse("no custom rule may leak in", body.has("old_toggle"))
        // The BUILT-IN deepseek echo policy still applies (an empty CUSTOM list is
        // not "no rules at all"); what must be absent is a CUSTOM echo.
        val echo = legacy.echoPolicyFor(ctx("deepseek-v4-flash", "inst-3"))
        assertEquals("reasoning_content", echo?.fieldName)
    }

    @Test
    fun `corrupt rows degrade instead of throwing`() {
        val bad = JSONObject().put("label", "")
        assertEquals(0, RequestThinkingRules.fromJson(JSONArray().put(bad)).size)
    }

    @Test
    fun `wire and echo come from the SAME snapshot`() {
        val rules = RequestThinkingRules.fromJson(
            wire(
                listOf(
                    clampRule(
                        echo = ReasoningEchoPolicy(
                            "reasoning_content",
                            ReasoningEchoPolicy.Timing.AFTER_TOOL_USE_ONLY,
                        ),
                    ),
                ),
            ),
        )
        // A different rule set published in the process cache must not leak in.
        ThinkingRuleResolver.setCustomRules("inst-4", listOf(clampRule(label = "cache-rule")))
        val body = JSONObject()
        val trace = rules.apply(body, ctx("deepseek-v4-flash", "inst-4"))
        assertEquals("sensenova ds clamp", trace.matchedRuleLabel)
        assertEquals("xhigh", body.optString("reasoning_effort"))
        assertEquals(
            "reasoning_content",
            rules.echoPolicyFor(ctx("deepseek-v4-flash", "inst-4"))?.fieldName,
        )
    }

    @Test
    fun `snapshot copies the mutable tier map`() {
        val mutable = mutableMapOf(ThinkingLevel.HIGH to "xhigh")
        val rule = ThinkingRule(
            kind = ThinkingRule.Kind.CUSTOM,
            scope = ThinkingRule.Scope.AllModels,
            wireFormat = ThinkingWireFormat.CustomPath("reasoning_effort", mutable, "none"),
            label = "copied",
        )
        val snapshot = RequestThinkingRules.capture(listOf(rule))
        mutable[ThinkingLevel.HIGH] = "minimal"
        val body = JSONObject()
        snapshot.apply(body, ctx("anything", "inst-5"))
        assertEquals("xhigh", body.optString("reasoning_effort"))
    }

    @Test
    fun `dispatcher encoding still round-trips echo policy`() {
        val o = ThinkingRuleCoding.encodeRuleJson(
            clampRule(
                echo = ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.EVERY_TURN),
            ),
        )
        assertEquals("allModels".let { o.optString("scopeKind") }, o.optString("scopeKind"))
        val back = ThinkingRuleCoding.decodeRuleJson(o)
        assertTrue(back != null)
        assertEquals(
            "reasoning_content",
            (back?.reasoningEcho as? ReasoningEchoPolicy)?.fieldName,
        )
    }
}
