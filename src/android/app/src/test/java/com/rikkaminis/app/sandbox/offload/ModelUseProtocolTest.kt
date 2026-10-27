package com.rikkaminis.app.sandbox.offload

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/audit3-1015 F-04] The `extra_body` dialect, and the structural guard
 * that keeps it single-sourced.
 *
 * The bug: `:modelservice`'s copy of `parseChatExtraBody` mapped a `JSONArray`
 * through `(0 until v.length()).mapNotNull { e -> e.let { if (it is Int || …)
 * it else null } }`. The lambda parameter was the loop *index*, so `is Int` was
 * unconditionally true and `{"stop":["a","b"]}` was sent as `[0,1]`; nested
 * objects fell into `else` and were dropped with a warning. The `:app` copy had
 * always forwarded raw org.json values, so the SAME input produced two
 * different request bodies depending on which process executed the run — and
 * nothing observed the difference (the JVM path never reaches the worker).
 *
 * Every expectation below is a literal, taken from the pre-fix `:app`
 * behaviour (which is the definition of "correct" here — it is the dialect the
 * iOS bridge and the help text describe), not derived from the implementation.
 */
class ModelUseProtocolTest {

    private fun warn() = mutableListOf<String>()

    // ── F-04 regression: array elements must survive as elements ─────────

    @Test
    fun `extra_body array keeps its elements`() {
        val w = warn()
        val out = ModelUseProtocol.parseChatExtraBody("""{"extra_body":{"stop":["a","b"]}}""", w)
        val arr = out["stop"] as JSONArray
        assertEquals(2, arr.length())
        assertEquals("a", arr.getString(0))
        assertEquals("b", arr.getString(1))
        assertTrue("no warnings expected for a well-formed envelope: $w", w.isEmpty())
    }

    @Test
    fun `extra_body array serializes back to the original JSON`() {
        val w = warn()
        val out = ModelUseProtocol.parseChatExtraBody(
            """{"extra_body":{"stop":["a","b"],"logit_bias":{"50256":-100},"n":2,"stream":false}}""",
            w,
        )
        // The provider does `body.put(key, value)` with these values; that is
        // the only contract that matters. Pre-fix the worker dialect printed
        // `[0,1]` for stop and dropped logit_bias.
        val serialized = JSONObject().apply { for ((k, v) in out) put(k, v) }
        assertEquals("""["a","b"]""", serialized.get("stop").toString())
        assertEquals("""{"50256":-100}""", serialized.get("logit_bias").toString())
        assertEquals(2, serialized.getInt("n"))
        assertEquals(false, serialized.getBoolean("stream"))
        assertTrue("no warnings expected: $w", w.isEmpty())
    }

    @Test
    fun `nested object is preserved as an object, not dropped`() {
        val w = warn()
        val out = ModelUseProtocol.parseChatExtraBody(
            """{"extra_body":{"response_format":{"type":"json_object"}}}""",
            w,
        )
        val nested = out["response_format"]
        assertTrue("expected a JSONObject, got ${nested?.javaClass}", nested is JSONObject)
        assertEquals("json_object", (nested as JSONObject).getString("type"))
        assertTrue("a nested object must not warn-and-drop: $w", w.isEmpty())
    }

    @Test
    fun `array of objects round-trips structurally`() {
        val w = warn()
        val out = ModelUseProtocol.parseChatExtraBody(
            """{"extra_body":{"tools":[{"type":"function","function":{"name":"f"}}]}}""",
            w,
        )
        val arr = out["tools"] as JSONArray
        assertEquals(1, arr.length())
        // org.json's toString() does not promise key order — compare structure.
        val only = JSONObject(arr.get(0).toString())
        assertEquals("function", only.getString("type"))
        assertEquals("f", only.getJSONObject("function").getString("name"))
    }

    // ── scalars / null / absent / malformed ─────────────────────────────

    @Test
    fun `json null is preserved as a key rather than dropped`() {
        val w = warn()
        val out = ModelUseProtocol.parseChatExtraBody("""{"extra_body":{"x":null,"y":1}}""", w)
        assertTrue("the key must survive", out.containsKey("x"))
        assertEquals(JSONObject.NULL, out["x"])
        assertEquals(1, out["y"])
        assertTrue("no warnings expected: $w", w.isEmpty())
    }

    @Test
    fun `unicode and escapes survive verbatim`() {
        val w = warn()
        val out = ModelUseProtocol.parseChatExtraBody("""{"extra_body":{"t":"a\nb«é»"}}""", w)
        assertEquals("a\nb«é»", out["t"])
        assertTrue(w.isEmpty())
    }

    @Test
    fun `absent extra_body yields no body and no warning`() {
        val w = warn()
        assertTrue(ModelUseProtocol.parseChatExtraBody("""{"messages":[]}""", w).isEmpty())
        assertTrue("an absent envelope is not a mistake: $w", w.isEmpty())
    }

    @Test
    fun `empty extra_body object yields no body and no warning`() {
        val w = warn()
        assertTrue(ModelUseProtocol.parseChatExtraBody("""{"extra_body":{}}""", w).isEmpty())
        assertTrue(w.isEmpty())
    }

    @Test
    fun `non-object extra_body is ignored with a type-mismatch warning`() {
        val w = warn()
        val out = ModelUseProtocol.parseChatExtraBody("""{"extra_body":"nope"}""", w)
        assertTrue(out.isEmpty())
        assertEquals(1, w.size)
        assertTrue(w[0].contains("extra_body is not a JSON object"))
        assertTrue(w[0].contains("string"))
    }

    @Test
    fun `malformed input json yields no body and no warning`() {
        val w = warn()
        assertTrue(ModelUseProtocol.parseChatExtraBody("not json at all", w).isEmpty())
        assertTrue(w.isEmpty())
    }

    // ── structural: one implementation, two callers ─────────────────────

    private fun readRepoFile(relative: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        return null
    }

    @Test
    fun `both processes delegate extra_body parsing to this one dialect`() {
        val handler = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt",
        ) ?: return // tree not on disk → skip (see class kdoc)
        val service = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt",
        ) ?: return
        for ((name, text) in listOf("ModelUseOffloadHandler.kt" to handler, "ModelExecutionService.kt" to service)) {
            assertTrue(
                "$name must delegate extra_body parsing to ModelUseProtocol",
                text.contains("ModelUseProtocol.parseChatExtraBody("),
            )
            assertFalse(
                "$name carries a second extra_body dialect again — index-based array rebuild",
                text.contains("(0 until v.length())"),
            )
        }
    }

    // ── [dv21-A06] one decode entry for the offload wire ────────────────────
    //
    // The wire carries media as base64 and the producers are not uniform:
    // `android.util.Base64.encodeToString(..., NO_WRAP)` writes one long line,
    // `Base64.DEFAULT` wraps at 76 chars. Each consumer hand-rolled its own
    // decoder, so the SAME field was accepted by one path and rejected by
    // another — and two of the strict ones threw the exception straight out of
    // the run (an attachment's malformed base64 discarded the whole reply).

    /** 80 ASCII bytes = "0123456789" x8; base64 is the literal below. */
    private val wirePayload = "0123456789".repeat(8)

    private companion object {
        const val WIRE_B64 =
            "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkw" +
                "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODk="
    }

    @Test
    fun `a MIME-wrapped payload decodes to the same bytes as the single-line form`() {
        val wrapped = WIRE_B64.chunked(76).joinToString("\r\n")
        assertEquals(WIRE_B64, WIRE_B64.chunked(76).joinToString(""))
        assertEquals(wirePayload, OffloadWireBase64.decode(WIRE_B64)?.toString(Charsets.UTF_8))
        assertEquals(
            "the wrapped RFC 2045 form must decode identically — the worker's " +
                "own DEFAULT-flag encoder can produce it",
            wirePayload,
            OffloadWireBase64.decode(wrapped)?.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `the strict RFC 4648 decoder is what rejects the wrapped form`() {
        // Teeth: proves the wrapped payload above is exactly the input the old
        // code (java.util.Base64.getDecoder().decode) could not read.
        val wrapped = WIRE_B64.chunked(76).joinToString("\r\n")
        var threw = false
        try {
            java.util.Base64.getDecoder().decode(wrapped)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("strict RFC 4648 decoding must reject a wrapped payload", threw)
    }

    @Test
    fun `a malformed payload yields null instead of throwing`() {
        assertEquals("an empty field is 'no bytes', not a decode error", null, OffloadWireBase64.decode(""))
        // A wrong final 4-byte unit is one of the few shapes the MIME decoder
        // does reject — and it must come back as null, not as an
        // IllegalArgumentException escaping a mapNotNull.
        assertEquals(null, OffloadWireBase64.decode("MDEyMzQ1Njc4OTAxMjM0NTY3O"))
        // The MIME alphabet is deliberately lenient (characters outside the
        // table are ignored rather than rejected), so the contract pinned here
        // is "never throws", not "detects every corruption". Record it so a
        // future change to a strict-round-trip validator is a deliberate edit.
        assertTrue(
            OffloadWireBase64.decode("not base64 at all !!!") != null,
        )
    }

    @Test
    fun `every offload-wire consumer decodes through the shared entry`() {
        val files = listOf(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt",
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelExecutionService.kt",
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ChatStreamJsonl.kt",
        )
        var checked = 0
        for (rel in files) {
            val text = readRepoFile(rel) ?: continue
            checked++
            val name = rel.substringAfterLast('/')
            assertFalse(
                "$name must not use the strict RFC 4648 decoder on the offload wire",
                codeOnly(text).contains("java.util.Base64.getDecoder()"),
            )
            assertTrue(
                "$name must decode media through OffloadWireBase64.decode",
                codeOnly(text).contains("OffloadWireBase64.decode("),
            )
        }
        if (checked == 0) return // tree not on disk
        val gateway = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ProviderExecutionGateway.kt",
        ) ?: return
        assertTrue(
            "the gateway's own MIME decode (pinned by offload_media_b64_wire_check) stays",
            gateway.contains("java.util.Base64.getMimeDecoder()"),
        )
        val helper = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/OffloadWireBase64.kt",
        ) ?: return
        assertTrue(helper.contains("java.util.Base64.getMimeDecoder()"))
        assertTrue(helper.contains("runCatching"))
    }

    /** Drop line and block comments (prose about a removed shape is legal). */
    private fun codeOnly(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i < src.length && !(src[i] == '*' && i + 1 < src.length && src[i + 1] == '/')) i++
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
