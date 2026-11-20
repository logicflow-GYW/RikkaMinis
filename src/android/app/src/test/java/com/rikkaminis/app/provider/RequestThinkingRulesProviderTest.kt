package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ThinkingLevel
import com.rikkaminis.app.provider.openai.OpenAIProvider
import com.rikkaminis.app.provider.thinking.ReasoningEchoPolicy
import com.rikkaminis.app.provider.thinking.RequestThinkingRules
import com.rikkaminis.app.provider.thinking.ThinkingRule
import com.rikkaminis.app.provider.thinking.ThinkingRuleCoding
import com.rikkaminis.app.provider.thinking.ThinkingRuleResolver
import com.rikkaminis.app.provider.thinking.ThinkingWireFormat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [fix/request-rules-1120] The PROVIDER-level half of the request-snapshot contract.
 *
 * [com.rikkaminis.app.sandbox.offload.WorkerThinkingRulesRestoreTest] pins the snapshot
 * type itself. This file proves the wiring: an OpenAIProvider built for a worker request
 * must emit BOTH its thinking fields and its reasoning echo from the snapshot it was
 * constructed with — even when the process-wide cache (the UI's source of truth) holds
 * different rules at the moment the body is built.
 *
 * Expected strings are hand-written wire literals, never read back out of the rules under
 * test.
 */
class RequestThinkingRulesProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ThinkingRuleResolver.setAllCustomRules(emptyMap())
    }

    private fun rule(label: String, path: String, echo: ReasoningEchoPolicy?) = ThinkingRule(
        kind = ThinkingRule.Kind.CUSTOM,
        scope = ThinkingRule.Scope.ModelPattern("deepseek-v4*"),
        wireFormat = ThinkingWireFormat.CustomPath(
            path = path,
            values = mapOf(ThinkingLevel.HIGH to "xhigh"),
            offValue = "none",
        ),
        reasoningEcho = echo,
        label = label,
    )

    private fun snapshotOf(vararg rules: ThinkingRule) = RequestThinkingRules.fromJson(
        JSONArray().apply { rules.forEach { put(ThinkingRuleCoding.encodeRuleJson(it)) } },
    )

    private fun body(
        snapshot: RequestThinkingRules?,
        model: String = "deepseek-v4-flash",
        messages: List<LLMMessage> = listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
    ): JSONObject = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

data: [DONE]

""",
            ).setHeader("Content-Type", "text/event-stream"),
        )
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel(model, model, "Relay", supportsReasoning = true),
            basePath = server.url("/").toString().trimEnd('/'),
            requestThinkingRules = snapshot,
        )
        provider.sendMessage(messages, null, 2048, thinkingLevel = ThinkingLevel.HIGH)
        JSONObject(server.takeRequest().body.readUtf8())
    }

    @Test
    fun `wire uses the request snapshot, not the process cache`() {
        val snapshot = snapshotOf(rule("A-dispatched-old", "old_toggle", null))
        // The cache is what a LATER request on this worker would publish.
        ThinkingRuleResolver.setCustomRules("inst-a", listOf(rule("B-dispatched-new", "new_toggle", null)))
        val sent = body(snapshot)
        assertTrue("A's own rule must shape the wire", sent.has("old_toggle"))
        assertEquals("xhigh", sent.optString("old_toggle"))
        assertTrue("B's cache rule must not leak in", !sent.has("new_toggle"))
    }

    @Test
    fun `echopolicy also comes from the request snapshot`() {
        val snapshot = snapshotOf(
            rule(
                "A-dispatched-old",
                "old_toggle",
                ReasoningEchoPolicy("reasoning_content", ReasoningEchoPolicy.Timing.EVERY_TURN),
            ),
        )
        ThinkingRuleResolver.setCustomRules(
            "inst-a",
            listOf(rule("B-dispatched-new", "new_toggle", ReasoningEchoPolicy("other", ReasoningEchoPolicy.Timing.NEVER))),
        )
        val history = listOf(
            LLMMessage(LLMMessage.Role.USER, "hi"),
            LLMMessage(
                LLMMessage.Role.ASSISTANT,
                "",
                contentParts = listOf(
                    com.rikkaminis.app.data.model.AgentContentPart.ToolUse("call_1", "file_read", JSONObject()),
                ),
                reasoningContent = "captured-reasoning",
            ),
            LLMMessage(
                LLMMessage.Role.USER,
                "",
                contentParts = listOf(
                    com.rikkaminis.app.data.model.AgentContentPart.ToolResult("call_1", "file_read", "ok"),
                ),
            ),
        )
        val sent = body(snapshot, messages = history)
        val assistant = sent.getJSONArray("messages").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("role") == "assistant" }
        }
        assertEquals("A's echo policy must be used", "captured-reasoning", assistant.optString("reasoning_content"))
        assertTrue(sent.has("old_toggle"))
        assertTrue(!sent.has("new_toggle"))
    }

    @Test
    fun `empty snapshot on an old request inherits nothing from the cache`() {
        ThinkingRuleResolver.setCustomRules("inst-a", listOf(rule("B-dispatched-new", "new_toggle", null)))
        val sent = body(RequestThinkingRules.fromJson(null))
        assertTrue("no field must mean an EMPTY snapshot", !sent.has("new_toggle"))
        assertTrue(!sent.has("old_toggle"))
    }

    @Test
    fun `direct callers without a snapshot still capture the cache once`() {
        ThinkingRuleResolver.setCustomRules("inst-a", listOf(rule("direct-rule", "direct_toggle", null)))
        val provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel("deepseek-v4-flash", "m", "Relay", supportsReasoning = true),
            basePath = server.url("/").toString().trimEnd('/'),
        )
        provider.thinkingRuleInstanceId = "inst-a"
        server.enqueue(
            MockResponse().setBody(
                """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

data: [DONE]

""",
            ).setHeader("Content-Type", "text/event-stream"),
        )
        runBlocking {
            provider.sendMessage(
                listOf(LLMMessage(LLMMessage.Role.USER, "hi")),
                null, 2048, thinkingLevel = ThinkingLevel.HIGH,
            )
        }
        val sent = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("xhigh", sent.optString("direct_toggle"))
    }
}
