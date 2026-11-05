package probea

import org.json.JSONObject
import org.json.JSONArray

// ─────────────────────────────────────────────────────────────────────────
// FIX-RHUB1105-A 沙箱 JVM 影子探针（2026-11-05）
//
// 逐字节复刻生产文件 OpenAIProvider.kt @ 00dc003a 的【决策片段】，通过
// buildSrc() 宏由真码机械抽取（extract_probe.py），不是手写近似件：
//   ProdA1：Responses 工具三段 SSE（added / delta / done）+ EOF drain
//   ProdA2：Responses 分支的 error / response.failed 判别 when
//   ProdA3：Chat Completions [DONE] / EOF 两处 ReasoningContent 发射门控
// 生产片段之外只补最小桩：android.util.Log、AppLogger、LLMError、
// mapHttpError/openAiStreamErrorStatus/combineResponsesAPIIds/
// chatCompletionsDeltaThinkingBlocks（后四者按生产语义复刻，不变被测决策）。
//
// 期望值一律写死字面量，不从被测常量推导。
// ─────────────────────────────────────────────────────────────────────────

// ===== 最小桩 =====
private object Log { fun d(t: String, m: String) {} }
private object AppLogger {
    fun warning(t: String, m: String) {}
    fun info(t: String, m: String) {}
    fun error(t: String, m: String) {}
    fun debug(t: String, m: String) {}
}

private sealed class LLMError(message: String) : Exception(message) {
    class RateLimited : LLMError("Rate limited — please try again later")
    class InvalidApiKey : LLMError("Invalid API key")
    class TransientError(d: String) : LLMError("Transient error: $d")
    class ProviderError(d: String) : LLMError("Provider error: $d")
}

private fun extractHttpErrorMessage(body: String): String {
    return try {
        val j = JSONObject(body)
        j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: j.optString("message").takeIf { it.isNotBlank() }
            ?: body.take(500)
    } catch (_: Exception) { body.take(500) }
}

private fun mapHttpError(statusCode: Int, body: String): LLMError {
    if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
    if (statusCode == 429) return LLMError.RateLimited()
    val message = "[$statusCode] ${extractHttpErrorMessage(body)}"
    return if (statusCode in setOf(500, 502, 503, 504, 529)) LLMError.TransientError(message)
    else LLMError.ProviderError(message)
}

private fun openAiStreamErrorStatus(codeToken: String, typeToken: String): Int {
    fun family(token: String): Int? {
        val t = token.trim().lowercase()
        if (t.isEmpty()) return null
        return when {
            t.startsWith("rate_limit") -> 429
            t == "insufficient_quota" || t == "quota_exceeded" -> 429
            t.startsWith("authentication") || t.startsWith("invalid_api_key") -> 401
            t.startsWith("permission") -> 403
            t.startsWith("not_found") || t == "model_not_found" -> 404
            t.startsWith("context_length") || t.startsWith("invalid_request") ||
                t.startsWith("invalid_prompt") -> 400
            t == "server_error" || t == "api_error" || t == "overloaded_error" ||
                t.startsWith("internal") -> 500
            else -> null
        }
    }
    val numeric = codeToken.trim().toIntOrNull()
    if (numeric != null && numeric in 400..599) return numeric
    family(codeToken)?.let { return it }
    family(typeToken)?.let { return it }
    return 0
}

private fun combineResponsesAPIIds(callId: String, fcId: String): String =
    if (fcId.isEmpty()) callId else "$callId|$fcId"

private fun safeOptString(j: JSONObject, key: String, fallback: String = ""): String {
    return try {
        val v = j.opt(key) ?: return fallback
        if (v is String) v else fallback
    } catch (_: Exception) { fallback }
}

private fun chatCompletionsDeltaThinkingBlocks(delta: JSONObject?): List<String> {
    val array = delta?.optJSONArray("content") ?: return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until array.length()) {
        val block = array.optJSONObject(i) ?: continue
        if (block.optString("type", "") != "thinking") continue
        val thinking = block.optJSONArray("thinking") ?: continue
        for (j in 0 until thinking.length()) {
            val item = thinking.optJSONObject(j) ?: continue
            val text = safeOptString(item, "text", "")
            if (text.isNotEmpty()) out.add(text)
        }
    }
    return out
}

private fun chatCompletionsDeltaText(delta: JSONObject?): String {
    if (delta == null) return ""
    if (delta.isNull("content")) return ""
    val array = delta.optJSONArray("content") ?: return delta.optString("content", "")
    val out = StringBuilder()
    for (i in 0 until array.length()) {
        val block = array.optJSONObject(i) ?: continue
        if (block.optString("type", "") != "text") continue
        out.append(block.optString("text", ""))
    }
    return out.toString()
}

private data class Chunk(val kind: String, val a: String = "", val b: String = "", val args: JSONObject? = null)
private data class Acc(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastLoggedInputChars: Int = -1)

// ===== 生产片段（由 extract_probe.py 从真码机械抽出，勿手改） =====
// __PROD_A1__
// __PROD_A2__
// __PROD_A3__

// ===== 夹具 =====
private var failures = 0
private fun check(name: String, ok: Boolean) {
    if (ok) println("PASS  $name") else { println("FAIL  $name"); failures++ }
}

private fun data(events: List<String>, done: Boolean = true): List<String> {
    val out = events.toMutableList()
    if (done) out += "[DONE]"
    return out
}

private fun sse(event: String): String = event

fun main() {
    // ── F-01 ──────────────────────────────────────────────────────────
    // 反事实夹具（任务书 §2）：added(arguments:"") → delta({"command":"pwd"}) → done(arguments:"")
    val f01 = responsesToolStream(
        listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"bash","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"command\":\"pwd\"}"}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"bash","arguments":""}}""",
        ), done = false,
    )
    val complete = f01.filter { it.kind == "ToolCallComplete" }
    check("F-01/A empty-arguments snapshot must not wipe streamed delta",
        complete.size == 1 && complete[0].args?.optString("command") == "pwd")

    // 路径 B：只在 function_call_arguments.done 里给完整参数
    val f01b = responsesToolStream(
        listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_2","call_id":"call_2","name":"bash","arguments":""}}""",
            """{"type":"response.function_call_arguments.done","item_id":"fc_2","output_index":0,"arguments":"{\"command\":\"ls\"}"}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_2","call_id":"call_2","name":"bash"}}""",
        ), done = false,
    )
    check("F-01/B arguments.done authoritative payload is absorbed",
        f01b.any { it.kind == "ToolCallComplete" && it.args?.optString("command") == "ls" })

    // 路径 C：省略可选 item.id / item_id，只靠 call_id 关联
    val f01c = responsesToolStream(
        listOf(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_3","name":"bash"}}""",
            """{"type":"response.function_call_arguments.delta","call_id":"call_3","output_index":0,"delta":"{\"command\":\"whoami\"}"}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"call_3","name":"bash"}}""",
        ), done = false,
    )
    check("F-01/C optional item.id absent still resolves via call_id",
        f01c.any { it.kind == "ToolUseStart" } &&
            f01c.any { it.kind == "ToolCallComplete" && it.args?.optString("command") == "whoami" })

    // ── F-02 ──────────────────────────────────────────────────────────
    // 反事实夹具：type=="error" + [DONE]，当前会走完流 Finished(truncated=true)
    val f02 = responsesErrorStream(
        """{"type":"error","error":{"message":"Quota exceeded","code":"insufficient_quota"}}""",
    )
    val thrown = f02.second
    check("F-02 a Responses top-level error frame throws instead of finishing",
        thrown != null && thrown.message?.contains("Quota exceeded") == true &&
            thrown !is LLMError.TransientError)
    check("F-02 control arm: no error frame still finishes cleanly",
        responsesErrorStream("""{"type":"response.completed","response":{"status":"completed"}}""").second == null)

    // ── F-06 ──────────────────────────────────────────────────────────
    // 例 1：Mistral content[] 思考块 + finish_reason:stop + [DONE]
    val f06a = reasoningStream(
        listOf(
            """{"choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"step 1"}]}]}}]}""",
            """{"choices":[{"index":0,"delta":{"content":"answer"},"finish_reason":"stop"}]}""",
        ), done = true,
    )
    check("F-06/1 Mistral thinking block emits ReasoningContent(step 1)",
        f06a.any { it.kind == "ReasoningContent" && it.a == "step 1" })

    // 例 2：标量 reasoning_content + finish_reason:stop，无 [DONE] 直接 EOF
    val f06b = reasoningStream(
        listOf(
            """{"choices":[{"index":0,"delta":{"reasoning_content":"think"},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
        ), done = false,
    )
    check("F-06/2 EOF without [DONE] still emits ReasoningContent(think)",
        f06b.any { it.kind == "ReasoningContent" && it.a == "think" })

    // 负控：<think> 标签拆出的内容仍不得触发 ReasoningContent（既有设计）
    val f06c = reasoningStream(
        listOf("""{"choices":[{"index":0,"delta":{"content":"<think>hidden</think>visible"},"finish_reason":"stop"}]}"""),
        done = true,
    )
    check("F-06/control think-tag text does NOT emit ReasoningContent",
        f06c.none { it.kind == "ReasoningContent" } && f06c.any { it.kind == "ThinkingDelta" && it.a == "hidden" })

    println(if (failures == 0) "PROBE_A=PASS" else "PROBE_A=FAIL ($failures)")
    if (failures != 0) throw AssertionError("$failures probe assertion(s) failed")
}
