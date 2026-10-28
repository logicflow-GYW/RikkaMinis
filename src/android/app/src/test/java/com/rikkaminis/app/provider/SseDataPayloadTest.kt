package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [r3-b08 F-13] The one SSE `data:` reader shared by all three stream parsers.
 *
 * Regression this pins: only OpenAIProvider accepted `data:{…}` (no space). The
 * Anthropic and Gemini loops required `data: `, so a relay that emits the legal
 * no-space form lost EVERY frame — an empty stream, or a first-data watchdog
 * timeout, on providers that had worked on the other two backends.
 */
class SseDataPayloadTest {

    @Test
    fun `a space-delimited payload is read`() {
        assertEquals("{\"a\":1}", sseDataPayload("data: {\"a\":1}"))
    }

    @Test
    fun `a payload without the optional space is read`() {
        assertEquals("{\"a\":1}", sseDataPayload("data:{\"a\":1}"))
    }

    @Test
    fun `only ONE leading space is stripped`() {
        // SSE spec: strip at most one space; the rest belongs to the data.
        assertEquals(" {\"a\":1}", sseDataPayload("data:  {\"a\":1}"))
    }

    @Test
    fun `the DONE sentinel comes through as-is`() {
        assertEquals("[DONE]", sseDataPayload("data: [DONE]"))
        assertEquals("[DONE]", sseDataPayload("data:[DONE]"))
    }

    @Test
    fun `an empty data payload is an empty string, not null`() {
        assertEquals("", sseDataPayload("data:"))
        assertEquals("", sseDataPayload("data: "))
    }

    @Test
    fun `event, id and comment lines are not data lines`() {
        assertNull(sseDataPayload("event: message_start"))
        assertNull(sseDataPayload(": keep-alive"))
        assertNull(sseDataPayload(""))
        assertNull(sseDataPayload("  data: {\"a\":1}"))
    }
}
