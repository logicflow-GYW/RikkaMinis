package com.rikkaminis.app.mcp.oauth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FULLSCAN-1119 F-18] The inbound callback request line carries
 * `?code=<authorization code>&state=<CSRF nonce>`; app logs are persisted by
 * LogcatTailer into a SHAREABLE file, so `Log.d(tag, "Request: $requestLine")`
 * published a one-shot credential plus the CSRF value to anyone who can read or
 * receive that file.
 *
 * Every fixture value below is a literal FAKE (`abc123`, `xyz789`); no real
 * credential, no file is read or written.
 *
 * Two halves:
 *  * [callbackRequestSummary] is the only thing still logged — it must keep the
 *    diagnostic ("a callback arrived on /callback, and were the parameters
 *    present") and drop every value.
 *  * the source must not grow a second leak path: no `Log.*` call in the file
 *    may interpolate the request line, the raw query or a decoded parameter.
 */
class OAuthCallbackLogRedactionTest {

    private companion object {
        const val SERVER = "app/src/main/java/com/rikkaminis/app/mcp/oauth/OAuthCallbackServer.kt"
        const val FAKE_CODE = "abc123"
        const val FAKE_STATE = "xyz789"
    }

    // -- the summary ----------------------------------------------------------

    @Test
    fun `the summary keeps the callback shape and drops both values`() {
        val line = "GET /callback?code=$FAKE_CODE&state=$FAKE_STATE HTTP/1.1"
        val summary = callbackRequestSummary(line)

        assertFalse("the authorization code must not survive", summary.contains(FAKE_CODE))
        assertFalse("the CSRF state must not survive", summary.contains(FAKE_STATE))
        assertTrue("the method is diagnostic and harmless: $summary", summary.contains("GET"))
        assertTrue("the path proves the callback arrived: $summary", summary.contains("/callback"))
        assertTrue("the code's PRESENCE must stay visible: $summary", summary.contains("code=len=6"))
        assertTrue("the state's PRESENCE must stay visible: $summary", summary.contains("state=len=6"))
        assertTrue("a missing error must read as absent: $summary", summary.contains("error=absent"))
    }

    @Test
    fun `a denial is still distinguishable from a missing parameter`() {
        val denied = callbackRequestSummary("GET /callback?error=access_denied&state=$FAKE_STATE HTTP/1.1")
        assertTrue("denial is visible: $denied", denied.contains("error=len=13"))
        assertTrue("code is absent: $denied", denied.contains("code=absent"))

        // RFC 6749 §4.1.2.1: `?error=` (present, empty) is a refusal too, and
        // the server must keep telling it apart from "no error parameter".
        val empty = callbackRequestSummary("GET /callback?error=&state=$FAKE_STATE HTTP/1.1")
        assertTrue("an empty error must not read as absent: $empty", empty.contains("error=empty"))
    }

    @Test
    fun `the preflight line has no query and is summarized without it`() {
        val summary = callbackRequestSummary("OPTIONS /callback HTTP/1.1")
        assertTrue(summary, summary.contains("OPTIONS"))
        assertTrue(summary, summary.contains("/callback"))
        assertTrue(summary, summary.contains("no query"))
    }

    @Test
    fun `hostile key names are counted, never echoed`() {
        val summary = callbackRequestSummary(
            "GET /callback?code=$FAKE_CODE&session_token=$FAKE_STATE HTTP/1.1",
        )
        assertFalse("an attacker-chosen KEY must not be echoed", summary.contains("session_token"))
        assertFalse("its value must not be echoed", summary.contains(FAKE_STATE))
        assertTrue("but the parameter count still tells the shape: $summary", summary.contains("params=2"))
    }

    @Test
    fun `a line with no target is not echoed either`() {
        val summary = callbackRequestSummary("GET")
        assertTrue(summary, summary.contains("unparseable request line"))
    }

    // -- no second leak path in the source ------------------------------------

    @Test
    fun `no Log call interpolates the request line, raw query or a parameter`() {
        val src = codeOnly(readRepoFile(SERVER))

        val logLines = src.lines().filter { line ->
            Regex("""\bLog\.(d|i|w|e|v)\s*\(""").containsMatchIn(line)
        }
        assertTrue("expected to find the server's log calls", logLines.isNotEmpty())

        // The summary is the ONLY allowed use of the raw line: every log call
        // that mentions it must be exactly that call.
        val summaryCall = "Log.d(TAG, callbackRequestSummary(requestLine))"
        val mentions = logLines.filter { it.contains("requestLine") }
        assertTrue(
            "the request line may only reach the log through the summary: $mentions",
            mentions.all { it.trim() == summaryCall },
        )

        val leaks = logLines.filter { line ->
            listOf("rawQuery", "params[", "params.get", "uri.", "decoded").any(line::contains)
        }
        assertTrue(
            "these log calls would publish callback values: $leaks",
            leaks.isEmpty(),
        )

        // The pre-fix shape must not come back, verbatim or with the same idea.
        assertFalse(
            "the raw request line must not be logged",
            src.contains("\"Request: \$requestLine\""),
        )
    }

    @Test
    fun `the summary is what the server actually logs`() {
        val src = codeOnly(readRepoFile(SERVER))
        assertTrue(
            "the request line must be logged through callbackRequestSummary",
            src.contains("Log.d(TAG, callbackRequestSummary(requestLine))"),
        )
    }

    @Test
    fun `a malformed target is reported by length only`() {
        val src = codeOnly(readRepoFile(SERVER))
        // URI() throws with the whole target in the message and the catch-all
        // logs the exception — so the parse must sit behind its own catch.
        assertTrue(
            "the target must be parsed behind a narrow catch",
            src.contains("catch (_: java.net.URISyntaxException)"),
        )
        assertFalse(
            "the parse must not run unguarded in the main body",
            src.contains("val uri = URI(\"http://localhost\" + parts[1])"),
        )
        assertTrue(
            "a failed parse logs the LENGTH, never the text",
            src.contains("Unparseable callback target (\${parts[1].length} chars)"),
        )
    }

    // -- helpers --------------------------------------------------------------

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from ${System.getProperty("user.dir")}")
    }

    /** Comments blanked: KDoc that NAMES a shape is not that shape. */
    private fun codeOnly(src: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < src.length) {
            if (src.startsWith("//", i)) {
                val j = src.indexOf('\n', i)
                i = if (j < 0) src.length else j
                continue
            }
            if (src.startsWith("/*", i)) {
                val j = src.indexOf("*/", i + 2)
                i = if (j < 0) src.length else j + 2
                continue
            }
            out.append(src[i])
            i++
        }
        return out.toString()
    }
}
