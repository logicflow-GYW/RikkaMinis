package com.rikkaminis.app.provider.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R8-B B-F14] An `error` OBJECT is not a message.
 *
 * `org.json`'s `optString("error")` on an object returns its `toString()` — the
 * whole JSON body — so `{"error":{"code":1}}` surfaced as
 * `Request failed (HTTP 500): {"code":1}`: unreadable, and it shadowed the
 * `message` fallback that would have said something useful. Only a STRING
 * `error` is a message; an object without `message` has nothing to say and falls
 * through to the next level.
 */
class VoiceModelsServerMessageTest {

    private fun msg(json: String): String? =
        VoiceProviderException.serverMessage(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `an error object without a message is not echoed as JSON`() {
        val out = msg("""{"error":{"code":1}}""")
        assertNull(out)
    }

    @Test
    fun `an error object with a message yields the message`() {
        assertEquals("invalid voice", msg("""{"error":{"message":"invalid voice"}}"""))
    }

    @Test
    fun `a string error is still a message`() {
        assertEquals("quota exceeded", msg("""{"error":"quota exceeded"}"""))
    }

    @Test
    fun `a top-level message is still read`() {
        assertEquals("bad request", msg("""{"message":"bad request"}"""))
    }

    @Test
    fun `a plain-text body is still passed through`() {
        assertEquals("Bad Gateway", msg("Bad Gateway"))
    }

    @Test
    fun `an empty error object falls through`() {
        assertNull(msg("""{"error":{}}"""))
    }

    @Test
    fun `a null error falls through`() {
        assertNull(msg("""{"error":null}"""))
    }

    @Test
    fun `a blank or empty body yields null`() {
        assertNull(VoiceProviderException.serverMessage(null))
        assertNull(VoiceProviderException.serverMessage(ByteArray(0)))
    }

    @Test
    fun `the HTTP message uses the extracted text, never the JSON`() {
        val out = VoiceProviderException.Http(500, """{"error":{"code":1}}""".toByteArray()).message
        assertFalse("must not echo the raw JSON object:\n$out", out!!.contains("{\"code\""))
        assertEquals("Request failed (HTTP 500)", out)
    }

    @Test
    fun `the HTTP message still carries a real provider message`() {
        val body = """{"error":{"message":"voice_type not found"}}""".toByteArray()
        val out = VoiceProviderException.Http(400, body).message
        assertTrue("must include the provider's own words:\n$out", out!!.contains("voice_type not found"))
    }
}
