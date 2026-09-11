package com.rikkaminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [absorb-relay-error-messages] HTTP error bodies must yield a readable message
 * across the dialects relays actually return — including the bare
 * `{"code":"internal","message":"服务繁忙，请稍后再试",...}` shape observed
 * from api.senseaudio.cn (HTTP 500), which previously leaked the whole JSON
 * into the diagnostic string.
 */
class HttpErrorExtractionTest {

    @Test
    fun `openai style error object yields its message`() {
        val body = """{"error":{"message":"model not found","type":"invalid_request_error"}}"""
        assertEquals("model not found", extractHttpErrorMessage(body))
    }

    @Test
    fun `bare relay body yields the top-level message`() {
        val body = """{"code":"internal","message":"服务繁忙，请稍后再试","ref_code":500000,"ref_scope":"common"}"""
        assertEquals("服务繁忙，请稍后再试", extractHttpErrorMessage(body))
    }

    @Test
    fun `error carried as a bare string is accepted`() {
        assertEquals("rate limited", extractHttpErrorMessage("""{"error":"rate limited"}"""))
    }

    @Test
    fun `msg alias is accepted`() {
        assertEquals("服务器繁忙", extractHttpErrorMessage("""{"msg":"服务器繁忙"}"""))
    }

    @Test
    fun `blank candidates fall through to the next one`() {
        val body = """{"error":{"message":"   "},"message":"top level"}"""
        assertEquals("top level", extractHttpErrorMessage(body))
    }

    @Test
    fun `error object without message never stringifies the object`() {
        val body = """{"error":{"code":500}}"""
        assertEquals(body.take(500), extractHttpErrorMessage(body))
    }

    @Test
    fun `non-json body falls back to a bounded raw slice`() {
        val body = "<html>" + "x".repeat(2000)
        assertEquals(body.take(500), extractHttpErrorMessage(body))
    }

    @Test
    fun `fallback bound is configurable`() {
        val body = "y".repeat(300)
        assertEquals(body.take(200), extractHttpErrorMessage(body, fallbackTake = 200))
    }
}
