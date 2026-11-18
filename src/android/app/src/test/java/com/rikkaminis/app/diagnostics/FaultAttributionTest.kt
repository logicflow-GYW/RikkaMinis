package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [W1118-T6 R6 F-01] Fault-layer attribution: the specific vocabulary must win.
 *
 * `attribute()` walks its pattern lists in order and returns on the first hit.
 * The old order was credentials → CONNECTION → SERVICE, and connection's
 * `"timeout"` is a substring of service's `"first chunk timeout"`, so every
 * first-chunk budget failure — "the endpoint accepted the request and then went
 * silent", the one case the service layer exists for — was reported as the
 * user's network/proxy's fault and the service phrases were unreachable.
 *
 * The module has no production consumer yet (the panel that will read it is a
 * registered follow-up), so nothing user-visible changes today: the point is
 * that wiring it up cannot ship the misattribution, and that a future pattern
 * added to the connection list cannot re-shadow the service list unnoticed.
 *
 * Expectations are literal strings: they must not move when a pattern list is
 * edited.
 */
class FaultAttributionTest {

    private val sourcePath = "src/main/java/com/rikkaminis/app/diagnostics/FaultAttribution.kt"

    @Test
    fun `a first-chunk budget failure is attributed to the service`() {
        val result = FaultAttribution.attribute("upstream failed: first chunk timeout after 30s")
        assertEquals(FaultAttribution.Layer.SERVICE, result.layer)
        assertEquals("fault_layer_service", result.labelResKey)
    }

    @Test
    fun `an http status is attributed to the service`() {
        val result = FaultAttribution.attribute("HTTP 503 from relay")
        assertEquals(FaultAttribution.Layer.SERVICE, result.layer)
    }

    @Test
    fun `a connection-leg timeout stays on the connection layer`() {
        // The half that must NOT move: nothing here appears in the service list.
        assertEquals(
            FaultAttribution.Layer.CONNECTION,
            FaultAttribution.attribute("java.net.SocketTimeoutException: connect timed out").layer,
        )
        assertEquals(
            FaultAttribution.Layer.CONNECTION,
            FaultAttribution.attribute("java.net.ConnectException: connection refused").layer,
        )
        assertEquals(
            FaultAttribution.Layer.CONNECTION,
            FaultAttribution.attribute("unable to resolve host api.example.com").layer,
        )
    }

    @Test
    fun `bad credentials still win over both layers`() {
        // Credentials is checked first on purpose: an "expired key" is a 401 to
        // the endpoint (service vocabulary) but the user's fix is the key.
        assertEquals(
            FaultAttribution.Layer.CREDENTIALS,
            FaultAttribution.attribute("HTTP 401 invalid api key").layer,
        )
    }

    @Test
    fun `unknown text is reported as unclear`() {
        assertEquals(FaultAttribution.Layer.UNCLEAR, FaultAttribution.attribute(null).layer)
        assertEquals(FaultAttribution.Layer.UNCLEAR, FaultAttribution.attribute("   ").layer)
        assertEquals(FaultAttribution.Layer.UNCLEAR, FaultAttribution.attribute("something odd").layer)
    }

    @Test
    fun `the service list is consulted before the connection list`() {
        assertServiceBeforeConnection(source())
    }

    // ─── F-03: status codes are tokens, not substrings ──────────────────────

    /**
     * [F-03] A millisecond count must not be read as an HTTP status code.
     *
     * The codes used to be space-prefixed substrings (a space, then three
     * digits) matched with a bare `contains`, which fixes the boundary on the
     * left and none on the right. A connection-leg message carrying a duration
     * — the normal shape of every timeout we classify — therefore matched the
     * SERVICE list first and came back as the relay's fault: `"after 500 ms"`
     * contains that substring, `"connect 5020 ms"` contains the 502 one, and
     * `"port 4030"` contains the 403 one. The user is told to complain to their
     * relay operator about a failure that is on their own network leg.
     *
     * Every sample below carries connection vocabulary, so the expected answer
     * is unambiguous: only the status-code arm could move it to SERVICE.
     */
    @Test
    fun `a millisecond count is not read as an http status code`() {
        val connection = FaultAttribution.Layer.CONNECTION
        assertEquals(
            connection,
            FaultAttribution.attribute("java.net.SocketTimeoutException: connect timed out after 500 ms").layer,
        )
        assertEquals(
            connection,
            FaultAttribution.attribute("connect timed out: proxy retry in 400 ms").layer,
        )
        assertEquals(
            connection,
            FaultAttribution.attribute("read timed out after 2500 ms (connect 5020 ms)").layer,
        )
        assertEquals(
            connection,
            FaultAttribution.attribute("connection reset while connect 5040 ms elapsed").layer,
        )
    }

    @Test
    fun `the status-code boundary holds on both sides of the digits`() {
        val connection = FaultAttribution.Layer.CONNECTION
        // Right-hand side: a longer number, a fraction, and a duration unit.
        assertEquals(
            connection,
            FaultAttribution.attribute("connect timed out, elapsed 5000ms").layer,
        )
        assertEquals(
            connection,
            FaultAttribution.attribute("connect timed out after 1500 ms").layer,
        )
        assertEquals(
            connection,
            FaultAttribution.attribute("connect timed out, ratio 500.5").layer,
        )
        // Left-hand side: a code glued to a longer number is not a code.
        assertEquals(
            connection,
            FaultAttribution.attribute("connect timed out, port 4030 refused").layer,
        )
    }

    /**
     * The half that must NOT move: real status codes keep their layer. These
     * are the shapes the panel will actually see, and two of them (`status=500`,
     * `code: 503`) never matched the old space-prefixed substring at all — the
     * token rule is strictly wider there.
     */
    @Test
    fun `real http status codes are still attributed to the service`() {
        val service = FaultAttribution.Layer.SERVICE
        assertEquals(service, FaultAttribution.attribute("HTTP 500 from relay").layer)
        assertEquals(service, FaultAttribution.attribute("upstream returned status=500").layer)
        assertEquals(service, FaultAttribution.attribute("error code: 503").layer)
        assertEquals(service, FaultAttribution.attribute("got a 429, slow down").layer)
        assertEquals(service, FaultAttribution.attribute("502 Bad Gateway").layer)
        assertEquals(service, FaultAttribution.attribute("response: 400 (bad request)").layer)
        assertEquals(service, FaultAttribution.attribute(" 500").layer)
        assertEquals(service, FaultAttribution.attribute("504").layer)
    }

    /**
     * The probe must be able to fail: the pre-fix substring rule misattributes.
     *
     * The pre-fix rule is reproduced here as a local lambda (the space-prefixed
     * code substrings + a bare `contains`), so the control proves the OLD
     * behaviour really differs on these samples instead of merely asserting the
     * new one. Every sample carries connection vocabulary, which is what makes
     * the expected answer unambiguous — without it the old rule and the new one
     * could agree for the wrong reason.
     */
    @Test
    fun `the token rule rejects the pre-fix substring vocabulary`() {
        val preFixCodes = listOf(
            " 400", " 401", " 403", " 429", " 500", " 502", " 503", " 504",
        )
        fun preFix(raw: String): FaultAttribution.Layer =
            if (preFixCodes.any { raw.contains(it) }) FaultAttribution.Layer.SERVICE
            else FaultAttribution.Layer.CONNECTION

        val samples = listOf(
            "java.net.SocketTimeoutException: connect timed out after 500 ms",
            "connect timed out: proxy retry in 400 ms",
            "read timed out after 2500 ms (connect 5020 ms)",
            "connect timed out, port 4030 refused",
        )
        for (s in samples) {
            assertEquals(
                "the pre-fix rule must really misattribute \"$s\" — otherwise this control proves nothing",
                FaultAttribution.Layer.SERVICE,
                preFix(s),
            )
            assertEquals(
                "the token rule must get \"$s\" right",
                FaultAttribution.Layer.CONNECTION,
                FaultAttribution.attribute(s).layer,
            )
        }
    }

    /**
     * The status-code class must stay a regex in the source. A future edit that
     * drops back to `contains(" 500")` would silently restore the hijack, and
     * the behavioural tests above would only catch it for the samples they
     * happen to list.
     */
    @Test
    fun `status codes are matched by the token pattern, not by a substring`() {
        val body = source()
        assertNotNull(
            "the status-code token pattern is gone",
            Regex("private val STATUS_CODE_PATTERN\\s*=\\s*Regex\\(").find(body),
        )
        assertTrue(
            "status codes must be matched through STATUS_CODE_PATTERN",
            body.contains("STATUS_CODE_PATTERN.containsMatchIn(raw)"),
        )
        // Comments may (and do) quote the pre-fix literals to explain them —
        // only the CODE is judged, so the check runs on a comment-stripped copy.
        val code = body.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        for (code3 in listOf("400", "401", "403", "429", "500", "502", "503", "504")) {
            assertFalse(
                "the space-prefixed substring \" $code3\" must be gone from the pattern lists",
                code.contains("\" $code3\""),
            )
        }
        // And the pattern itself must carry the boundaries it exists for.
        val declAt = body.indexOf("STATUS_CODE_PATTERN = Regex(")
        val patternText = body.substring(declAt, minOf(body.length, declAt + 400))
        assertTrue(
            "the pattern must reject a preceding digit (left boundary): $patternText",
            patternText.contains("(?<![\\\\d.])"),
        )
        assertTrue(
            "the pattern must reject a duration unit on the right: $patternText",
            patternText.contains("(?!\\\\s*ms)"),
        )
    }

    /** Order probe over the real `attribute()` body. */
    private fun assertServiceBeforeConnection(body: String) {
        val fn = body.substringAfter("fun attribute(failureReason: String?)")
        val credentials = fn.indexOf("CREDENTIAL_PATTERNS.any")
        val service = fn.indexOf("SERVICE_PATTERNS.any")
        val connection = fn.indexOf("CONNECTION_PATTERNS.any")
        assertTrue("credentials must still be checked", credentials >= 0)
        assertTrue("service must still be checked", service >= 0)
        assertTrue("connection must still be checked", connection >= 0)
        assertTrue(
            "service must be checked BEFORE connection (service@$service, connection@$connection) — " +
                "\"timeout\" shadows \"first chunk timeout\" in the other order",
            service < connection,
        )
        assertTrue("credentials keeps winning over both", credentials < service)
    }

    @Test
    fun `the order probe rejects the pre-fix ordering`() {
        val real = source()
        val serviceBlock = "        if (SERVICE_PATTERNS.any { raw.contains(it) }) {\n" +
            "            return Result(Layer.SERVICE, \"fault_layer_service\")\n" +
            "        }\n"
        val connectionBlock = "        if (CONNECTION_PATTERNS.any { raw.contains(it) }) {\n" +
            "            return Result(Layer.CONNECTION, \"fault_layer_connection\")\n" +
            "        }\n"
        assertTrue("the two branches must be adjacent in the fixed source", real.contains(serviceBlock + connectionBlock))
        val preFix = real.replace(serviceBlock + connectionBlock, connectionBlock + serviceBlock)
        assertFalse("the mutation must really swap the two branches", preFix == real)
        assertEquals(
            "the pre-fix ordering must be rejected",
            true,
            runCatching { assertServiceBeforeConnection(preFix) }.isFailure,
        )
    }

    private fun source(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, sourcePath)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$sourcePath not found from user.dir")
    }
}
