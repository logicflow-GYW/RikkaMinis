package com.rikkaminis.app.mcp.oauth

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R3-B F-18] `authorize()` validated the redirect URI by HOST only. The
 * callback server is a plain-HTTP `ServerSocket` bound to
 * [MCPOAuthController.LOOPBACK_PORT], so a redirect that is not exactly that
 * endpoint is undeliverable and the flow waits out its full 5-minute timeout:
 *
 *  - `http://localhost/callback` — no port, so the browser goes to :80;
 *  - `https://localhost:54546/callback` — TLS against a plain socket, so the
 *    Custom Tab fails with ERR_SSL_PROTOCOL_ERROR and no callback arrives.
 *
 * Both passed the host check. The endpoint check now runs before the browser
 * opens, so the user gets a sentence instead of a hang.
 */
class MCPOAuthRedirectEndpointTest {

    private val good = "http://localhost:54546/callback"

    @Test
    fun `a redirect without a port is refused before the browser opens`() {
        val err = MCPOAuthController.redirectEndpointError("http://localhost/callback")
        assertNotNull(
            "an implicit :80 redirect can never reach the 54546 callback server",
            err,
        )
        assertTrue(
            "the refusal must name the port the server listens on: $err",
            err!!.contains("54546"),
        )
    }

    @Test
    fun `the exact callback endpoint is accepted`() {
        assertNull(MCPOAuthController.redirectEndpointError(good))
        assertNull(MCPOAuthController.redirectEndpointError("http://127.0.0.1:54546/callback"))
        assertNull(
            "the shipped default must satisfy its own rule",
            MCPOAuthController.redirectEndpointError(MCPOAuthController.DEFAULT_REDIRECT_URI),
        )
    }

    @Test
    fun `an https redirect is refused`() {
        val err = MCPOAuthController.redirectEndpointError("https://localhost:54546/callback")
        assertNotNull(
            "the callback server answers plain HTTP; a TLS redirect never arrives",
            err,
        )
        assertTrue(
            "the refusal must say the scheme is the problem: $err",
            err!!.contains("http"),
        )
    }

    @Test
    fun `a non-loopback host is refused`() {
        for (host in listOf("http://evil.example:54546/callback", "http://0.0.0.0:54546/callback")) {
            val err = MCPOAuthController.redirectEndpointError(host)
            assertNotNull("$host must be refused", err)
            assertTrue(
                "the refusal must name the loopback requirement: $err",
                err!!.contains("loopback"),
            )
        }
    }

    @Test
    fun `a different loopback port is refused`() {
        val err = MCPOAuthController.redirectEndpointError("http://localhost:12345/callback")
        assertNotNull("the server is bound to one port, not any port", err)
        assertTrue("the refusal must name the real port: $err", err!!.contains("54546"))
    }

    @Test
    fun `a value that is not a URI is refused`() {
        assertNotNull(MCPOAuthController.redirectEndpointError("not a uri"))
        assertNotNull("a scheme-relative value has no host", MCPOAuthController.redirectEndpointError("//localhost:54546/callback"))
    }

    @Test
    fun `scheme and host case are not policy`() {
        assertNull(
            "URI schemes and hosts are case-insensitive",
            MCPOAuthController.redirectEndpointError("HTTP://LocalHost:54546/callback"),
        )
    }

    // ── wiring ───────────────────────────────────────────────────────────

    @Test
    fun `authorize consults the endpoint check before it opens the browser`() {
        val body = memberSlice(
            readRepoFile(
                "src/android/app/src/main/java/com/rikkaminis/app/mcp/oauth/MCPOAuthController.kt"
            ),
            "suspend fun authorize(",
        )
        val check = body.indexOf("redirectEndpointError(redirect)")
        assertTrue("authorize must consult the endpoint policy:\n$body", check >= 0)
        assertTrue(
            "a mismatch must fail the flow, not be logged:\n$body",
            body.contains("redirectEndpointError(redirect)?.let { return Result.Failed(it) }"),
        )
        val launch = body.indexOf("CustomTabsIntent.Builder()")
        assertTrue("authorize must still open the browser:\n$body", launch >= 0)
        assertTrue(
            "the check must run before the Custom Tab — otherwise the user waits out " +
                "the 5-minute timeout and only then learns the URI was wrong",
            check < launch,
        )
        assertFalse(
            "the host-only check that let an implicit :80 through must be gone",
            body.contains("val redirectHost = runCatching"),
        )
    }

    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val t = lines[i]
            t.startsWith("    /**") || t.startsWith("    @") || t.startsWith("    fun ") ||
                t.startsWith("    internal fun ") || t.startsWith("    private fun ") ||
                t.startsWith("    private suspend fun ") || t.startsWith("    private val ") ||
                t.startsWith("    // --")
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}