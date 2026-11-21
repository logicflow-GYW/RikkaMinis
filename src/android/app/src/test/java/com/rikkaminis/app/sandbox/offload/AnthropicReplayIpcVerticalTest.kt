package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.data.model.AgentContentPart
import com.rikkaminis.app.data.model.AnthropicReplay
import com.rikkaminis.app.data.model.AnthropicReplayCodec
import com.rikkaminis.app.data.model.ImageEndpointMode
import com.rikkaminis.app.data.model.LLMMediaAttachment
import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.LLMResponse
import com.rikkaminis.app.data.model.LLMStreamChunk
import com.rikkaminis.app.data.model.LLMUsage
import com.rikkaminis.app.data.model.ProviderCredential
import com.rikkaminis.app.data.model.ProviderInstance
import com.rikkaminis.app.data.model.ProviderType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

/** Real writer, capture, worker assembly methods and reply readers; no provider or Android lifecycle. */
class AnthropicReplayIpcVerticalTest {
    private val service = ModelExecutionService()

    @Test
    fun dispatcherWritesAnExactStructuredCapsuleAtTheMessageIndex() {
        val raw = buildRequest()
        val messages = parseMessages(raw)
        assertEquals(3, messages.size)
        assertEquals(tree(capsule()), messages[1].jsonObject[FIELD])
        assertFalse(FIELD in messages[0].jsonObject)
        assertFalse(FIELD in messages[2].jsonObject)
        val withoutReplay = parseMessages(buildRequest(replay = null))
        assertTrue(withoutReplay.all { FIELD !in it.jsonObject })
        assertEquals(withoutReplay[1].jsonObject, JsonObject(messages[1].jsonObject - FIELD))
    }

    @Test
    fun nonStreamingWorkerReconstructsFromTheOneRawRead() {
        val req = capture(buildRequest(streaming = false), streaming = false)
        assertWorkerMessages(service.readNonStreamingMessages(req.optJSONArray("messages")))
    }

    @Test
    fun streamingWorkerReconstructsFromTheOneRawRead() {
        val req = capture(buildRequest(streaming = true), streaming = true)
        assertWorkerMessages(service.readStreamingMessages(req.optJSONArray("messages")))
    }

    @Test
    fun bothWorkerReadersKeepPublicMessagesForMissingOrInvalidReplay() {
        val messages = variants.joinToString(",") { (wire, _) ->
            """{"role":"assistant","content":"$PUBLIC_TEXT"${field(wire)}}"""
        }
        val req = capture("""{"messages":[$messages]}""")
        for (items in listOf(service.readNonStreamingMessages(req.optJSONArray("messages")), service.readStreamingMessages(req.optJSONArray("messages")))) {
            assertEquals(variants.map { it.second }, items.map { it.anthropicReplay?.state })
            assertTrue(items.all { it.content == PUBLIC_TEXT })
            assertTrue(items.all { it.anthropicReplay?.blocksCopy() == null })
        }
        // A parser which only recognizes the literal field name would silently drop this.
        val escaped = capture("""{"messages":[{"role":"assistant","anthropic\u005freplay":${capsule()}}]}""")
        assertReplay(service.readNonStreamingMessages(escaped.optJSONArray("messages")).single().anthropicReplay)
        assertReplay(service.readStreamingMessages(escaped.optJSONArray("messages")).single().anthropicReplay)
    }

    @Test
    fun overlappingWorkerRunsKeepIndependentSourcesAndNeverSeedTheNextRun() {
        val pool = Executors.newFixedThreadPool(2)
        val barrier = CyclicBarrier(2)
        try {
            val lanes = listOf("one", "two").map { lane ->
                val blocks = BLOCKS.replace(PRIVATE_A, "signature-$lane")
                val source = source("fixture-$lane", "instance-$lane")
                val raw = buildRequest(replay = replay(source, blocks), streaming = lane == "two")
                pool.submit<JSONObject> {
                    val req = capture(raw, streaming = lane == "two")
                    barrier.await(5, SECONDS) // Both captures live before either reads its message.
                    val messages = if (lane == "one") service.readNonStreamingMessages(req.optJSONArray("messages"))
                    else service.readStreamingMessages(req.optJSONArray("messages"))
                    assertReplay(messages[1].anthropicReplay, source, blocks)
                    req
                } to (source to blocks)
            }
            for ((future, expected) in lanes) {
                val (source, blocks) = expected
                val req = future.get(10, SECONDS)
                // Also consume on a different thread from the one that captured the run.
                assertReplay(service.readNonStreamingMessages(req.optJSONArray("messages"))[1].anthropicReplay, source, blocks)
                assertReplay(service.readStreamingMessages(req.optJSONArray("messages"))[1].anthropicReplay, source, blocks)
            }
            val next = capture(buildRequest(replay = null))
            assertTrue(service.readNonStreamingMessages(next.optJSONArray("messages")).all { it.anthropicReplay == null })
            assertTrue(service.readStreamingMessages(next.optJSONArray("messages")).all { it.anthropicReplay == null })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun workerWritesExactNonStreamingResultAndGatewayRoundTripsIt() {
        val raw = workerResult(replay())
        assertEquals(tree(capsule()), tree(raw).jsonObject[FIELD])
        assertTrue(PRIVATE_A in raw)
        val result = success(raw)
        assertReplay(result.response.anthropicReplay)
        assertEquals(PUBLIC_TEXT, result.response.text)
        assertEquals("tool_use", result.response.stopReason)
        assertEquals(LLMUsage(17, 9), result.response.usage)
        val media = result.response.mediaAttachments.single()
        assertEquals(LLMMediaAttachment.MediaType.IMAGE, media.type)
        assertEquals("image/png", media.mimeType)
        assertArrayEquals(MEDIA_BYTES, media.data)
        assertEquals(tree(workerResult(null)), tree(result.rawJson))
        assertPublic(result.rawJson)
    }

    @Test
    fun gatewayDecodesAnIndependentExactWireValueAndProjectsTheWholeField() {
        val raw = """{"text":"$PUBLIC_TEXT","anthropic\u005freplay":${capsule()},"stop_reason":"tool_use"}"""
        val result = success(raw)
        assertReplay(result.response.anthropicReplay)
        assertEquals(PUBLIC_TEXT, result.response.text)
        assertEquals("tool_use", result.response.stopReason)
        assertPublic(result.rawJson)
        assertFalse(PRIVATE_A in result.toString())
    }

    @Test
    fun gatewayProjectsInvalidValuesAndPreservesBytesWhenTheFieldIsMissing() {
        for ((wire, state) in variants) {
            val raw = """ { "text" : "$PUBLIC_TEXT", "stop_reason" : "length"${field(wire)} } """
            val result = success(raw)
            assertEquals(state, result.response.anthropicReplay?.state)
            assertEquals(PUBLIC_TEXT, result.response.text)
            assertEquals("length", result.response.stopReason)
            assertPublic(result.rawJson)
            if (wire == null) assertEquals(raw, result.rawJson)
        }
        // Legacy spelling: JSONObject accepts it, but it is not an exact carrier.
        val legacy = success("""{'text':'$PUBLIC_TEXT','anthropic_replay':'$PRIVATE_A'}""")
        assertEquals(AnthropicReplay.State.UNAVAILABLE, legacy.response.anthropicReplay?.state)
        assertPublic(legacy.rawJson)
    }

    @Test
    fun workerWithoutReplayRetainsLegacyResultBytesAndImageAndErrorShapes() {
        // Legacy serialization (JSONObject ordering and escaping are platform-specific).
        val expected = JSONObject().put("model", "fixture-model").put("text", PUBLIC_TEXT)
            .put("stop_reason", "tool_use")
            .put("usage", JSONObject().put("input_tokens", 17).put("output_tokens", 9))
            .put("media_files", org.json.JSONArray().put(JSONObject().put("type", "image")
                .put("mime_type", "image/png").put("data", "AP9/") ))
            .put("applied_extras", JSONObject().put("quality", "high"))
            .put("warnings", org.json.JSONArray().put("ordinary warning"))
            .put("image_endpoint_resolved", "chatCompletions").put("exit_code", 0).toString()
        assertEquals(expected, workerResult(null))
        assertEquals(expected, success(expected).rawJson)

        val image = """{"media_files":[{"type":"image","mime_type":"image/png","data":"AP9/"}],"exit_code":0}"""
        val parsedImage = success(image)
        assertNull(parsedImage.response.anthropicReplay)
        assertArrayEquals(MEDIA_BYTES, parsedImage.response.mediaAttachments.single().data)
        assertEquals(image, parsedImage.rawJson)
        val cancelled = """{"error":"model_use_cancelled","message":"cancelled","exit_code":1}"""
        assertEquals(ProviderExecutionGateway.SendResult.RemoteFailure("model_use_cancelled", "cancelled", 1),
            ProviderExecutionGateway.parseNonStreamingResult(cancelled))
        val error = """{"error":"image_generation_failed","message":"$PUBLIC_TEXT","exit_code":2${field(capsule())}}"""
        assertEquals(ProviderExecutionGateway.SendResult.RemoteFailure("image_generation_failed", PUBLIC_TEXT, 2),
            ProviderExecutionGateway.parseNonStreamingResult(error))
    }

    @Test
    fun finishedSpoolKeepsTheSameReplayWithoutChangingTerminalOrTruncation() {
        val finished = LLMStreamChunk.Finished("tool_use", anthropicReplay = replay())
        val line = ChatStreamJsonl.encode(finished)
        assertEquals(tree(capsule()), tree(line).jsonObject[FIELD])
        val file = File.createTempFile("replay-ipc", ".jsonl")
        try {
            file.writeText(line + "\n" + ChatStreamJsonl.DONE_LINE + "\n")
            val ceiling = PayloadSizePolicy.base64Size(PayloadSizePolicy.MAX_INLINE_BINARY_BYTES).toInt()
            val read = BoundedLineReader(maxLineBytes = ceiling)
                .readAppended(file, 0, file.length()) as BoundedLineReader.ReadResult.Lines
            assertEquals(listOf(line, """{"t":"done"}"""), read.lines)
            assertTrue(read.oversizedLines.isEmpty())
            assertFalse(ChatStreamJsonl.isDone(read.lines[0]))
            assertFalse(ChatStreamJsonl.isError(read.lines[0]))
            val result = ChatStreamJsonl.decode(read.lines[0]) as LLMStreamChunk.Finished
            assertReplay(result.anthropicReplay)
            assertEquals("tool_use", result.stopReason)
            assertFalse(result.truncated)
            assertTrue(ChatStreamJsonl.isDone(read.lines[1]))
            assertNull((ChatStreamJsonl.decode(read.lines[1]) as LLMStreamChunk.Finished).anthropicReplay)
        } finally {
            file.delete()
        }
        for ((wire, state) in variants) {
            val raw = """{"t":"finished","stop":"length","truncated":true${field(wire)}}"""
            val chunk = ChatStreamJsonl.decode(raw) as LLMStreamChunk.Finished
            assertEquals(state, chunk.anthropicReplay?.state)
            assertEquals("length", chunk.stopReason)
            assertTrue(chunk.truncated)
        }
    }

    @Test
    fun modelUsePublicReaderAcceptsExactWorkerResultWithoutReadingPrivateValues() {
        val raw = """{"text":"$PUBLIC_TEXT","warnings":["ordinary warning"],"anthropic_replay":${capsule()}}"""
        val obj = AnthropicReplayIpc.publicResultObject(raw)
        assertEquals(PUBLIC_TEXT, obj.getString("text"))
        assertEquals("ordinary warning", obj.getJSONArray("warnings").getString(0))
        assertPublic(obj.toString())
    }

    @Test
    fun productionExecutorsKeepUsingTheAssemblyUnderTheirExistingProtocol() {
        val service = sourceFile("ModelExecutionService.kt")
        val nonStreaming = service.substringAfter("private fun executeRun(")
            .substringBefore("private fun executeStreamingRun(")
        val streaming = service.substringAfter("private fun executeStreamingRun(")
            .substringBefore("private fun awaitStreamAckBarrier(")
        assertTrue(nonStreaming.contains("val messages = readNonStreamingMessages(req.optJSONArray(\"messages\"))"))
        assertTrue(nonStreaming.contains("messages = messages,"))
        assertTrue(nonStreaming.contains("return@withModelExecutionCancellation buildNonStreamingResult("))
        assertTrue(nonStreaming.contains("publish = { completed -> writeResultAtomically(dir, completed) }"))
        assertTrue(streaming.contains("val messages = readStreamingMessages(req.optJSONArray(\"messages\"))"))
        assertTrue(streaming.contains("messages = messages,"))
        assertTrue(streaming.contains("appendLine(ChatStreamJsonl.encode(chunk))"))
        val handler = sourceFile("ChatStreamOffloadHandler.kt")
        assertTrue(handler.contains("ChatStreamJsonl.decode(line)?.let { chunk ->"))
        assertTrue(handler.contains("emit(chunk)"))
        val modelUse = sourceFile("ModelUseOffloadHandler.kt")
        assertTrue(modelUse.contains("AnthropicReplayIpc.publicResultObject(remoteResult)"))
    }

    private fun assertWorkerMessages(messages: List<LLMMessage>) {
        assertEquals(listOf(LLMMessage.Role.USER, LLMMessage.Role.ASSISTANT, LLMMessage.Role.USER), messages.map { it.role })
        assertEquals(listOf("ask", PUBLIC_TEXT, "tool result"), messages.map { it.content })
        assertEquals(listOf(null, "", "old public reasoning"), messages.map { it.reasoningContent })
        assertEquals(AgentContentPart.Text("visible part"), messages[1].contentParts.single())
        assertEquals(LLMMessage.AudioPart("wav", "AQID"), messages[2].audioParts.single())
        assertNull(messages[0].anthropicReplay)
        assertReplay(messages[1].anthropicReplay)
        assertNull(messages[2].anthropicReplay)
    }

    private fun buildRequest(replay: AnthropicReplay? = replay(), streaming: Boolean = false): String =
        ModelExecutionDispatcher.buildRequestJson(
            instance = ProviderInstance("fixture-instance", "fixture", ProviderType.anthropic, ProviderCredential.apiKey),
            model = LLMModel("fixture-model", "fixture", "anthropic"),
            messages = listOf(
                LLMMessage(LLMMessage.Role.USER, "ask"),
                LLMMessage(LLMMessage.Role.ASSISTANT, PUBLIC_TEXT, reasoningContent = "",
                    contentParts = listOf(AgentContentPart.Text("visible part")), anthropicReplay = replay),
                LLMMessage(LLMMessage.Role.USER, "tool result", reasoningContent = "old public reasoning",
                    audioParts = listOf(LLMMessage.AudioPart("wav", "AQID"))),
            ),
            systemPrompt = null, maxTokens = 256, temperature = null, imageParts = emptyList(),
            inputJson = "", outputExt = null, streaming = streaming,
        )

    private fun workerResult(replay: AnthropicReplay?): String = service.buildNonStreamingResult(
        modelId = "fixture-model",
        response = LLMResponse(PUBLIC_TEXT, "tool_use", LLMUsage(17, 9),
            listOf(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, "image/png", MEDIA_BYTES)), replay),
        appliedExtras = JSONObject().put("quality", "high"),
        callWarnings = listOf("ordinary warning"),
        imageEndpointCache = ImageEndpointMode.chatCompletions,
    )

    private fun capture(raw: String, streaming: Boolean = false): JSONObject {
        var reads = 0
        val capture = CapturedRequestBody.capture { reads++; raw }
        assertEquals(streaming, capture.streamingFlagOrFalse())
        val req = capture.jsonOrThrow()
        assertEquals(streaming, capture.streamingFlagOrFalse())
        assertEquals(1, reads)
        return req
    }

    private fun success(raw: String): ProviderExecutionGateway.SendResult.Success {
        val result = ProviderExecutionGateway.parseNonStreamingResult(raw)
        assertTrue("expected public success, got ${result.javaClass.simpleName}", result is ProviderExecutionGateway.SendResult.Success)
        return result as ProviderExecutionGateway.SendResult.Success
    }

    private fun assertPublic(raw: String) {
        assertFalse("public envelope must remove the whole capsule", FIELD in tree(raw).jsonObject)
        assertFalse(PRIVATE_A in raw)
        assertFalse(PRIVATE_B in raw)
        assertEquals(PUBLIC_TEXT, JSONObject(raw).getString("text"))
    }

    private fun assertReplay(replay: AnthropicReplay?, source: AnthropicReplay.Source = source(), blocks: String = BLOCKS) {
        assertEquals("replay must arrive at this edge", AnthropicReplay.State.COMPLETE, replay?.state)
        assertEquals(source, replay?.source)
        assertEquals(tree(blocks), replay?.blocksCopy())
    }

    private fun source(model: String = "fixture-model", instance: String = "fixture-instance") =
        checkNotNull(AnthropicReplay.Source.fromActualRequest("https://api.anthropic.com/v1/messages", model, instance))

    private fun replay(source: AnthropicReplay.Source = source(), blocks: String = BLOCKS) =
        AnthropicReplayCodec.complete(source, tree(blocks).jsonArray).also {
            check(it.state == AnthropicReplay.State.COMPLETE) { "invalid fixture" }
        }

    private fun capsule(blocks: String = BLOCKS) =
        """{"version":1,"state":"complete","source":{"endpoint":"https://api.anthropic.com/v1/messages","model":"fixture-model","instanceId":"fixture-instance"},"blocks":$blocks}"""

    private fun tree(raw: String) = Json.parseToJsonElement(raw)
    private fun parseMessages(raw: String): JsonArray = tree(raw).jsonObject.getValue("messages").jsonArray
    private fun field(wire: String?) = wire?.let { ",\"$FIELD\":$it" }.orEmpty()

    private val variants get() = listOf(
        null to null,
        "null" to AnthropicReplay.State.UNAVAILABLE,
        "\"$PRIVATE_A\"" to AnthropicReplay.State.UNAVAILABLE,
        """{"version":2,"state":"absent","secret":"$PRIVATE_B"}""" to AnthropicReplay.State.UNAVAILABLE,
        """{"version":1,"state":"absent"}""" to AnthropicReplay.State.ABSENT,
        """{"version":1,"state":"unavailable"}""" to AnthropicReplay.State.UNAVAILABLE,
        capsule("""[{"type":"thinking","thinking":"${"界".repeat(44_000)}","signature":"$PRIVATE_A"}]""") to AnthropicReplay.State.UNAVAILABLE,
        capsule("""[{"type":"tool_use","id":"t","name":"f","input":{"deep":${"[".repeat(72)}"$PRIVATE_B"${"]".repeat(72)}}}]""") to AnthropicReplay.State.UNAVAILABLE,
    )

    private fun sourceFile(name: String): String {
        val path = "src/main/java/com/rikkaminis/app/sandbox/offload/$name"
        return generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, path), File(it, "src/android/app/$path")) }
            .first { it.isFile }.readText()
    }

    companion object {
        private const val FIELD = "anthropic_replay"
        private const val PUBLIC_TEXT = "public text mentions anthropic_replay 常🙂"
        private const val PRIVATE_A = "d05-synthetic-signature-a"
        private const val PRIVATE_B = "d05-synthetic-redacted-b"
        private val MEDIA_BYTES = byteArrayOf(0, -1, 127)
        private const val BLOCKS = """[
            {"type":"thinking","thinking":"","signature":"d05-synthetic-signature-a"},
            {"type":"thinking","thinking":"思考 🐈\n e\u0301","signature":"d05-synthetic-signature-other"},
            {"type":"redacted_thinking","data":"d05-synthetic-redacted-b"},
            {"type":"text","text":"native text 世界"},
            {"type":"tool_use","id":"toolu_fixture","name":"local_fixture","input":{
                "large":9223372036854775897,"fraction":0.12345678901234567890123456789,
                "exponent":1e400,"unicode":"雪🙂/e\u0301","nested":[true,null,{"v":-7.25e-41}]
            }}
        ]"""
    }
}
