package com.rikkaminis.app.provider.openai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [r3-b08 F-01] Boundary table for the Chat-Completions `delta.content` reader.
 *
 * The defect: `content` may be an ARRAY of typed blocks (Mistral Magistral
 * dialect), and `optString` answers an array with the array's own JSON text — so
 * the visible reply became `[{"type":"thinking",…}]` while the real `text`
 * blocks were skipped. Expected strings are hand-written literals, never derived
 * from the code under test.
 */
class ChatCompletionsDeltaTextTest {

    @Test
    fun `plain string content passes through unchanged`() {
        val delta = JSONObject().put("content", "hello")
        assertEquals("hello", chatCompletionsDeltaText(delta))
    }

    @Test
    fun `null delta reads as empty`() {
        assertEquals("", chatCompletionsDeltaText(null))
    }

    @Test
    fun `missing content reads as empty`() {
        assertEquals("", chatCompletionsDeltaText(JSONObject().put("role", "assistant")))
    }

    @Test
    fun `explicitly null content reads as empty`() {
        val delta = JSONObject()
        delta.put("content", JSONObject.NULL)
        assertEquals("", chatCompletionsDeltaText(delta))
    }

    @Test
    fun `array content yields only the text blocks`() {
        val delta = JSONObject().put(
            "content",
            JSONArray()
                .put(
                    JSONObject().put("type", "thinking").put(
                        "thinking",
                        JSONArray().put(JSONObject().put("type", "text").put("text", "why")),
                    )
                )
                .put(JSONObject().put("type", "text").put("text", "answer")),
        )
        // "why" belongs to the thinking loop; the reply must be exactly "answer".
        assertEquals("answer", chatCompletionsDeltaText(delta))
    }

    @Test
    fun `array content concatenates several text blocks in order`() {
        val delta = JSONObject().put(
            "content",
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", "a"))
                .put(JSONObject().put("type", "thinking").put("thinking", JSONArray()))
                .put(JSONObject().put("type", "text").put("text", "b")),
        )
        assertEquals("ab", chatCompletionsDeltaText(delta))
    }

    @Test
    fun `array content with no text block yields empty, not the array JSON`() {
        val delta = JSONObject().put(
            "content",
            JSONArray().put(
                JSONObject().put("type", "thinking").put(
                    "thinking",
                    JSONArray().put(JSONObject().put("type", "text").put("text", "only thinking")),
                )
            ),
        )
        assertEquals("", chatCompletionsDeltaText(delta))
    }
}
