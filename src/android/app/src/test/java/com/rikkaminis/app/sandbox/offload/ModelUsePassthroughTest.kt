package com.rikkaminis.app.sandbox.offload

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/audit3-1015 F-03] `--passthrough` was unreachable.
 *
 * The app process assembled the request and then performed the HTTP call
 * itself — `openAI.rawPassthroughRequest(...)` inside ModelUseOffloadHandler,
 * i.e. in `:app`. ProviderBoundary refuses every provider network call outside
 * `:modelservice`, so the call could only ever throw and the branch always
 * answered `passthrough_failed`: the mode was 100% dead while its CLI contract
 * looked fine on paper. Nothing observed it, because the JVM path never reaches
 * the worker and no gate named the entry point.
 *
 * The fix splits the work along the process boundary the guard enforces:
 *   :app  — parse the envelope, assemble the body (shared pure module), ship it
 *           as `passthrough_request`, shape the reply, own --output / session IO
 *   worker — perform the HTTP call, return the raw bytes Base64-encoded
 *
 * Two kinds of expectation below, and they fail for different reasons:
 *  - behavioural — the request rules in [ModelUseProtocol]. Values are literals
 *    taken from the pre-fix implementation, which is the definition of "correct"
 *    here (it is what the iOS bridge and the help text describe).
 *  - structural — the boundary itself, read out of the production sources. The
 *    failure mode was "the call lives in the wrong process", and no runtime
 *    assertion in a unit test can see that; the sources can.
 */
class ModelUsePassthroughTest {

    private fun warn() = mutableListOf<String>()

    private fun spec(json: String) = ModelUseProtocol.parsePassthroughEnvelope(json)

    private fun body(
        json: String,
        modelId: String = "resolved-model",
        system: String? = "sys",
        messages: List<Pair<String, String>> = listOf("user" to "hi"),
        maxTokens: Int = 321,
        warnings: MutableList<String> = warn(),
    ) = ModelUseProtocol.buildRawPassthroughBody(
        spec = spec(json),
        modelId = modelId,
        systemPrompt = system,
        messages = messages,
        maxTokens = maxTokens,
        warnings = warnings,
    )

    // ── behavioural: envelope parsing ────────────────────────────────────

    @Test
    fun `a standard call is not passthrough`() {
        val s = spec("""{"model":"gpt-4o","messages":[{"role":"user","content":"hi"}]}""")
        assertFalse(s.active)
        assertTrue(s.warnings.isEmpty())
    }

    @Test
    fun `wrong-case top-level key is reported and ignored`() {
        val s = spec("""{"Passthrough":{"endpoint":"/v1/completions"}}""")
        assertFalse(s.active)
        assertEquals(1, s.warnings.size)
        assertTrue(
            "expected the lowercase-key hint, got: ${s.warnings}",
            s.warnings[0].contains("must be lowercase 'passthrough'"),
        )
    }

    @Test
    fun `method accepts PATCH and uppercases a lower-case verb`() {
        assertEquals("PATCH", spec("""{"passthrough":{"endpoint":"/v1/models","method":"patch"}}""").method)
        assertEquals("GET", spec("""{"passthrough":{"method":"get"}}""").method)
    }

    @Test
    fun `unsupported method degrades to POST with a warning`() {
        val s = spec("""{"passthrough":{"method":"TRACE"}}""")
        assertEquals("POST", s.method)
        assertTrue("expected a method warning, got: ${s.warnings}", s.warnings.any { it.contains("not supported") })
    }

    @Test
    fun `non-string header values are dropped with a warning`() {
        val s = spec("""{"passthrough":{"headers":{"X-Ok":"1","X-Bad":2}}}""")
        assertEquals(mapOf("X-Ok" to "1"), s.headers)
        assertTrue("expected a header warning, got: ${s.warnings}", s.warnings.any { it.contains("X-Bad") })
    }

    @Test
    fun `non-object body is ignored with a warning`() {
        val s = spec("""{"passthrough":{"body":"nope"}}""")
        assertTrue(s.active)
        assertTrue(s.body.isEmpty())
        assertTrue(
            "expected the wrap-it-in-an-object hint, got: ${s.warnings}",
            s.warnings.any { it.contains("passthrough.body is not a JSON object") },
        )
    }

    @Test
    fun `invalid body_mode is treated as merge with a warning`() {
        val s = spec("""{"passthrough":{"body_mode":"append"}}""")
        assertEquals("merge", s.bodyMode)
        assertTrue("expected a body_mode warning, got: ${s.warnings}", s.warnings.any { it.contains("only merge/replace") })
    }

    @Test
    fun `non-string endpoint falls back to the default path with a warning`() {
        val s = spec("""{"passthrough":{"endpoint":42}}""")
        assertTrue(s.active)
        assertNull(s.endpoint)
        assertTrue("expected an endpoint warning, got: ${s.warnings}", s.warnings.any { it.contains("passthrough.endpoint") })
    }

    // ── behavioural: body assembly ───────────────────────────────────────

    @Test
    fun `replace mode is verbatim and does not inject the model`() {
        val w = warn()
        val out = body(
            """{"passthrough":{"body_mode":"replace","body":{"model":"mine","prompt":"p"}}}""",
            warnings = w,
        )!!
        assertEquals(setOf("model", "prompt"), out.keys().asSequence().toSet())
        assertEquals("mine", out.getString("model"))   // replace mode leaves `model` to the caller
        assertFalse(out.has("messages"))
        assertFalse(out.has("max_tokens"))
        assertTrue(w.isEmpty())
    }

    @Test
    fun `replace mode with no body sends no body and says so`() {
        val w = warn()
        assertNull(body("""{"passthrough":{"body_mode":"replace"}}""", warnings = w))
        assertEquals(1, w.size)
        assertTrue("expected the NO-body warning, got: $w", w[0].contains("NO body"))
    }

    @Test
    fun `merge mode injects the standard shape and locks the model`() {
        val out = body("""{"passthrough":{"body":{"top_p":0.5,"model":"mine"}}}""")!!
        assertEquals("resolved-model", out.getString("model"))   // merge mode locks `model`
        assertEquals(321, out.getInt("max_tokens"))
        assertFalse(out.getBoolean("stream"))
        assertEquals(0.5, out.getDouble("top_p"), 0.0)
        val msgs = out.getJSONArray("messages")
        assertEquals(2, msgs.length())
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("sys", msgs.getJSONObject(0).getString("content"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
        assertEquals("hi", msgs.getJSONObject(1).getString("content"))
    }

    // ── behavioural: the app→worker envelope ─────────────────────────────

    @Test
    fun `wire envelope carries the assembled request and keeps absent values explicit`() {
        val s = spec(
            """{"passthrough":{"endpoint":"/v1/completions","method":"patch","headers":{"X-A":"1"},"body_mode":"replace","body":{"prompt":"p"}}}""",
        )
        val wire = ModelUseProtocol.buildPassthroughWireRequest(s, body("""{"passthrough":{"body_mode":"replace","body":{"prompt":"p"}}}"""))
        assertEquals("/v1/completions", wire.getString("endpoint"))
        assertEquals("PATCH", wire.getString("method"))
        assertEquals("1", wire.getJSONObject("headers").getString("X-A"))
        assertEquals("p", wire.getJSONObject("body").getString("prompt"))
        assertEquals("replace", wire.getString("body_mode"))

        // An absent endpoint/body must travel as explicit JSON null, not vanish:
        // the worker branches on presence, so a dropped key would silently change
        // the request it builds.
        val bare = ModelUseProtocol.buildPassthroughWireRequest(spec("""{"passthrough":{}}"""), null)
        assertTrue(bare.isNull("endpoint"))
        assertTrue(bare.isNull("body"))
        assertEquals("POST", bare.getString("method"))
    }

    // ── structural: the process boundary ─────────────────────────────────

    private val offload = "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload"

    private fun readRepoFile(relative: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        return null
    }

    private fun handler() = readRepoFile("$offload/ModelUseOffloadHandler.kt")
    private fun service() = readRepoFile("$offload/ModelExecutionService.kt")
    private fun protocol() = readRepoFile("$offload/ModelUseProtocol.kt")
    private fun dispatcher() = readRepoFile("$offload/ModelExecutionDispatcher.kt")

    @Test
    fun `the app process no longer performs the passthrough call`() {
        val h = handler() ?: return
        assertFalse(
            "ModelUseOffloadHandler must not call the provider entry point — " +
                "ProviderBoundary refuses it in :app and the mode dies again",
            h.contains(".rawPassthroughRequest("),
        )
        assertTrue(
            "the passthrough branch must dispatch to the worker",
            h.contains("ModelExecutionDispatcher.dispatch(context, ptRequestJson)"),
        )
        assertTrue(
            "the request must be shipped through the shared wire builder",
            h.contains("ModelUseProtocol.buildPassthroughWireRequest("),
        )
    }

    @Test
    fun `the worker owns the passthrough call and reads the shipped request`() {
        val s = service() ?: return
        assertTrue(
            "the worker must read the shipped envelope",
            s.contains("""req.optJSONObject("passthrough_request")"""),
        )
        assertEquals(
            "exactly one raw passthrough call, in the worker",
            1,
            Regex("""\.rawPassthroughRequest\(""").findAll(s).count(),
        )
        for (key in listOf("endpoint", "method", "headers", "body")) {
            assertTrue(
                "the worker must read '$key' — the wire envelope's payload",
                s.contains("""optString("$key"""") || s.contains("""optJSONObject("$key"""") || s.contains("""opt("$key")"""),
            )
        }
    }

    @Test
    fun `the dispatcher carries the envelope to the worker`() {
        val d = dispatcher() ?: return
        assertTrue(d.contains("passthroughRequest: JSONObject? = null,"))
        assertTrue(d.contains("""put("passthrough_request", passthroughRequest)"""))
    }

    @Test
    fun `env parsing and body assembly exist exactly once`() {
        val protocolText = protocol() ?: return
        val sources = listOfNotNull(
            handler()?.let { it to "handler" },
            service()?.let { it to "worker" },
            protocolText to "protocol",
        )
        for (def in listOf("fun parsePassthroughEnvelope(", "fun buildRawPassthroughBody(", "data class PassthroughSpec(")) {
            val total = sources.sumOf { (text, _) -> Regex(Regex.escape(def)).findAll(text).count() }
            assertEquals("'$def' must be defined exactly once (in ModelUseProtocol)", 1, total)
            assertTrue(
                "'$def' belongs to the shared module, not the handler",
                protocolText.contains(def),
            )
        }
    }

    @Test
    fun `reply keys agree across the process boundary`() {
        val h = handler() ?: return
        val s = service() ?: return
        for (key in listOf("http_status", "content_type", "endpoint_url", "body_b64")) {
            assertTrue("the worker must emit '$key'", s.contains("\"$key\""))
            assertTrue("the app must read '$key'", h.contains("\"$key\""))
        }
        assertTrue("both ends must share the failure code", s.contains("passthrough_failed") && h.contains("passthrough_failed"))
    }
}
