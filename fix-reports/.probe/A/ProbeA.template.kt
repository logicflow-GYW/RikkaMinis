package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.data.model.LLMError
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.provider.TOOL_LOG_INPUT_STEP_CHARS
import com.rikkaminis.app.provider.extractHttpErrorMessage
import com.rikkaminis.app.provider.safeOptString
import org.json.JSONObject

/**
 * SHADOW PROBE for FIX-A (F-01 / F-02 / F-06).
 *
 * Decision logic is NOT hand-copied: every block between the marker lines below
 * is extracted verbatim from the real OpenAIProvider.kt by /tmp/probeA2/build.py,
 * dedented only. The harness around it (state declarations, payload loop, send())
 * is glue and holds no decision.
 */

private class StubModel(val id: String)

// ======================= F-01 / F-02 : Responses path =======================

private fun responsesStream(payloads: List<String>): List<LLMStreamChunk> {
    val out = mutableListOf<LLMStreamChunk>()
    fun send(c: LLMStreamChunk) { out.add(c) }

    val model = StubModel("stub-model")
    val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
    val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
    var finishReason: String? = null
    var sawFinishReason = false
    var sawReasoningField = false
    var sawReasoningDelta = false
    val reasoningAccum = StringBuilder()
    var contentLen = 0
    var reasoningLen = 0
    var toolCallEventCount = 0
    var sseEventCount = 0
    var finishedSent = false
    val hasThinkTags = true
    val thinkState = ThinkTagState()
    var lastUsageJson: JSONObject? = null
    var sawUsageBlock = false

    for (payload in payloads) {
@@A4@@

        val event = try { JSONObject(payload) } catch (e: Exception) { continue }
        val type = event.optString("type", "")
        when {
@@A1@@

@@A2@@

            else -> {}
        }
    }

@@A5@@

    return out
}

// ======================= F-06 : Chat Completions path =======================

private fun chatStream(payloads: List<String>): List<LLMStreamChunk> {
    val out = mutableListOf<LLMStreamChunk>()
    fun send(c: LLMStreamChunk) { out.add(c) }

    val model = StubModel("stub-model")
    val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
    val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
    var finishReason: String? = null
    var sawFinishReason = false
    var sawReasoningField = false
    var sawReasoningDelta = false
    val reasoningAccum = StringBuilder()
    var contentLen = 0
    var reasoningLen = 0
    var toolCallEventCount = 0
    var sseEventCount = 0
    var finishedSent = false
    val hasThinkTags = true
    val thinkState = ThinkTagState()
    var lastUsageJson: JSONObject? = null
    var sawUsageBlock = false

    for (payload in payloads) {
@@A4@@

        val event = try { JSONObject(payload) } catch (e: Exception) { continue }
        val choices = event.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta")
            contentLen += chatCompletionsDeltaText(delta).length
            reasoningLen += chatCompletionsDeltaThinkingBlocks(delta).sumOf { it.length }

            delta?.let { d ->
@@A3@@

            choice.safeOptString("finish_reason", "").let {
                if (it.isNotEmpty()) {
                    finishReason = it
                    if (!sawFinishReason) sawFinishReason = true
                }
            }
        }
    }

@@A5@@

    return out
}

// ======================= production declarations / helpers (extracted) ======

@@DECL_TOOLACC@@

@@DECL_RESPACC@@

@@HELP_ACC@@

@@HELP_IDS@@

@@HELP_MAP@@

@@HELP_PARSE_CHAT@@

@@HELP_PARSE_RESP@@

@@HELP_THINKRESULT@@

@@HELP_THINKSTATE@@

@@HELP_EXTRACT@@

@@HELP_FLUSH@@

@@HELP_DELTATEXT@@

@@HELP_DELTATHINK@@

// ======================= fixtures =======================

private var failures = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("PASS  $name")
    else { failures++; println("FAIL  $name ${if (detail.isNotEmpty()) "— $detail" else ""}") }
}

private fun toolCompletes(chunks: List<LLMStreamChunk>) =
    chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()

// --- F-01a: empty `arguments` on output_item.done must not wipe the buffer ----
private fun f01EmptyArgumentsAtDone() {
    val payloads = listOf(
        """{"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"shell","arguments":""}}""",
        """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"{\"command\":\"pwd\"}"}""",
        """{"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","call_id":"call_1","name":"shell","arguments":""}}""",
        "[DONE]",
    )
    val completes = toolCompletes(responsesStream(payloads))
    check("F-01a one ToolCallComplete emitted", completes.size == 1, "got ${completes.size}")
    check(
        "F-01a args survive empty arguments on output_item.done",
        completes.firstOrNull()?.args?.optString("command") == "pwd",
        "args=${completes.firstOrNull()?.args}",
    )
}

// --- F-01b: `response.function_call_arguments.done` is authoritative ----------
private fun f01ArgumentsDoneArm() {
    val payloads = listOf(
        """{"type":"response.output_item.added","item":{"id":"fc_2","type":"function_call","call_id":"call_2","name":"shell","arguments":""}}""",
        """{"type":"response.function_call_arguments.done","item_id":"fc_2","call_id":"call_2","output_index":0,"arguments":"{\"command\":\"ls\"}"}""",
        "[DONE]",
    )
    val completes = toolCompletes(responsesStream(payloads))
    check(
        "F-01b function_call_arguments.done arms the call",
        completes.any { it.args.optString("command") == "ls" },
        "completes=${completes.map { it.args.toString() }}",
    )
}

// --- F-01c: optional item.id (call_id fallback) ------------------------------
private fun f01MissingItemId() {
    val payloads = listOf(
        """{"type":"response.output_item.added","item":{"type":"function_call","call_id":"call_3","name":"shell","arguments":""}}""",
        """{"type":"response.function_call_arguments.delta","call_id":"call_3","output_index":0,"delta":"{\"command\":\"whoami\"}"}""",
        """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_3","name":"shell","arguments":"{\"command\":\"whoami\"}"}}""",
        "[DONE]",
    )
    val completes = toolCompletes(responsesStream(payloads))
    check(
        "F-01c item without id still yields ToolCallComplete(call_3)",
        completes.any { it.id.startsWith("call_3") && it.args.optString("command") == "whoami" },
        "completes=${completes.map { it.id to it.args.toString() }}",
    )
}

// --- F-01d: no output_item.done at all → EOF drain must still emit -----------
private fun f01EofDrain() {
    val payloads = listOf(
        """{"type":"response.output_item.added","item":{"id":"fc_4","type":"function_call","call_id":"call_4","name":"shell","arguments":""}}""",
        """{"type":"response.function_call_arguments.delta","item_id":"fc_4","delta":"{\"command\":\"id\"}"}""",
    )
    val completes = toolCompletes(responsesStream(payloads))
    check(
        "F-01d EOF drain emits the half-finished call",
        completes.any { it.args.optString("command") == "id" },
        "completes=${completes.map { it.args.toString() }}",
    )
}

// --- F-02: top-level type:"error" frame must map to a real LLMError ----------
private fun f02InlineErrorFrame() {
    val payloads = listOf(
        """{"type":"error","error":{"message":"Quota exceeded","code":"insufficient_quota"}}""",
        "[DONE]",
    )
    var thrown: LLMError? = null
    var chunks: List<LLMStreamChunk> = emptyList()
    try {
        chunks = responsesStream(payloads)
    } catch (e: LLMError) {
        thrown = e
    }
    check("F-02 error frame throws instead of finishing silently", thrown != null,
        "chunks=${chunks.map { it::class.simpleName }}")
    check("F-02 mapped to the rate-limit family (not TransientError)",
        thrown is LLMError.RateLimited, "thrown=${thrown?.let { it::class.simpleName }}")
    check("F-02 no Finished emitted after the error frame",
        chunks.none { it is LLMStreamChunk.Finished })
}

// --- F-06a: Mistral content[].type=="thinking" must set sawReasoningField ----
private fun f06MistralThinkingBlocks() {
    val payloads = listOf(
        """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"step 1"}]}]}}]}""",
        """{"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}]}""",
        "[DONE]",
    )
    val reasoning = chatStream(payloads).filterIsInstance<LLMStreamChunk.ReasoningContent>()
    check("F-06a ReasoningContent emitted for Mistral thinking blocks",
        reasoning.any { it.content == "step 1" },
        "got=${reasoning.map { it.content }}")
}

// --- F-06b: scalar reasoning_content + EOF (no [DONE]) must still emit -------
private fun f06ScalarReasoningAtEof() {
    val payloads = listOf(
        """{"choices":[{"delta":{"reasoning_content":"think"}}]}""",
        """{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}""",
    )
    val reasoning = chatStream(payloads).filterIsInstance<LLMStreamChunk.ReasoningContent>()
    check("F-06b ReasoningContent emitted on EOF without [DONE]",
        reasoning.any { it.content == "think" },
        "got=${reasoning.map { it.content }}")
}

fun main() {
    f01EmptyArgumentsAtDone()
    f01ArgumentsDoneArm()
    f01MissingItemId()
    f01EofDrain()
    f02InlineErrorFrame()
    f06MistralThinkingBlocks()
    f06ScalarReasoningAtEof()
    println(if (failures == 0) "ALL PASS" else "$failures FAILURE(S)")
    if (failures != 0) kotlin.system.exitProcess(1)
}
