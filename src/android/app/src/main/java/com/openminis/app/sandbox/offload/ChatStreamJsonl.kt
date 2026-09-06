package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import org.json.JSONObject

/**
 * JSONL (one JSON object per line) codec for [LLMStreamChunk].
 *
 * Used by the :modelservice streaming protocol:
 *   - service process appends one line per emitted chunk to stream.jsonl
 *   - main process reads incremental bytes and decodes each line back
 *     into [LLMStreamChunk]
 *
 * Line schema:
 *   {"t":"started"}
 *   {"t":"started","run":"<runId>","seq":0}      optional correlation (provider-rss v2 / TF-A)
 *   {"t":"text","v":"..."}
 *   {"t":"td","v":"..."}                  ThinkingDelta
 *   {"t":"rc","v":"..."}                  ReasoningContent
 *   {"t":"tu_start","id":"...","name":"..."}
 *   {"t":"tu_delta","id":"...","v":"..."}
 *   {"t":"tu_done","id":"...","name":"...","args":{...}}
 *   {"t":"usage","in":123,"out":45,"cache_creation":0,"cache_read":0,"ctx":0}
 *   {"t":"finished","stop":"stop_reason","truncated":false}
 *   {"t":"media","type":"image","mime":"image/png","b64":"..."}
 *   {"t":"error","m":"message"}
 *   {"t":"done"}                          sentinel: stream complete
 *
 * Correlation fields (v2): every encoded line MAY carry optional `run` (runId) and
 * `seq` (chunk ordinal) for cross-referencing [provider-rss] log lines with the stream
 * that produced them. They are NOT emitted by default (decode stays backwards-compatible:
 * absent → run=null / seq=-1) and are purely observational — they never affect chunk
 * semantics.
 *
 * Errors are represented as {"t":"error","m":"..."} lines — the consumer
 * surfaces them as failures and falls back to in-process, never as a fake
 * successful Finished (hard-won rule: remote failure must NOT masquerade
 * as success).
 */
object ChatStreamJsonl {

    /** Encode a chunk as a single JSONL line (no trailing newline). */
    fun encode(chunk: LLMStreamChunk): String = when (chunk) {
        LLMStreamChunk.Started -> """{"t":"started"}"""
        is LLMStreamChunk.Text -> JSONObject().put("t", "text").put("v", chunk.text).toString()
        is LLMStreamChunk.ThinkingDelta -> JSONObject().put("t", "td").put("v", chunk.text).toString()
        is LLMStreamChunk.ReasoningContent -> JSONObject().put("t", "rc").put("v", chunk.content).toString()
        is LLMStreamChunk.ToolUseStart -> JSONObject()
            .put("t", "tu_start").put("id", chunk.id).put("name", chunk.name).toString()
        is LLMStreamChunk.ToolInputDelta -> JSONObject()
            .put("t", "tu_delta").put("id", chunk.id).put("v", chunk.accumulated).toString()
        is LLMStreamChunk.ToolCallComplete -> JSONObject()
            .put("t", "tu_done").put("id", chunk.id).put("name", chunk.name).put("args", chunk.args).toString()
        is LLMStreamChunk.Usage -> JSONObject()
            .put("t", "usage")
            .put("in", chunk.usage.inputTokens)
            .put("out", chunk.usage.outputTokens)
            .put("cache_creation", chunk.usage.cacheCreationInputTokens ?: 0)
            .put("cache_read", chunk.usage.cacheReadInputTokens ?: 0)
            .put("ctx", chunk.usage.latestContextTokens)
            .toString()
        is LLMStreamChunk.Finished -> JSONObject()
            .put("t", "finished").put("stop", chunk.stopReason ?: "").put("truncated", chunk.truncated).toString()
        is LLMStreamChunk.MediaAttachment -> JSONObject()
            .put("t", "media")
            .put("type", chunk.attachment.type.value)
            .put("mime", chunk.attachment.mimeType)
            .put("b64", java.util.Base64.getEncoder().encodeToString(chunk.attachment.data))
            .toString()
    }

    /**
     * 编码为带可选关联字段（runId/seq）的 JSONL 行。仅当 [runId] 非空或 [seq] >= 0 时注入；
     * 否则等价于 [encode]，保持零噪音。纯观测，不影响 chunk 语义。
     */
    fun encodeWithCorrelation(chunk: LLMStreamChunk, runId: String? = null, seq: Int = -1): String {
        val base = encode(chunk)
        if (runId == null && seq < 0) return base
        val obj = try { JSONObject(base) } catch (_: Exception) { return base }
        if (runId != null) obj.put("run", runId)
        if (seq >= 0) obj.put("seq", seq)
        return obj.toString()
    }

    /** 单行解码结果：chunk（不可解析/error/done 为 null）+ 可选关联字段。 */
    data class StreamLine(
        val chunk: LLMStreamChunk?,
        val runId: String?,
        val seq: Int,
    )

    /**
     * 解码单行并同时取出可选关联字段 run/seq（provider-rss v2 / TF-A）。
     * 纯追加：字段缺失时 runId=null、seq=-1，不与 [decode] 的语义冲突。
     */
    fun decodeLine(line: String): StreamLine {
        val t = line.trim()
        if (t.isEmpty()) return StreamLine(null, null, -1)
        val obj = try { JSONObject(t) } catch (_: Exception) { return StreamLine(null, null, -1) }
        return StreamLine(
            chunk = decode(t),
            runId = obj.optString("run", "").ifEmpty { null },
            seq = obj.optInt("seq", -1),
        )
    }

    /** Sentinel line marking the end of a stream. */
    const val DONE_LINE: String = """{"t":"done"}"""

    /** Error line template (m = message, k = optional machine-readable kind). */
    fun errorLine(message: String): String =
        JSONObject().put("t", "error").put("m", message).toString()

    /**
     * Typed error line: carries a machine-readable kind alongside the human
     * message so the client can classify the failure WITHOUT re-parsing raw
     * error text. `k` is optional — consumers must treat a missing kind as
     * "unknown" (backward compatible with older workers that only write m).
     */
    fun errorLine(message: String, kind: String?): String =
        JSONObject().put("t", "error").put("m", message)
            .putOpt("k", kind?.takeIf { it.isNotBlank() }).toString()

    /** Decode a single line back into a chunk, or null if unparseable. */
    fun decode(line: String): LLMStreamChunk? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val obj = try { JSONObject(t) } catch (_: Exception) { return null }
        return when (obj.optString("t", "")) {
            "started" -> LLMStreamChunk.Started
            "text" -> LLMStreamChunk.Text(obj.optString("v", ""))
            "td" -> LLMStreamChunk.ThinkingDelta(obj.optString("v", ""))
            "rc" -> LLMStreamChunk.ReasoningContent(obj.optString("v", ""))
            "tu_start" -> LLMStreamChunk.ToolUseStart(obj.optString("id", ""), obj.optString("name", ""))
            "tu_delta" -> LLMStreamChunk.ToolInputDelta(obj.optString("id", ""), obj.optString("v", ""))
            "tu_done" -> LLMStreamChunk.ToolCallComplete(
                obj.optString("id", ""),
                obj.optString("name", ""),
                obj.optJSONObject("args") ?: JSONObject(),
            )
            "usage" -> LLMStreamChunk.Usage(
                LLMUsage(
                    inputTokens = obj.optInt("in", 0),
                    outputTokens = obj.optInt("out", 0),
                    cacheCreationInputTokens = obj.optInt("cache_creation", 0).takeIf { it > 0 },
                    cacheReadInputTokens = obj.optInt("cache_read", 0).takeIf { it > 0 },
                    latestContextTokens = obj.optInt("ctx", 0),
                )
            )
            "finished" -> LLMStreamChunk.Finished(
                stopReason = obj.optString("stop", "").ifEmpty { null },
                truncated = obj.optBoolean("truncated", false),
            )
            "media" -> {
                val b64 = obj.optString("b64", "")
                LLMStreamChunk.MediaAttachment(
                    LLMMediaAttachment(
                        type = LLMMediaAttachment.MediaType.values()
                            .firstOrNull { it.value == obj.optString("type", "") }
                            ?: LLMMediaAttachment.MediaType.IMAGE,
                        mimeType = obj.optString("mime", "application/octet-stream"),
                        data = if (b64.isNotEmpty()) java.util.Base64.getDecoder().decode(b64) else ByteArray(0),
                    )
                )
            }
            "error" -> null // errors are surfaced by the handler, not as chunks
            "done" -> LLMStreamChunk.Finished(stopReason = null, truncated = false)
            else -> null
        }
    }

    /** True if the line carries a stream-terminal signal. */
    fun isTerminal(line: String): Boolean {
        val t = line.trim()
        return t == DONE_LINE || (runCatching { JSONObject(t).optString("t", "") == "error" }.getOrElse { false })
    }

    /** True if the line is the clean-completion marker. */
    fun isDone(line: String): Boolean = line.trim() == DONE_LINE

    /** True if the line is an error signal. */
    fun isError(line: String): Boolean =
        runCatching { JSONObject(line.trim()).optString("t", "") == "error" }.getOrElse { false }

    /** Extract the message from an error line. */
    fun errorMessage(line: String): String =
        runCatching { JSONObject(line.trim()).optString("m", "stream_failed") }.getOrElse { "stream_failed" }

    /**
     * Extract the machine-readable kind from an error line ("network",
     * "rate_limited", …), or null when absent (legacy worker / untyped path).
     * [ChatStreamErrorPolicy] decides how to treat a null kind.
     */
    fun errorKind(line: String): String? =
        runCatching { JSONObject(line.trim()).optString("k", "").ifEmpty { null } }.getOrElse { null }
}