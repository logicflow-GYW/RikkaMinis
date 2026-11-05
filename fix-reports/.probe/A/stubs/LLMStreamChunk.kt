package com.rikkaminis.app.data.model

import org.json.JSONObject

/** Probe stub — verbatim copy of the production sealed class shape (the probe
 *  only pattern-matches on these variants). */
sealed class LLMStreamChunk {
    data object Started : LLMStreamChunk()
    data class Text(val text: String) : LLMStreamChunk()
    data class Usage(val usage: LLMUsage) : LLMStreamChunk()
    data class Finished(val stopReason: String?, val truncated: Boolean = false) : LLMStreamChunk()
    data class ThinkingDelta(val text: String) : LLMStreamChunk()
    data class ReasoningContent(val content: String) : LLMStreamChunk()
    data class ToolUseStart(val id: String, val name: String) : LLMStreamChunk()
    data class ToolInputDelta(val id: String, val accumulated: String) : LLMStreamChunk()
    data class ToolCallComplete(val id: String, val name: String, val args: JSONObject) : LLMStreamChunk()
    data class MediaAttachment(val attachment: LLMMediaAttachment) : LLMStreamChunk()
    data class QueueStatus(val waiting: Int) : LLMStreamChunk()
}

data class LLMMediaAttachment(
    val type: MediaType,
    val mimeType: String,
    val data: ByteArray,
) {
    enum class MediaType(val value: String) { IMAGE("image"), AUDIO("audio"), VIDEO("video") }
}
