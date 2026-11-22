package com.rikkaminis.app.provider.anthropic

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AnthropicReplay
import com.rikkaminis.app.data.model.AnthropicReplayCodec
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.ui.chat.SANITIZE_PLACEHOLDER_RESULT_CONTENT
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject

private const val CAPTURE_CHARS = AnthropicReplayCodec.MAX_CAPSULE_BYTES * 2 // framing/escaping headroom
internal const val ANTHROPIC_ERROR_BYTES = 16L * 1024

internal fun JsonObject.nativeString(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Size/depth preflight before asking the existing JSON parser to build a tree. */
private fun exactObject(raw: String, limit: Int = CAPTURE_CHARS): JsonObject? {
    if (raw.length > limit) return null
    var depth = 0
    var quoted = false
    var escaped = false
    for (ch in raw) {
        when {
            escaped -> escaped = false
            quoted && ch == '\\' -> escaped = true
            ch == '"' -> quoted = !quoted
            quoted -> Unit
            ch == '{' || ch == '[' -> if (++depth > 65) return null
            ch == '}' || ch == ']' -> if (--depth < 0) return null
        }
    }
    if (quoted || depth != 0) return null
    return try { Json.parseToJsonElement(raw) as? JsonObject } catch (_: IllegalArgumentException) { null }
}

internal fun unavailableReplay(reason: String): AnthropicReplay {
    // Callers supply fixed reason codes, never a rejected field, body or URL.
    android.util.Log.i("AnthropicProvider", "Native replay unavailable: $reason")
    return AnthropicReplayCodec.unavailable()
}

private fun completeNativeMessage(source: AnthropicReplay.Source, blocks: JsonArray, stop: String?): AnthropicReplay {
    if (stop !in setOf("end_turn", "tool_use", "stop_sequence") ||
        (stop == "tool_use") != blocks.any { (it as? JsonObject)?.nativeString("type") == "tool_use" }
    ) return unavailableReplay("unclean_stop")
    val value = AnthropicReplayCodec.complete(source, blocks)
    return if (value.state == AnthropicReplay.State.COMPLETE) value else unavailableReplay("invalid_native_blocks")
}

internal fun captureNativeResponse(raw: String, source: AnthropicReplay.Source): AnthropicReplay {
    val message = exactObject(raw)
    val blocks = message?.get("content") as? JsonArray
    if (message?.nativeString("type") != "message" || message.nativeString("role") != "assistant" || blocks == null) {
        return unavailableReplay("invalid_message")
    }
    return completeNativeMessage(source, blocks, message.nativeString("stop_reason"))
}

/** Messages SSE has serial, zero-based block lifecycles. Lose the entire capsule on any gap. */
internal class AnthropicNativeStream {
    private enum class Phase { START, BLOCKS, MESSAGE_DELTA, STOPPED }

    private var phase = Phase.START
    private var stopReason: String? = null
    private var failure: String? = null
    private var remaining = AnthropicReplayCodec.MAX_CAPSULE_BYTES
    private val closed = mutableListOf<JsonObject>()
    private var open: Block? = null

    fun reject(reason: String) {
        if (failure == null) failure = reason
        closed.clear()
        open = null
    }

    private fun charge(chars: Int): Boolean {
        remaining -= chars
        if (remaining < 0) reject("native_budget")
        return remaining >= 0
    }

    fun accept(raw: String) {
        if (failure != null) return
        val event = exactObject(raw) ?: return reject("invalid_event")
        val type = event.nativeString("type")
        if (type == "ping") return
        if (phase == Phase.STOPPED) return reject("event_after_message_stop")
        when (type) {
            "message_start" -> {
                val message = event["message"] as? JsonObject
                if (phase != Phase.START || message?.nativeString("type") != "message" ||
                    message.nativeString("role") != "assistant" || message["content"] != JsonArray(emptyList())
                ) return reject("invalid_message_start")
                phase = Phase.BLOCKS
            }
            "content_block_start", "content_block_delta", "content_block_stop" -> {
                val index = event["index"] as? JsonPrimitive
                if (phase != Phase.BLOCKS || index == null || index.isString || index.content != closed.size.toString()) {
                    return reject("invalid_block_index_or_phase")
                }
                when (type) {
                    "content_block_start" -> {
                        val initial = event["content_block"] as? JsonObject
                        if (open != null || initial?.nativeString("type") !in setOf("thinking", "redacted_thinking", "text", "tool_use")) {
                            return reject("invalid_block_start")
                        }
                        if (initial != null && charge(initial.toString().length)) open = Block(initial)
                    }
                    "content_block_delta" -> {
                        val block = open ?: return reject("delta_without_open_block")
                        val delta = event["delta"] as? JsonObject ?: return reject("invalid_block_delta")
                        val field = when (delta.nativeString("type") to block.initial.nativeString("type")) {
                            "thinking_delta" to "thinking" -> "thinking"
                            "signature_delta" to "thinking" -> "signature"
                            "text_delta" to "text" -> "text"
                            "input_json_delta" to "tool_use" -> "partial_json"
                            "citations_delta" to "text" -> {
                                val citation = delta["citation"] as? JsonObject ?: return reject("invalid_citation")
                                if (charge(citation.toString().length)) block.citations.add(citation)
                                return
                            }
                            else -> return reject("unsupported_block_delta")
                        }
                        val value = delta.nativeString(field) ?: return reject("invalid_block_delta")
                        if (charge(value.length)) block.fragments.getOrPut(field, ::StringBuilder).append(value)
                    }
                    "content_block_stop" -> {
                        val block = open?.finish() ?: return reject("invalid_block_stop_or_input")
                        closed.add(block)
                        open = null
                    }
                }
            }
            "message_delta" -> {
                if (phase == Phase.START || open != null) return reject("message_delta_before_blocks_closed")
                val delta = event["delta"] as? JsonObject ?: return reject("invalid_message_delta")
                val rawStop = delta["stop_reason"]
                if (rawStop != null && rawStop != JsonNull) {
                    if (stopReason != null) return reject("duplicate_stop_reason")
                    stopReason = delta.nativeString("stop_reason") ?: return reject("invalid_stop_reason")
                }
                phase = Phase.MESSAGE_DELTA
            }
            "message_stop" -> {
                if (phase != Phase.MESSAGE_DELTA || stopReason == null) return reject("premature_message_stop")
                phase = Phase.STOPPED
            }
            else -> reject("unsupported_event")
        }
    }

    fun finish(source: AnthropicReplay.Source, truncated: Boolean): AnthropicReplay {
        failure?.let { return unavailableReplay(it) }
        if (phase != Phase.STOPPED || open != null || truncated) return unavailableReplay("incomplete_stream")
        return completeNativeMessage(source, JsonArray(closed), stopReason)
    }

    private class Block(val initial: JsonObject) {
        val fragments = mutableMapOf<String, StringBuilder>()
        val citations = mutableListOf<JsonObject>()

        fun finish(): JsonObject? {
            val fields = initial.toMutableMap()
            for ((field, value) in fragments) {
                if (field == "partial_json") {
                    // Official streamed arguments start at {}. Never repair an incomplete object
                    // or guess how deltas should modify a nonempty announcement.
                    if (initial["input"] != JsonObject(emptyMap())) return null
                    fields["input"] = exactObject(value.toString()) ?: return null
                } else {
                    val base = initial.nativeString(field)
                        ?: if (field == "signature" && field !in initial) "" else return null
                    fields[field] = JsonPrimitive(base + value)
                }
            }
            if (citations.isNotEmpty()) {
                val base = initial["citations"]
                if (base != null && base != JsonNull && base !is JsonArray) return null
                fields["citations"] = JsonArray((base as? JsonArray).orEmpty() + citations)
            }
            return JsonObject(fields)
        }
    }
}

// The official prefix-check contract is not the same as the adaptive-thinking switch.
// No system/tools/history proof reaches this Provider, so preserved/unknown IDs never replay.
private val knownSignatureModels = setOf(
    "claude-haiku-4-5", "claude-haiku-4-5-20251001", "claude-sonnet-4-6", "claude-opus-5",
)
private val nativeToolId = Regex("[A-Za-z0-9_-]+")

/** Find only the active tail of real tool pairs. A hole in that tail disqualifies the whole run. */
internal fun nativeToolReplay(
    messages: List<LLMMessage>,
    sanitized: List<LLMMessage>,
    retained: List<Int>,
    source: AnthropicReplay.Source?,
    thinkingType: String?,
): Map<Int, JsonArray> {
    fun decline(reason: String): Map<Int, JsonArray> {
        unavailableReplay(reason)
        return emptyMap()
    }
    if (source == null) return decline("request_source")
    if (source.model !in knownSignatureModels) return decline("model_prefix_unproven")
    if (thinkingType != "enabled" && thinkingType != "adaptive") return decline("thinking_not_enabled")
    val retainedSet = retained.toSet()
    val replay = mutableMapOf<Int, JsonArray>()
    var i = messages.lastIndex - 1
    while (i >= 0) {
        val user = messages[i + 1]
        if (user.role != LLMMessage.Role.USER || user.contentParts.none { it is AgentContentPart.ToolResult }) break
        val assistant = messages[i]
        if (assistant.role != LLMMessage.Role.ASSISTANT || assistant != sanitized[i] || user != sanitized[i + 1] ||
            i !in retainedSet || i + 1 !in retainedSet ||
            retained.lastOrNull { it < i }?.let { messages[it].role == LLMMessage.Role.ASSISTANT } == true
        ) return decline("changed_tool_pair")
        val capsule = assistant.anthropicReplay
        if (capsule?.source != source) return decline("capsule_source_or_state")
        val blocks = capsule.blocksCopy() ?: return decline("capsule_source_or_state")
        if (!matchesNativeProjection(blocks, assistant, user)) return decline("changed_tool_pair")
        replay[i] = blocks
        i -= 2
    }
    return replay.ifEmpty { decline("not_active_tool_turn") }
}

private fun matchesNativeProjection(blocks: JsonArray, assistant: LLMMessage, user: LLMMessage): Boolean {
    if (assistant.imageParts.isNotEmpty() || assistant.audioParts.isNotEmpty()) return false
    val native = blocks.filterIsInstance<JsonObject>() // the codec already checked every block
    val text = native.mapNotNull { it.nativeString("text") }.joinToString("")
    val thinking = native.mapNotNull { it.nativeString("thinking") }.joinToString("")
    if ((assistant.content.isNotEmpty() && assistant.content != text) ||
        (assistant.reasoningContent != null && assistant.reasoningContent != thinking) ||
        assistant.contentParts.any { it !is AgentContentPart.Text && it !is AgentContentPart.ToolUse } ||
        assistant.contentParts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text } != text
    ) return false

    val tools = native.filter { it.nativeString("type") == "tool_use" }
    val uses = assistant.contentParts.filterIsInstance<AgentContentPart.ToolUse>()
    val results = user.contentParts.takeWhile { it is AgentContentPart.ToolResult }.filterIsInstance<AgentContentPart.ToolResult>()
    if (tools.isEmpty() || uses.size != tools.size || results.size != tools.size ||
        results.size != user.contentParts.count { it is AgentContentPart.ToolResult } ||
        results.map { it.id }.toSet().size != results.size
    ) return false
    // Image budgeting/placeholder conversion happens later in the public serializer.
    // Without proof of that result's unchanged payload, decline only the private capsule;
    // the ordinary tool-result serializer still owns and preserves the image bytes.
    if (results.any { it.imageData != null }) return false
    val resultsById = results.associateBy { it.id }
    return tools.zip(uses).all { (tool, use) ->
        val result = resultsById[use.id]
        if (result == null || result.name != use.name || result.content == SANITIZE_PLACEHOLDER_RESULT_CONTENT ||
            !nativeToolId.matches(use.id) || tool.nativeString("id") != use.id || tool.nativeString("name") != use.name
        ) return@all false
        // Compare with the existing execution/public projection to detect edits/repairs,
        // including on Android where org.json may round numbers. Never use that
        // projection as the outbound value: only `blocks` goes on the native wire.
        val projected = try { exactObject(JSONObject(tool.getValue("input").toString()).toString()) }
        catch (_: org.json.JSONException) { null }
        projected != null && projected == exactObject(use.input.toString())
    } && uses.map { it.id }.toSet().size == uses.size
}

private val privateErrorField = Regex("(?i)\\b(signature(?:_delta)?|redacted_thinking|thinking(?:_delta)?|data|partial_json|anthropic_replay)\\b")

/** No raw-body or exception-message fallback. An API error may echo private request blocks. */
internal fun publicAnthropicError(raw: String, privateContext: Boolean): String {
    val obj = exactObject(raw, ANTHROPIC_ERROR_BYTES.toInt()) ?: return "Unstructured or oversized Anthropic error body omitted"
    val error = obj["error"] as? JsonObject
    val type = error?.nativeString("type")?.takeIf {
        it == "invalid_request_error" || anthropicStreamErrorStatus(it) != 400
    } ?: "error"
    fun containsPrivateField(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.any { (key, child) -> privateErrorField.matches(key) || containsPrivateField(child) }
        is JsonArray -> value.any(::containsPrivateField)
        else -> false
    }
    val message = sequenceOf(error?.nativeString("message"), obj.nativeString("error"),
        obj.nativeString("message"), obj.nativeString("msg"))
        .filterNotNull().firstOrNull { it.isNotBlank() }
    if (privateContext || containsPrivateField(obj) || (message != null && privateErrorField.containsMatchIn(message))) {
        return "[$type] Anthropic error details withheld (private content)"
    }
    return "[$type] ${message?.take(500) ?: "No public error message"}"
}
