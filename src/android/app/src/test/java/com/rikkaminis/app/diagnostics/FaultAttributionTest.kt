package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
