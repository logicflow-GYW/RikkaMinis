package com.rikkaminis.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URISyntaxException
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8

/** One immutable native assistant turn. The private capsule is not a display projection. */
class AnthropicReplay internal constructor(
    val state: State,
    val source: Source?,
    val blockCount: Int,
    val encodedBytes: Int,
    private val capsule: String,
) {
    enum class State { ABSENT, COMPLETE, UNAVAILABLE }

    /** Actual request lane identity, not a credential or proof of replay eligibility. */
    class Source private constructor(
        val endpoint: String,
        val model: String,
        val instanceId: String?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Source && endpoint == other.endpoint && model == other.model && instanceId == other.instanceId

        override fun hashCode(): Int = 31 * (31 * endpoint.hashCode() + model.hashCode()) + (instanceId?.hashCode() ?: 0)

        override fun toString(): String = "AnthropicReplay.Source(instanceBound=${instanceId != null})"

        companion object {
            private const val HOST = "api.anthropic.com"
            private const val PATH = "/v1/messages"
            private const val ENDPOINT = "https://$HOST$PATH"

            fun fromActualRequest(url: String, model: String, instanceId: String? = null): Source? {
                if (!validId(model, 256) || (instanceId != null && !validId(instanceId, 128))) return null
                val uri = try { URI(url) } catch (_: URISyntaxException) { return null }
                if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals(HOST, ignoreCase = true) ||
                    uri.rawPath != PATH || uri.rawQuery != null || uri.rawUserInfo != null || uri.rawFragment != null ||
                    !(uri.port == 443 || (uri.port == -1 && uri.rawAuthority.equals(HOST, ignoreCase = true)))
                ) return null
                return Source(ENDPOINT, model, instanceId)
            }

            private fun validId(value: String, limit: Int): Boolean =
                value.isNotEmpty() && value.length <= limit && value.none { it.isWhitespace() || it.isISOControl() } &&
                    UTF_8.newEncoder().canEncode(value) && value.toByteArray(UTF_8).size <= limit
        }
    }

    /** A fresh parsed copy, including nested values, only for a complete capsule. */
    fun blocksCopy(): JsonArray? = if (state == State.COMPLETE) jsonCopy()["blocks"] as JsonArray else null

    internal fun jsonCopy(): JsonObject = Json.parseToJsonElement(capsule).jsonObject

    override fun toString(): String = "AnthropicReplay(state=$state, blocks=$blockCount, bytes=$encodedBytes)"
}

/** One versioned structured codec for the IPC and future private persistence boundaries. */
object AnthropicReplayCodec {
    const val FIELD = "anthropic_replay"
    // A whole capsule must leave headroom for enclosing IPC and Room JSON, not raise their limits.
    const val MAX_CAPSULE_BYTES = 128 * 1024
    private const val MAX_DEPTH = 64
    private val version = JsonPrimitive(1)
    private val jsonNumber = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
    private val absent = status(AnthropicReplay.State.ABSENT, "absent")
    private val unavailable = status(AnthropicReplay.State.UNAVAILABLE, "unavailable")

    fun absent(): AnthropicReplay = absent
    fun unavailable(): AnthropicReplay = unavailable

    fun complete(source: AnthropicReplay.Source, blocks: JsonArray): AnthropicReplay {
        if (blocks.isEmpty() || !blocks.all { it is JsonObject && validBlock(it) }) return unavailable
        val obj = buildJsonObject {
            put("version", version)
            put("state", "complete")
            put("source", buildJsonObject {
                put("endpoint", source.endpoint)
                put("model", source.model)
                source.instanceId?.let { put("instanceId", it) }
            })
            put("blocks", blocks)
        }
        if (!boundedTree(obj)) return unavailable
        val capsule = obj.toString()
        val bytes = try { UTF_8.newEncoder().encode(CharBuffer.wrap(capsule)).remaining() }
        catch (_: CharacterCodingException) { return unavailable }
        if (bytes > MAX_CAPSULE_BYTES) return unavailable
        return AnthropicReplay(AnthropicReplay.State.COMPLETE, source, blocks.size, bytes, capsule)
    }

    fun encode(value: AnthropicReplay): JsonObject = value.jsonCopy()

    /** Missing enclosing keys are handled by the caller; a present null is invalid. */
    fun decode(value: JsonElement?): AnthropicReplay {
        val obj = value as? JsonObject ?: return unavailable
        if (obj["version"] != version) return unavailable
        return when (obj.string("state")) {
            "absent" -> if (obj.size == 2) absent else unavailable
            "unavailable" -> unavailable
            "complete" -> {
                if (obj.size != 4) return unavailable
                val source = (obj["source"] as? JsonObject)?.let(::decodeSource) ?: return unavailable
                val blocks = obj["blocks"] as? JsonArray ?: return unavailable
                complete(source, blocks)
            }
            else -> unavailable
        }
    }

    private fun status(state: AnthropicReplay.State, wireName: String): AnthropicReplay {
        val json = buildJsonObject { put("version", version); put("state", wireName) }.toString()
        return AnthropicReplay(state, null, 0, json.length, json) // ASCII-only envelope
    }

    private fun decodeSource(obj: JsonObject): AnthropicReplay.Source? {
        val endpoint = obj.string("endpoint") ?: return null
        val model = obj.string("model") ?: return null
        val instanceId = if ("instanceId" in obj) obj.string("instanceId") ?: return null else null
        if (obj.size != (if (instanceId == null) 2 else 3)) return null
        return AnthropicReplay.Source.fromActualRequest(endpoint, model, instanceId)?.takeIf { it.endpoint == endpoint }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun validBlock(block: JsonObject): Boolean {
        val keys = when (block.string("type")) {
            "thinking" -> if (block.string("thinking") != null && !block.string("signature").isNullOrBlank()) {
                setOf("thinking", "signature")
            } else return false
            "redacted_thinking" -> if (!block.string("data").isNullOrBlank()) setOf("data") else return false
            "text" -> {
                if (block.string("text") == null) return false
                val citations = block["citations"]
                if (citations != null && citations != JsonNull &&
                    !(citations is JsonArray && citations.all { it is JsonObject })) return false
                setOf("text", "citations")
            }
            "tool_use" -> {
                if (block.string("id").isNullOrBlank() || block.string("name").isNullOrBlank() ||
                    block["input"] !is JsonObject) return false
                setOf("id", "name", "input")
            }
            else -> return false
        }
        if (!block.keys.all { it == "type" || it == "cache_control" || it in keys }) return false
        val control = block["cache_control"] ?: return true
        return control is JsonObject && control.string("type") == "ephemeral" &&
            (control.size == 1 || (control.size == 2 && control.string("ttl") in setOf("5m", "1h")))
    }

    /** Reject deep/non-JSON input and large trees before allocating their compact encoding. */
    private fun boundedTree(root: JsonObject): Boolean {
        var remaining = MAX_CAPSULE_BYTES
        fun charge(chars: Int): Boolean {
            if (chars > remaining) return false
            remaining -= chars
            return true
        }
        fun valid(value: JsonElement, depth: Int): Boolean {
            if (depth > MAX_DEPTH) return false
            // Cheap lower bound; UTF-8 and escape expansion are checked after encoding.
            return when (value) {
                is JsonObject -> charge(2) && value.all { (key, child) ->
                    charge(3) && charge(key.length) && valid(child, depth + 1)
                }
                is JsonArray -> charge(2) && value.all { valid(it, depth + 1) }
                is JsonPrimitive -> charge(value.content.length) && when {
                    value.isString -> charge(2)
                    value == JsonNull || value.content == "true" || value.content == "false" -> true
                    else -> jsonNumber.matches(value.content)
                }
            }
        }
        return valid(root, 0)
    }
}
