package com.rikkaminis.app.provider.openai

import com.rikkaminis.app.data.model.LLMMessage
import com.rikkaminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-18: a relay can end a Responses stream before the tool's done frame. */
class OpenAIResponsesEofTest {
    @Test
    fun `EOF uses the call id alone when the relay omits the item id`() = runBlocking {
        assertEofPair(
            """{"type":"function_call","call_id":"call_a","name":"test_tool"}""",
            "call_a",
        )
    }

    @Test
    fun `EOF preserves the composite id when the relay supplies the item id`() = runBlocking {
        assertEofPair(
            """{"type":"function_call","id":"fc_a","call_id":"call_a","name":"test_tool"}""",
            "call_a|fc_a",
        )
    }

    private suspend fun assertEofPair(item: String, expectedId: String) {
        MockWebServer().use { server ->
            server.start()
            // There is no item/arguments done event, terminal event or [DONE].
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    """
                        data: {"type":"response.output_item.added","output_index":0,"item":$item}

                        data: {"type":"response.function_call_arguments.delta","call_id":"call_a","delta":"{\"arg\":"}

                    """.trimIndent(),
                ),
            )
            val provider = OpenAIProvider(
                apiKey = "SECRET123",
                basePath = server.url("/v1").toString(),
                useResponsesAPI = true,
            )
            val chunks = provider.streamMessage(
                listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 128,
            ).toList()

            assertEquals(expectedId, chunks.filterIsInstance<LLMStreamChunk.ToolUseStart>().single().id)
            assertEquals(expectedId, chunks.filterIsInstance<LLMStreamChunk.ToolInputDelta>().single().id)
            assertEquals(expectedId, chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>().single().id)
            assertTrue(chunks.filterIsInstance<LLMStreamChunk.Finished>().single().truncated)
        }
    }
}
