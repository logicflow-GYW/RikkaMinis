package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * GOLDEN SNAPSHOT of the thinking-parameter injection on the Chat Completions path.
 *
 * WHY THIS EXISTS, and how it differs from [ThinkingRulesRegressionTest]: that suite
 * asserts the *rules we knew to look for* — one hand-written assertion per catalogued
 * rule. This file asserts something weaker but far broader: for a matrix of
 * (model × level × endpoint) combinations, the emitted thinking fields are
 * **byte-for-byte what they are today**, whatever that happens to be. The single-
 * dimension rows pin every branch; the CROSS-PRODUCT rows pin the rule-ORDERING
 * interactions, which is where silent regressions actually live (the unified-gateway
 * hoisting regression slipped past single-dimension rows).
 *
 * A diff here is a behaviour change — either an unintended regression, or an intended
 * fix that must be called out explicitly and updated deliberately. Do NOT regenerate
 * expectations to turn a red test green without explaining, in words, why the wire
 * format legitimately changed.
 *
 * RIKKAMINIS DELIBERATE DIVERGENCES FROM THE UPSTREAM BASELINE (all field-measured):
 *   1. qwen on a non-DashScope base → `enable_thinking:false` at OFF and portable
 *      `reasoning_effort` at ON (7aea092d host gating; upstream dual-sends everywhere).
 *   2. deepseek-v4 splits by ENDPOINT: official → vendor sibling object, relay →
 *      top-level `reasoning_effort` (upstream has one shape).
 *   3. OFF emits `enable_thinking:false` / `thinking:{type:"disabled"}` explicitly for
 *      default-reasoning families (upstream omits at OFF).
 *   4. MockWebServer base never qualifies for `explicitOffEffort`, so OFF renders `{}`
 *      on unified-gateway rows where upstream's live-base baseline shows the off tier.
 *   5. AUTO rows emit nothing (T-thinking-auto-level, no upstream equivalent).
 */
class ThinkingWireGoldenSnapshotTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(
        id: String,
        supportsReasoning: Boolean? = true,
        reasoningEffortValues: List<String>? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = "Golden",
        supportsReasoning = supportsReasoning,
        reasoningEffortValues = reasoningEffortValues,
    )

    /** Stable textual form: sorted keys at every level, bools distinct from numbers. */
    private fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "$it:${canonical(v.get(it))}" }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        is String -> "\"$v\""
        else -> v.toString()
    }

    /** The keys this layer is allowed to touch. Everything else is request noise. */
    private val watched = listOf(
        "reasoning_effort", "reasoning", "thinking",
        "enable_thinking", "thinking_budget", "extra_body",
    )

    private fun emit(m: LLMModel, level: ThinkingLevel, basePath: String, maxTokens: Int): String {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}"""
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(ok))
        }
        val provider = OpenAIProvider(apiKey = "test-key", model = m, basePath = basePath)
        runCatching {
            runBlocking {
                provider.sendMessageClamped(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "q")),
                    systemPrompt = null,
                    maxTokens = maxTokens,
                    temperature = null,
                    imageParts = emptyList(),
                    tools = emptyList(),
                    thinkingLevel = level,
                )
            }
        }
        val body = JSONObject(server.takeRequest().body.readUtf8())
        val present = watched.filter { body.has(it) }.sorted()
        return present.joinToString(",", "{", "}") { "$it:${canonical(body.get(it))}" }
    }

    private data class Case(
        val label: String,
        val model: LLMModel,
        val path: String,
        val maxTokens: Int = 8192,
    )

    private fun matrix(): List<Case> = listOf(
        Case("openai-gpt5", model("gpt-5.3", reasoningEffortValues = listOf("none", "low", "medium", "high", "xhigh")), "/v1"),
        Case("openai-o3", model("o3-mini"), "/v1"),
        Case("openai-gpt4o-nonreasoning", model("gpt-4o", supportsReasoning = false), "/v1"),
        Case("openrouter", model("anthropic/claude-sonnet-4-6", reasoningEffortValues = listOf("low", "medium", "high")), "/openrouter.ai/api/v1"),
        Case("qwen", model("qwen3-32b"), "/v1", maxTokens = 16384),
        Case("qwen-tiny-max", model("qwen3-32b"), "/v1", maxTokens = 1),
        Case("deepseek-v4", model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")), "/v1"),
        Case("deepseek-v4-unified", model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")), "/ark.volces.com/api/v3"),
        Case("glm-declared", model("glm-5.2", reasoningEffortValues = listOf("high", "max")), "/v1"),
        Case("glm-undeclared", model("glm-4.5-air", supportsReasoning = null), "/v1"),
        Case("mimo", model("mimo-v2.5"), "/v1"),
        Case("agnes", model("agnes-1"), "/v1"),
        Case("seed", model("seed-1.6"), "/ark.volces.com/api/v3"),
        Case("generic-unknown", model("some-relay-model"), "/v1"),
        Case("sparse-high-max", model("vendor-x", reasoningEffortValues = listOf("high", "max")), "/v1"),
        Case("mistral", model("mistral-large-latest", reasoningEffortValues = listOf("low", "high")), "/mistral.ai/v1"),
        Case("venice-deepseek", model("deepseek-v4-flash", reasoningEffortValues = listOf("low", "high", "max")), "/api.venice.ai/api/v1"),

        // ---- CROSS-PRODUCT ROWS ----
        // Every case above varies ONE dimension, and that blind spot let a real ordering
        // regression through: hoisting the unified-gateway rule above the qwen and
        // OpenAI-native patterns changed the wire shape for models that match a vendor
        // pattern AND sit on a unified/DashScope endpoint. Single-dimension rows cannot
        // see it, because neither dimension alone is wrong. These rows pin the
        // interaction, which is where rule-ordering bugs actually live.
        Case("qwen-on-unified", model("qwen3-32b"), "/ark.volces.com/api/v3"),
        Case("gpt5-on-dashscope", model("gpt-5.3", reasoningEffortValues = listOf("low", "high")), "/dashscope.aliyuncs.com/compatible-mode/v1"),
        Case("mimo-on-unified", model("mimo-v2.5"), "/ark.volces.com/api/v3"),
        Case("qwen-on-openrouter", model("qwen3-32b"), "/openrouter.ai/api/v1"),
        Case("deepseek-v4-on-openrouter", model("deepseek-v4-pro", reasoningEffortValues = listOf("high", "max")), "/openrouter.ai/api/v1"),
    )

    private val levels = listOf(
        ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
        // AUTO: no upstream equivalent; pinned here so every branch stays silent for it.
        ThinkingLevel.AUTO,
    )

    private fun render(): String = buildString {
        for (c in matrix()) {
            for (lv in levels) {
                append("${c.label}/${lv.name} -> ${emit(c.model, lv, server.url(c.path).toString().trimEnd('/'), c.maxTokens)}")
                append('\n')
            }
        }
    }.trimEnd('\n')

    @Test
    fun `golden snapshot of every branch`() {
        val actual = render()
        println("===GOLDEN_EXPECTED_START===")
        println(EXPECTED)
        println("===GOLDEN_EXPECTED_END===")
        println("===GOLDEN_ACTUAL_START===")
        println(actual)
        println("===GOLDEN_ACTUAL_END===")
        assertEquals(
            """
            THINKING WIRE FORMAT CHANGED.

            This is the byte-for-byte oracle for the thinking rule layer. If you are
            mid-refactor and see this fail, the rule engine emits a different request
            than this baseline for at least one (model, level) pair.

            Do not "fix" this by pasting the new output in. Diff the two blocks, identify
            which branch moved, and either restore parity or justify the change explicitly.
            """.trimIndent(),
            EXPECTED,
            actual,
        )
    }

    companion object {
        /** Generated from the current implementation; see the class doc for divergences. */
        private val EXPECTED = """
openai-gpt5/OFF -> {}
openai-gpt5/LOW -> {reasoning_effort:"low"}
openai-gpt5/MEDIUM -> {reasoning_effort:"medium"}
openai-gpt5/HIGH -> {reasoning_effort:"high"}
openai-gpt5/XHIGH -> {reasoning_effort:"xhigh"}
openai-gpt5/MAX -> {reasoning_effort:"xhigh"}
openai-gpt5/ULTRA -> {reasoning_effort:"xhigh"}
openai-gpt5/AUTO -> {}
openai-o3/OFF -> {}
openai-o3/LOW -> {reasoning_effort:"low"}
openai-o3/MEDIUM -> {reasoning_effort:"medium"}
openai-o3/HIGH -> {reasoning_effort:"high"}
openai-o3/XHIGH -> {reasoning_effort:"xhigh"}
openai-o3/MAX -> {reasoning_effort:"xhigh"}
openai-o3/ULTRA -> {reasoning_effort:"xhigh"}
openai-o3/AUTO -> {}
openai-gpt4o-nonreasoning/OFF -> {}
openai-gpt4o-nonreasoning/LOW -> {}
openai-gpt4o-nonreasoning/MEDIUM -> {}
openai-gpt4o-nonreasoning/HIGH -> {}
openai-gpt4o-nonreasoning/XHIGH -> {}
openai-gpt4o-nonreasoning/MAX -> {}
openai-gpt4o-nonreasoning/ULTRA -> {}
openai-gpt4o-nonreasoning/AUTO -> {}
openrouter/OFF -> {}
openrouter/LOW -> {reasoning:{effort:"low"}}
openrouter/MEDIUM -> {reasoning:{effort:"medium"}}
openrouter/HIGH -> {reasoning:{effort:"high"}}
openrouter/XHIGH -> {reasoning:{effort:"high"}}
openrouter/MAX -> {reasoning:{effort:"high"}}
openrouter/ULTRA -> {reasoning:{effort:"high"}}
openrouter/AUTO -> {}
qwen/OFF -> {enable_thinking:false}
qwen/LOW -> {reasoning_effort:"low"}
qwen/MEDIUM -> {reasoning_effort:"medium"}
qwen/HIGH -> {reasoning_effort:"high"}
qwen/XHIGH -> {reasoning_effort:"xhigh"}
qwen/MAX -> {reasoning_effort:"xhigh"}
qwen/ULTRA -> {reasoning_effort:"xhigh"}
qwen/AUTO -> {}
qwen-tiny-max/OFF -> {enable_thinking:false}
qwen-tiny-max/LOW -> {reasoning_effort:"low"}
qwen-tiny-max/MEDIUM -> {reasoning_effort:"medium"}
qwen-tiny-max/HIGH -> {reasoning_effort:"high"}
qwen-tiny-max/XHIGH -> {reasoning_effort:"xhigh"}
qwen-tiny-max/MAX -> {reasoning_effort:"xhigh"}
qwen-tiny-max/ULTRA -> {reasoning_effort:"xhigh"}
qwen-tiny-max/AUTO -> {}
deepseek-v4/OFF -> {thinking:{type:"disabled"}}
deepseek-v4/LOW -> {reasoning_effort:"high"}
deepseek-v4/MEDIUM -> {reasoning_effort:"high"}
deepseek-v4/HIGH -> {reasoning_effort:"max"}
deepseek-v4/XHIGH -> {reasoning_effort:"max"}
deepseek-v4/MAX -> {reasoning_effort:"max"}
deepseek-v4/ULTRA -> {reasoning_effort:"max"}
deepseek-v4/AUTO -> {}
deepseek-v4-unified/OFF -> {}
deepseek-v4-unified/LOW -> {reasoning_effort:"high"}
deepseek-v4-unified/MEDIUM -> {reasoning_effort:"high"}
deepseek-v4-unified/HIGH -> {reasoning_effort:"high"}
deepseek-v4-unified/XHIGH -> {reasoning_effort:"high"}
deepseek-v4-unified/MAX -> {reasoning_effort:"max"}
deepseek-v4-unified/ULTRA -> {reasoning_effort:"max"}
deepseek-v4-unified/AUTO -> {}
glm-declared/OFF -> {}
glm-declared/LOW -> {reasoning_effort:"high"}
glm-declared/MEDIUM -> {reasoning_effort:"high"}
glm-declared/HIGH -> {reasoning_effort:"high"}
glm-declared/XHIGH -> {reasoning_effort:"high"}
glm-declared/MAX -> {reasoning_effort:"max"}
glm-declared/ULTRA -> {reasoning_effort:"max"}
glm-declared/AUTO -> {}
glm-undeclared/OFF -> {}
glm-undeclared/LOW -> {}
glm-undeclared/MEDIUM -> {}
glm-undeclared/HIGH -> {}
glm-undeclared/XHIGH -> {}
glm-undeclared/MAX -> {}
glm-undeclared/ULTRA -> {}
glm-undeclared/AUTO -> {}
mimo/OFF -> {}
mimo/LOW -> {reasoning_effort:"low"}
mimo/MEDIUM -> {reasoning_effort:"medium"}
mimo/HIGH -> {reasoning_effort:"high"}
mimo/XHIGH -> {reasoning_effort:"high"}
mimo/MAX -> {reasoning_effort:"high"}
mimo/ULTRA -> {reasoning_effort:"high"}
mimo/AUTO -> {}
agnes/OFF -> {}
agnes/LOW -> {reasoning_effort:"low"}
agnes/MEDIUM -> {reasoning_effort:"medium"}
agnes/HIGH -> {reasoning_effort:"high"}
agnes/XHIGH -> {reasoning_effort:"high"}
agnes/MAX -> {reasoning_effort:"high"}
agnes/ULTRA -> {reasoning_effort:"high"}
agnes/AUTO -> {}
seed/OFF -> {}
seed/LOW -> {reasoning_effort:"low"}
seed/MEDIUM -> {reasoning_effort:"medium"}
seed/HIGH -> {reasoning_effort:"high"}
seed/XHIGH -> {reasoning_effort:"high"}
seed/MAX -> {reasoning_effort:"high"}
seed/ULTRA -> {reasoning_effort:"high"}
seed/AUTO -> {}
generic-unknown/OFF -> {}
generic-unknown/LOW -> {reasoning_effort:"low"}
generic-unknown/MEDIUM -> {reasoning_effort:"medium"}
generic-unknown/HIGH -> {reasoning_effort:"high"}
generic-unknown/XHIGH -> {reasoning_effort:"xhigh"}
generic-unknown/MAX -> {reasoning_effort:"xhigh"}
generic-unknown/ULTRA -> {reasoning_effort:"xhigh"}
generic-unknown/AUTO -> {}
sparse-high-max/OFF -> {}
sparse-high-max/LOW -> {reasoning_effort:"high"}
sparse-high-max/MEDIUM -> {reasoning_effort:"high"}
sparse-high-max/HIGH -> {reasoning_effort:"high"}
sparse-high-max/XHIGH -> {reasoning_effort:"high"}
sparse-high-max/MAX -> {reasoning_effort:"max"}
sparse-high-max/ULTRA -> {reasoning_effort:"max"}
sparse-high-max/AUTO -> {}
mistral/OFF -> {}
mistral/LOW -> {}
mistral/MEDIUM -> {}
mistral/HIGH -> {}
mistral/XHIGH -> {}
mistral/MAX -> {}
mistral/ULTRA -> {}
mistral/AUTO -> {}
venice-deepseek/OFF -> {}
venice-deepseek/LOW -> {reasoning_effort:"low"}
venice-deepseek/MEDIUM -> {reasoning_effort:"low"}
venice-deepseek/HIGH -> {reasoning_effort:"high"}
venice-deepseek/XHIGH -> {reasoning_effort:"high"}
venice-deepseek/MAX -> {reasoning_effort:"max"}
venice-deepseek/ULTRA -> {reasoning_effort:"max"}
venice-deepseek/AUTO -> {}
qwen-on-unified/OFF -> {enable_thinking:false}
qwen-on-unified/LOW -> {reasoning_effort:"low"}
qwen-on-unified/MEDIUM -> {reasoning_effort:"medium"}
qwen-on-unified/HIGH -> {reasoning_effort:"high"}
qwen-on-unified/XHIGH -> {reasoning_effort:"xhigh"}
qwen-on-unified/MAX -> {reasoning_effort:"xhigh"}
qwen-on-unified/ULTRA -> {reasoning_effort:"xhigh"}
qwen-on-unified/AUTO -> {}
gpt5-on-dashscope/OFF -> {}
gpt5-on-dashscope/LOW -> {reasoning_effort:"low"}
gpt5-on-dashscope/MEDIUM -> {reasoning_effort:"medium"}
gpt5-on-dashscope/HIGH -> {reasoning_effort:"high"}
gpt5-on-dashscope/XHIGH -> {reasoning_effort:"high"}
gpt5-on-dashscope/MAX -> {reasoning_effort:"high"}
gpt5-on-dashscope/ULTRA -> {reasoning_effort:"high"}
gpt5-on-dashscope/AUTO -> {}
mimo-on-unified/OFF -> {}
mimo-on-unified/LOW -> {reasoning_effort:"low"}
mimo-on-unified/MEDIUM -> {reasoning_effort:"medium"}
mimo-on-unified/HIGH -> {reasoning_effort:"high"}
mimo-on-unified/XHIGH -> {reasoning_effort:"high"}
mimo-on-unified/MAX -> {reasoning_effort:"high"}
mimo-on-unified/ULTRA -> {reasoning_effort:"high"}
mimo-on-unified/AUTO -> {}
qwen-on-openrouter/OFF -> {}
qwen-on-openrouter/LOW -> {reasoning:{effort:"low"}}
qwen-on-openrouter/MEDIUM -> {reasoning:{effort:"medium"}}
qwen-on-openrouter/HIGH -> {reasoning:{effort:"high"}}
qwen-on-openrouter/XHIGH -> {reasoning:{effort:"xhigh"}}
qwen-on-openrouter/MAX -> {reasoning:{effort:"xhigh"}}
qwen-on-openrouter/ULTRA -> {reasoning:{effort:"xhigh"}}
qwen-on-openrouter/AUTO -> {}
deepseek-v4-on-openrouter/OFF -> {}
deepseek-v4-on-openrouter/LOW -> {reasoning:{effort:"low"}}
deepseek-v4-on-openrouter/MEDIUM -> {reasoning:{effort:"medium"}}
deepseek-v4-on-openrouter/HIGH -> {reasoning:{effort:"high"}}
deepseek-v4-on-openrouter/XHIGH -> {reasoning:{effort:"xhigh"}}
deepseek-v4-on-openrouter/MAX -> {reasoning:{effort:"max"}}
deepseek-v4-on-openrouter/ULTRA -> {reasoning:{effort:"max"}}
deepseek-v4-on-openrouter/AUTO -> {}
        """.trimIndent()
    }
}
