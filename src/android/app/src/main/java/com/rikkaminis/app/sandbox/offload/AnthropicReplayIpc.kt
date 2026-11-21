package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.data.model.AnthropicReplay
import com.rikkaminis.app.data.model.AnthropicReplayCodec
import com.rikkaminis.app.data.model.AnthropicReplayCodec.FIELD
import com.rikkaminis.app.data.model.LLMMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The optional structured value is the only part of the legacy IPC that needs exact JSON. */
internal object AnthropicReplayIpc {
    fun encodeRequest(publicBody: JSONObject, messages: List<LLMMessage>): String {
        val raw = publicBody.toString()
        if (messages.none { it.anthropicReplay != null }) return raw
        val body = Json.parseToJsonElement(raw).jsonObject
        val wireMessages = body.getValue("messages").jsonArray.mapIndexed { index, message ->
            val replay = messages[index].anthropicReplay
            if (replay == null) message
            else JsonObject(message.jsonObject + (FIELD to AnthropicReplayCodec.encode(replay)))
        }
        return JsonObject(body + ("messages" to JsonArray(wireMessages))).toString()
    }

    fun encodeResult(publicBody: JSONObject, replay: AnthropicReplay?): String {
        val raw = publicBody.toString()
        if (replay == null) return raw
        val body = Json.parseToJsonElement(raw).jsonObject
        return JsonObject(body + (FIELD to AnthropicReplayCodec.encode(replay))).toString()
    }

    fun parseRequest(raw: String): JSONObject {
        if (!mayContainReplay(raw)) return JSONObject(raw)
        val exact = exactObject(raw)
        val messages = exact?.get("messages") as? JsonArray
        if (exact != null && messages != null && messages.any { it is JsonObject && FIELD in it }) {
            val replays = messages.map { message ->
                (message as? JsonObject)?.takeIf { FIELD in it }?.let { AnthropicReplayCodec.decode(it[FIELD]) }
            }
            // Strip before JSONObject even reads it: Android can reject 1e400,
            // not merely round a number when serializing it back out.
            val publicMessages = JsonArray(messages.map { if (it is JsonObject) withoutReplay(it) else it })
            val publicBody = JSONObject(JsonObject(exact + ("messages" to publicMessages)).toString())
            return publicBody.withReplayMessages(replays)
        }

        val publicBody = try { JSONObject(raw) } catch (_: JSONException) {
            // Some parser errors quote the whole input. Never pass a suspect
            // private request (including any parse exception's cause) to logs.
            throw JSONException("Invalid model execution request JSON")
        }
        val legacyMessages = publicBody.optJSONArray("messages") ?: return publicBody
        // Legacy-only JSON spellings may be accepted by JSONObject. Presence
        // is still meaningful, but its lossy value is not eligible for replay.
        if ((0 until legacyMessages.length()).none { legacyMessages.optJSONObject(it)?.has(FIELD) == true }) {
            return publicBody
        }
        val unavailable = (0 until legacyMessages.length()).map { index ->
            val message = legacyMessages.optJSONObject(index)
            if (message?.has(FIELD) != true) null else {
                message.remove(FIELD)
                AnthropicReplayCodec.unavailable()
            }
        }
        return publicBody.withReplayMessages(unavailable)
    }

    fun messageReplay(messages: JSONArray?, index: Int): AnthropicReplay? =
        (messages as? ReplayMessages)?.replayAt(index)

    // Keep the existing parsed-JSONObject handoff. Private data belongs to
    // this run's message array and never participates in its JSON projection.
    private fun JSONObject.withReplayMessages(replays: List<AnthropicReplay?>): JSONObject = apply {
        put("messages", ReplayMessages(getJSONArray("messages"), replays))
    }

    private class ReplayMessages(publicMessages: JSONArray, private val replays: List<AnthropicReplay?>) : JSONArray() {
        init {
            for (index in 0 until publicMessages.length()) put(publicMessages.opt(index))
        }

        fun replayAt(index: Int): AnthropicReplay? = replays.getOrNull(index)
    }

    fun exactResultWithReplay(raw: String): JsonObject? =
        if (mayContainReplay(raw)) exactObject(raw)?.takeIf { FIELD in it } else null

    fun withoutReplay(body: JsonObject): JsonObject =
        if (FIELD in body) JsonObject(body - FIELD) else body

    /** model-use needs the legacy public fields, never the private carrier. */
    fun publicResultObject(raw: String): JSONObject =
        JSONObject(exactResultWithReplay(raw)?.let { withoutReplay(it).toString() } ?: raw).apply {
            remove(FIELD)
        }

    // The producer writes the literal key; accept escaped key spellings too.
    private fun mayContainReplay(raw: String): Boolean = FIELD in raw || "\\u" in raw

    private fun exactObject(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
}
