package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.data.model.AnthropicReplay
import com.rikkaminis.app.data.model.AnthropicReplay.State
import com.rikkaminis.app.data.model.AnthropicReplayCodec
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMResponse
import com.rikkaminis.app.data.model.LLMStreamChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicReplayCarrierTest {
    private val endpoint = "https://api.anthropic.com/v1/messages"
    private val source = checkNotNull(AnthropicReplay.Source.fromActualRequest(endpoint, "claude-fixture", "instance-1"))
    private val native = """
        [
          {"thinking":" e\u0301\n ","signature":" sig-first\\/ ","type":"thinking"},
          {"type":"redacted_thinking","data":" opaque-\u00e9\\u0065 "},
          {"type":"thinking","thinking":"","signature":"SIG-SECOND"},
          {"type":"text","text":"answer-\uD83D\uDE42","citations":null,"cache_control":{"ttl":"5m","type":"ephemeral"}},
          {"type":"tool_use","name":"weather","id":"tool-1","input":{
            "integer":9223372036854775809,"decimal":0.12345678901234567890123456789,"huge":1e400,
            "empty":"","nested":[false,null,{},[],"e\u0301"]
          }}
        ]
    """.trimIndent()

    private fun blocks(): JsonArray = Json.parseToJsonElement(native).jsonArray
    private fun capture() = AnthropicReplayCodec.complete(source, blocks())
    private fun finished(line: String) = ChatStreamJsonl.decode(line) as LLMStreamChunk.Finished
    private fun capsule(arr: String = native): String = """
        {"version":1,"state":"complete","source":{"endpoint":"$endpoint","model":"claude-fixture","instanceId":"instance-1"},"blocks":$arr}
    """.trimIndent()

    @Test
    fun finishedRoundTripKeepsEveryBlockAndTheTwoSignaturesInOrder() {
        val replay = capture()
        assertEquals(State.COMPLETE, replay.state)
        val line = ChatStreamJsonl.encode(LLMStreamChunk.Finished("tool_use", anthropicReplay = replay))
        assertFalse(line.contains('\n'))
        val wire = Json.parseToJsonElement(line).jsonObject.getValue("anthropic_replay").jsonObject
        assertEquals(5, wire.getValue("blocks").jsonArray.size)
        val decoded = finished(line)
        assertEquals("tool_use", decoded.stopReason)
        assertFalse(decoded.truncated)
        val received = checkNotNull(decoded.anthropicReplay)
        assertEquals(State.COMPLETE, received.state)
        assertEquals(source, received.source)
        val blocks = checkNotNull(received.blocksCopy())
        assertEquals(listOf("thinking", "redacted_thinking", "thinking", "text", "tool_use"),
            blocks.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(" e\u0301\n ", blocks[0].jsonObject.getValue("thinking").jsonPrimitive.content)
        assertEquals(" sig-first\\/ ", blocks[0].jsonObject.getValue("signature").jsonPrimitive.content)
        assertEquals(" opaque-é\\u0065 ", blocks[1].jsonObject.getValue("data").jsonPrimitive.content)
        assertEquals("", blocks[2].jsonObject.getValue("thinking").jsonPrimitive.content)
        assertEquals("SIG-SECOND", blocks[2].jsonObject.getValue("signature").jsonPrimitive.content)
        assertEquals(blocks(), blocks)
    }

    @Test
    fun toolArgumentsKeepPrecisionBeyondLongAndDouble() {
        // Exercise decode independently of encode, including an escaped envelope key.
        val received = checkNotNull(finished("""{"t":"finished","truncated":true,"anthropic_\u0072eplay":${capsule()}}""").anthropicReplay)
        assertEquals(State.COMPLETE, received.state)
        val args = received.blocksCopy()!![4].jsonObject.getValue("input").jsonObject
        val exactTokens = mapOf("integer" to "9223372036854775809", "decimal" to "0.12345678901234567890123456789", "huge" to "1e400")
        exactTokens.forEach { (key, literal) ->
            assertFalse(args.getValue(key).jsonPrimitive.isString)
            assertEquals(literal, args.getValue(key).jsonPrimitive.content)
        }
        val line = ChatStreamJsonl.encode(LLMStreamChunk.Finished(null, true, received))
        val reloaded = finished(line)
        assertTrue(reloaded.truncated)
        assertNull(reloaded.stopReason)
        assertEquals(args, reloaded.anthropicReplay!!.blocksCopy()!![4].jsonObject.getValue("input"))
        assertEquals("", args.getValue("empty").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement("""[false,null,{},[],"e\u0301"]"""), args.getValue("nested"))
    }

    @Test
    fun carrierDoesNotRetainMutableInputOrExposeItsSnapshot() {
        val map = linkedMapOf<String, JsonElement>("type" to JsonPrimitive("thinking"), "thinking" to JsonPrimitive(""),
            "signature" to JsonPrimitive("snapshot-signature"))
        val list = mutableListOf<JsonElement>(JsonObject(map))
        val captured = AnthropicReplayCodec.complete(source, JsonArray(list))
        map["signature"] = JsonPrimitive("mutated-input")
        list.clear()

        val copy = captured.blocksCopy()!!
        val fresh = captured.blocksCopy()!!
        assertNotSame(copy, fresh)
        assertNotSame(copy[0], fresh[0])
        val copyEntry = copy[0].jsonObject.entries.single { it.key == "signature" }
        // The current DOM returns mutable backing entries; the capsule must not alias them.
        (copyEntry as MutableMap.MutableEntry<String, JsonElement>).setValue(JsonPrimitive("mutated-copy"))
        assertEquals("mutated-copy", copy[0].jsonObject.getValue("signature").jsonPrimitive.content)
        val wireEntry = AnthropicReplayCodec.encode(captured).entries.single { it.key == "state" }
        (wireEntry as MutableMap.MutableEntry<String, JsonElement>).setValue(JsonPrimitive("absent"))
        assertEquals("snapshot-signature", captured.blocksCopy()!!.single().jsonObject.getValue("signature").jsonPrimitive.content)
        assertEquals("complete", AnthropicReplayCodec.encode(captured).getValue("state").jsonPrimitive.content)
        assertFalse(captured.toString().contains("snapshot-signature"))
        assertFalse(captured.toString().contains("claude-fixture"))
        assertFalse(source.toString().contains("instance-1"))
    }

    @Test
    fun sourceIsTheExactOfficialLaneAndIdentity() {
        assertEquals(source, AnthropicReplay.Source.fromActualRequest("HTTPS://API.ANTHROPIC.COM:443/v1/messages", "claude-fixture", "instance-1"))
        assertEquals(endpoint, source.endpoint)
        assertNull(AnthropicReplay.Source.fromActualRequest(endpoint, "  "))
        assertNull(AnthropicReplay.Source.fromActualRequest(endpoint, "汉".repeat(86)))
        assertNull(AnthropicReplay.Source.fromActualRequest(endpoint, "claude-fixture", "a".repeat(129)))
        assertFalse(source == AnthropicReplay.Source.fromActualRequest(endpoint, "CLAUDE-fixture", "instance-1"))
        assertFalse(source == AnthropicReplay.Source.fromActualRequest(endpoint, "claude-fixture"))
        listOf("http://api.anthropic.com/v1/messages", "https://localhost/v1/messages",
            "https://api.anthropic.com.evil.invalid/v1/messages", "https://api.anthropic.com:444/v1/messages",
            "https://api.anthropic.com:/v1/messages", "https://api.anthropic.com/v1/%6dessages", "$endpoint/",
            "$endpoint?", "$endpoint#", "https://user@api.anthropic.com/v1/messages").forEach { url ->
            assertNull(url, AnthropicReplay.Source.fromActualRequest(url, "claude-fixture"))
        }
    }

    @Test
    fun aBadMiddleBlockInvalidatesTheWholeGroup() {
        val valid = blocks()
        val bad = listOf("null", "[]", "1", """{"type":"future_block"}""", """{"type":"signature_delta"}""",
            """{"type":"thinking","thinking":""}""", """{"type":"thinking","signature":"sig"}""",
            """{"type":"thinking","thinking":"","signature":""}""", """{"type":"thinking","thinking":"","signature":123}""",
            """{"type":"redacted_thinking","data":null}""", """{"type":"text","text":2}""",
            """{"type":"tool_use","name":"x","id":"t","input":[]}""",
            """{"type":"text","text":"x","headers":{"api_key":"secret-sentinel"}}""",
            """{"type":"text","text":"x","cache_control":{"type":"ephemeral","headers":{}}}""",
            """{"type":"tool_use","name":"x","id":"t","input":{"value":NaN}}""")
        for (block in bad) {
            val array = JsonArray(listOf(valid.first(), Json.parseToJsonElement(block), valid.last()))
            assertUnavailable(AnthropicReplayCodec.complete(source, array))
            assertUnavailable(AnthropicReplayCodec.decode(Json.parseToJsonElement(capsule(array.toString()))))
        }
    }

    @Test
    fun presentBadCapsulesDoNotBecomeMissingOrAnEmptySuccess() {
        val cases = listOf("null", "[]", "true", "\"opaque\"", "{}", capsule("[]"),
            capsule().replace("\"version\":1", "\"version\":2"), capsule().replace("\"version\":1", "\"version\":\"1\""),
            capsule().replace("\"blocks\":", "\"other\":"), capsule().replace("\"state\":\"complete\"", "\"state\":\"future\""),
            capsule().replace("\"instanceId\":\"instance-1\"", "\"instanceId\":null"),
            capsule().replace("\"instanceId\":\"instance-1\"", "\"api_key\":\"secret-sentinel\""),
            capsule().replace(endpoint, "https://localhost/v1/messages"),
            """{"version":1,"state":"absent","authorization":"secret-sentinel"}""")
        cases.forEach { value ->
            val line = """{"t":"finished","stop":"end_turn","truncated":true,"anthropic_replay":$value}"""
            val decoded = finished(line)
            assertEquals("end_turn", decoded.stopReason)
            assertTrue(decoded.truncated)
            assertUnavailable(checkNotNull(decoded.anthropicReplay))
        }
        assertUnavailable(AnthropicReplayCodec.complete(source, JsonArray(emptyList())))
        assertUnavailable(AnthropicReplayCodec.decode(null))
        assertEquals(State.ABSENT, finished(ChatStreamJsonl.encode(LLMStreamChunk.Finished(null,
            anthropicReplay = AnthropicReplayCodec.absent()))).anthropicReplay?.state)
        assertUnavailable(checkNotNull(finished(ChatStreamJsonl.encode(LLMStreamChunk.Finished(null,
            anthropicReplay = AnthropicReplayCodec.unavailable()))).anthropicReplay))
    }

    @Test
    fun capsuleUsesUtf8BudgetIncludingEnvelopeInBothDirections() {
        val empty = """[{"type":"text","text":""}]"""
        // Calculate from the external wire contract, not the production cap or byte-count helper.
        val available = 131072 - capsule(empty).toByteArray(Charsets.UTF_8).size
        val atLimit = "汉".repeat(available / 3) + "x".repeat(available % 3)
        fun blocksWithText(text: String) = JsonArray(listOf(JsonObject(mapOf(
            "type" to JsonPrimitive("text"), "text" to JsonPrimitive(text),
        ))))
        val okay = AnthropicReplayCodec.complete(source, blocksWithText(atLimit))
        assertEquals(State.COMPLETE, okay.state)
        assertEquals(131072, okay.encodedBytes)
        assertEquals(State.COMPLETE, finished(ChatStreamJsonl.encode(LLMStreamChunk.Finished(null,
            anthropicReplay = okay))).anthropicReplay?.state)

        val tooLarge = blocksWithText(atLimit + "x")
        assertUnavailable(AnthropicReplayCodec.complete(source, tooLarge))
        val oversizedLine = """{"t":"finished","anthropic_replay":${capsule(tooLarge.toString())}}"""
        assertUnavailable(checkNotNull(finished(oversizedLine).anthropicReplay))
    }

    @Test
    fun rejectsTooDeepAndUnencodableTrees() {
        val deep = "[".repeat(72) + "0" + "]".repeat(72)
        val arr = """[{"type":"tool_use","name":"x","id":"t","input":{"nested":$deep}}]"""
        assertUnavailable(AnthropicReplayCodec.complete(source, Json.parseToJsonElement(arr).jsonArray))
        val malformedUnicode = JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive("\uD800")))
        assertUnavailable(AnthropicReplayCodec.complete(source, JsonArray(listOf(malformedUnicode))))
    }

    @Test
    fun legacyFramesAndPublicProjectionsKeepTheirBehavior() {
        val old = """{"t":"finished","stop":"stop","truncated":false}"""
        assertEquals(old, ChatStreamJsonl.encode(LLMStreamChunk.Finished("stop")))
        assertNull(finished(old).anthropicReplay)
        assertEquals(LLMStreamChunk.Finished(null, true), finished("""{"t":"finished","stop":"","truncated":true}"""))
        assertEquals(LLMStreamChunk.Finished(null), finished(ChatStreamJsonl.DONE_LINE))
        assertEquals(LLMStreamChunk.Finished(null), finished("""{"t":"done","anthropic_replay":${capsule()}}"""))
        val text = "visible \"anthropic_replay\""
        assertEquals(LLMStreamChunk.Text(text), ChatStreamJsonl.decode(ChatStreamJsonl.encode(LLMStreamChunk.Text(text))))
        val message = LLMMessage(LLMMessage.Role.ASSISTANT, "")
        val response = LLMResponse("", "end_turn", null)
        assertNull(message.anthropicReplay)
        assertNull(response.anthropicReplay)
        val replay = capture()
        assertTrue(message.copy(anthropicReplay = replay).content.isEmpty())
        assertTrue(response.copy(anthropicReplay = replay).text.isEmpty())
        assertNull(message.copy(anthropicReplay = replay).reasoningContent)
    }

    private fun assertUnavailable(replay: AnthropicReplay) {
        assertEquals(State.UNAVAILABLE, replay.state)
        assertNull(replay.source)
        assertNull(replay.blocksCopy())
        assertEquals(0, replay.blockCount)
    }
}
