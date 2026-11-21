package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.AnthropicReplayPublicProjection
import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AnthropicReplay
import com.rikkaminis.app.data.model.AnthropicReplayCodec
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.repository.ChatRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import java.security.MessageDigest

/** Attempt-local evidence, never a run/bubble-level cache. Only successful collect may finish it. */
internal class AnthropicReplayAttempt {
    private var terminal: LLMStreamChunk.Finished? = null
    private var terminals = 0
    private var afterTerminal = false
    private val starts = mutableListOf<Pair<String, String>>()
    private val completes = mutableListOf<Pair<String, String>>()

    fun observe(chunk: LLMStreamChunk) {
        if (terminals != 0 && chunk !is LLMStreamChunk.QueueStatus) afterTerminal = true
        when (chunk) {
            is LLMStreamChunk.ToolUseStart -> starts.add(chunk.id to chunk.name)
            is LLMStreamChunk.ToolCallComplete -> completes.add(chunk.id to chunk.name)
            is LLMStreamChunk.Finished -> { terminals++; terminal = chunk }
            else -> Unit
        }
    }

    fun finish(calls: List<Triple<String, String, JSONObject>>): AnthropicReplay? {
        val end = terminal ?: return null
        if (terminals != 1 || afterTerminal || end.stopReason != "tool_use" || end.truncated) return null
        val replay = end.anthropicReplay?.takeIf { it.state == AnthropicReplay.State.COMPLETE } ?: return null
        val native = nativeTools(replay)
        val actual = calls.map { it.first to it.second }
        if (native.isEmpty() || native != starts || native != completes || native != actual ||
            native.map { it.first }.toSet().size != native.size) return null
        return replay.takeIf { replayMatchesPublic(it, calls.map { (id, name, args) ->
            AgentContentPart.ToolUse(id, name, args)
        }, checkText = false) }
    }
}

private fun nativeTools(replay: AnthropicReplay): List<Pair<String, String>> =
    replay.blocksCopy().orEmpty().mapNotNull { block ->
        val obj = block as? JsonObject ?: return@mapNotNull null
        if (obj.string("type") == "tool_use") obj.string("id")!! to obj.string("name")!! else null
    }

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Compare the PUBLIC projection only. The opaque payload never passes through org.json. */
internal fun replayMatchesPublic(
    replay: AnthropicReplay,
    parts: List<AgentContentPart>,
    checkText: Boolean = true,
): Boolean {
    if (replay.state != AnthropicReplay.State.COMPLETE ||
        parts.any { it !is AgentContentPart.Text && it !is AgentContentPart.ToolUse }) return false
    val blocks = replay.blocksCopy() ?: return false
    if (checkText && blocks.filterIsInstance<JsonObject>().filter { it.string("type") == "text" }
            .joinToString("") { it.string("text").orEmpty() } !=
        parts.filterIsInstance<AgentContentPart.Text>().joinToString("") { it.text }) return false
    if (checkText && publicOrder(blocks) != partsOrder(parts)) return false
    val native = blocks.filterIsInstance<JsonObject>().filter { it.string("type") == "tool_use" }
    val tools = parts.filterIsInstance<AgentContentPart.ToolUse>()
    return native.isNotEmpty() && native.size == tools.size && native.indices.all { i ->
        native[i].string("id") == tools[i].id && native[i].string("name") == tools[i].name &&
            publicInputEquals(native[i]["input"], tools[i].input)
    }
}

private fun publicOrder(blocks: JsonArray): List<Pair<String, String>> = blocks.mapNotNull {
    val obj = it as? JsonObject ?: return@mapNotNull null
    when (obj.string("type")) {
        "text" -> obj.string("text")?.takeIf { text -> text.isNotEmpty() }?.let { text -> "text" to text }
        "tool_use" -> "tool" to obj.string("id").orEmpty()
        else -> null
    }
}.coalesceText()

private fun partsOrder(parts: List<AgentContentPart>): List<Pair<String, String>> = parts.mapNotNull {
    when (it) {
        is AgentContentPart.Text -> it.text.takeIf { text -> text.isNotEmpty() }?.let { text -> "text" to text }
        is AgentContentPart.ToolUse -> "tool" to it.id
        else -> null
    }
}.coalesceText()

private fun List<Pair<String, String>>.coalesceText(): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    for (part in this) {
        if (part.first == "text" && out.lastOrNull()?.first == "text") {
            out[out.lastIndex] = "text" to (out.last().second + part.second)
        } else out.add(part)
    }
    return out
}

// This is ONLY the legacy display/execution projection comparison. The capsule
// itself is always encoded/decoded by the exact kotlinx tree. Android may round
// numbers in the public JSONObject; compare against that same projection, never
// feed its rounded tokens back into the native payload.
private fun publicInputEquals(expected: kotlinx.serialization.json.JsonElement?, actual: JSONObject): Boolean = try {
    samePublicValue(JSONObject(expected.toString()), actual)
} catch (_: Exception) { false }

private fun samePublicValue(a: Any?, b: Any?): Boolean = when {
    a is JSONObject && b is JSONObject -> {
        val keys = a.keys().asSequence().toSet()
        keys == b.keys().asSequence().toSet() && keys.all { samePublicValue(a.opt(it), b.opt(it)) }
    }
    a is org.json.JSONArray && b is org.json.JSONArray -> a.length() == b.length() &&
        (0 until a.length()).all { samePublicValue(a.opt(it), b.opt(it)) }
    a is Number && b is Number -> a.toString() == b.toString()
    else -> a == b
}

/** No new public part variant: one private envelope appended after the old byte-stable array. */
internal fun appendAnthropicReplayPart(publicJson: String, replay: AnthropicReplay?): String {
    if (replay?.state != AnthropicReplay.State.COMPLETE) return publicJson
    val public = try { Json.parseToJsonElement(publicJson) as? JsonArray } catch (_: Exception) { null }
        ?: return publicJson
    val privatePart = JsonObject(linkedMapOf(
        "type" to JsonPrimitive(AnthropicReplayPublicProjection.PART_TYPE),
        "value" to AnthropicReplayCodec.encode(replay),
        // A consistency stamp, NOT authentication or preserved-prefix proof.
        "publicSha256" to JsonPrimitive(publicStamp(public)),
    ))
    val combined = publicJson.dropLast(1) + (if (public.isEmpty()) "" else ",") + privatePart + "]"
    return if (combined.toByteArray(Charsets.UTF_8).size <= ChatRepository.MAX_MESSAGE_PARTS_JSON_BYTES) {
        combined
    } else publicJson // Never let metadata push a good public row through the destructive row cap.
}

private fun publicStamp(parts: JsonArray): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Separate metadata read: no ParsedPart/UI exhaustive-when expansion, no lossy numeric tree. */
internal fun readAnthropicReplayPart(raw: String, role: String, parts: List<ParsedPart>): AnthropicReplay? {
    if (raw.length > ChatRepository.MAX_MESSAGE_PARTS_JSON_BYTES || role != "assistant" ||
        raw.toByteArray(Charsets.UTF_8).size > ChatRepository.MAX_MESSAGE_PARTS_JSON_BYTES) return null
    val array = try { Json.parseToJsonElement(raw) as? JsonArray } catch (_: Exception) { null } ?: return null
    val privateParts = array.filterIsInstance<JsonObject>().filter {
        it.string("type") == AnthropicReplayPublicProjection.PART_TYPE
    }
    val privatePart = privateParts.singleOrNull() ?: return null
    if (privatePart.keys != setOf("type", "value", "publicSha256")) return null
    val public = JsonArray(array.filterNot { it === privatePart })
    if (privatePart.string("publicSha256") != publicStamp(public)) return null
    val replay = AnthropicReplayCodec.decode(privatePart["value"])
    if (replay.state != AnthropicReplay.State.COMPLETE) return null
    val native = replay.blocksCopy()!!.filterIsInstance<JsonObject>()
    if (parts.any { it !is ParsedPart.Text && it !is ParsedPart.ToolUse } ||
        native.filter { it.string("type") == "text" }.joinToString("") { it.string("text").orEmpty() } !=
        parts.filterIsInstance<ParsedPart.Text>().joinToString("") { it.value }) return null
    val tools = parts.filterIsInstance<ParsedPart.ToolUse>()
    val uses = native.filter { it.string("type") == "tool_use" }
    if (uses.isEmpty() || uses.size != tools.size || uses.indices.any { i ->
            uses[i].string("id") != tools[i].id || uses[i].string("name") != tools[i].name ||
                !try { publicInputEquals(uses[i]["input"], JSONObject(tools[i].input)) } catch (_: Exception) { false }
        }) return null
    val rawPublic = parts.mapNotNull { part -> when (part) {
        is ParsedPart.Text -> AgentContentPart.Text(part.value)
        is ParsedPart.ToolUse -> try { AgentContentPart.ToolUse(part.id, part.name, JSONObject(part.input)) } catch (_: Exception) { null }
        else -> null
    } }
    return replay.takeIf { rawPublic.size == parts.size && replayMatchesPublic(it, rawPublic) }
}

/** Pairing must be real BEFORE sanitize can invent placeholders. Changed projections lose metadata. */
internal fun guardAnthropicReplayHistory(
    messages: List<LLMMessage>,
    original: List<LLMMessage> = messages,
): List<LLMMessage> {
    val rewritten = messages.size != original.size || messages.indices.any { messages[it] !== original[it] }
    return messages.mapIndexed { index, message ->
        val replay = message.anthropicReplay ?: return@mapIndexed message
        val next = messages.getOrNull(index + 1)
        val uses = nativeTools(replay)
        val results = next?.contentParts?.filterIsInstance<AgentContentPart.ToolResult>().orEmpty()
        val paired = next?.role == LLMMessage.Role.USER && uses.isNotEmpty() &&
            uses == results.map { it.id to it.name } &&
            results.none { it.content == SANITIZE_PLACEHOLDER_RESULT_CONTENT }
        if (rewritten || message.role != LLMMessage.Role.ASSISTANT || !paired ||
            !replayMatchesPublic(replay, message.contentParts)) message.copy(anthropicReplay = null) else message
    }
}

internal fun clearAnthropicReplays(messages: MutableList<LLMMessage>) {
    for (i in messages.indices) if (messages[i].anthropicReplay != null) {
        messages[i] = messages[i].copy(anthropicReplay = null)
    }
}
