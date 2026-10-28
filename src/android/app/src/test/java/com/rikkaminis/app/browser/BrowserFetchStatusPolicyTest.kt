package com.rikkaminis.app.browser

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r3-B09 F-11] `fetch` had both directions wrong at once:
 *
 *  - a 404 / 500 was reported as a SUCCESSFUL fetch carrying the server's error
 *    page — `status` was read only to be printed, never checked, so the agent
 *    "fetched" an HTML error page and went on to parse it as data;
 *  - a 204 (or any 200 with a 0-byte body) was reported as "Fetch returned no
 *    body" — an ERROR for exactly the responses the fetch succeeded on.
 *
 * The policy lives in the pure [fetchHttpFailure] so both directions can be
 * pinned with literals; `fetch` itself needs a WebView.
 */
class BrowserFetchStatusPolicyTest {

    // ── the policy ──────────────────────────────────────────────────────

    @Test
    fun `every 2xx is a success`() {
        assertNull("200 must not fail", fetchHttpFailure(200, "https://example.com/a"))
        assertNull("204 (no content) must not fail", fetchHttpFailure(204, "https://example.com/a"))
        assertNull("206 (partial) must not fail", fetchHttpFailure(206, "https://example.com/a"))
        assertNull("299 must not fail", fetchHttpFailure(299, "https://example.com/a"))
    }

    @Test
    fun `a missing status is not a failure`() {
        assertNull(
            "status 0 means the JS never reported one (bridge error / older shape) — " +
                "the body is still the best evidence we have",
            fetchHttpFailure(0, "https://example.com/a"),
        )
    }

    @Test
    fun `non-2xx is a failure that names the status`() {
        for (code in listOf(300, 301, 304, 400, 403, 404, 429, 500, 503)) {
            val msg = fetchHttpFailure(code, "https://example.com/a")
            assertNotNull("HTTP $code must be a failure", msg)
            assertTrue(
                "the failure must name the status so the agent can tell 404 from 429 " +
                    "(got: $msg)",
                msg!!.contains("HTTP $code"),
            )
            assertTrue(
                "the failure must say the body is the error page, not the resource " +
                    "(that is the loaded trap: the caller used to save it as data)",
                msg.contains("error page"),
            )
            assertTrue(
                "the failure must name the URL it failed on",
                msg.contains("https://example.com/a"),
            )
        }
    }

    // ── wiring (shape) ──────────────────────────────────────────────────

    @Test
    fun `fetch checks the status before it hands the body over`() {
        val body = memberSlice(readRepoFile(ManagerPath), "private suspend fun fetch(")
        val check = body.indexOf("fetchHttpFailure(status, finalURL)")
        val handOver = body.indexOf("fetchedFileData = data")
        assertTrue("fetch must consult the status policy:\n$body", check >= 0)
        assertTrue("fetch must still return the payload:\n$body", handOver >= 0)
        assertTrue(
            "the status gate must run BEFORE the body is returned, or a 404 body is " +
                "still reported as a successful fetch",
            check < handOver,
        )
    }

    @Test
    fun `a 2xx with no body is an empty success, not an error`() {
        val body = memberSlice(readRepoFile(ManagerPath), "private suspend fun fetch(")
        val nullData = body.indexOf("if (data == null) {")
        assertTrue("the no-body branch must exist:\n$body", nullData >= 0)
        val branch = body.substring(nullData)
        assertTrue(
            "the no-body branch must distinguish 2xx (empty resource, success) from " +
                "the rest (real failure):\n$branch",
            branch.contains("if (status in 200..299) {"),
        )
        assertTrue(
            "the empty success must still carry a file payload (the tool surface " +
                "writes it), just zero bytes:\n$branch",
            branch.contains("fetchedFileData = ByteArray(0)"),
        )
    }

    @Test
    fun `the fetch JS escapes CR as well as LF`() {
        val body = memberSlice(readRepoFile(JsPath), "fun fetch(url: String): String {")
        assertTrue(
            "a raw CR inside the injected string literal is a JS SyntaxError for the " +
                "whole IIFE — `\\n` alone does not cover it:\n$body",
            body.contains(".replace(\"\\r\", \"\\\\r\")"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val t = lines[i]
            t.startsWith("    /**") || t.startsWith("    @") ||
                t.startsWith("    fun ") || t.startsWith("    internal fun ") ||
                t.startsWith("    private fun ") || t.startsWith("    private suspend fun ") ||
                t.startsWith("    internal val ") || t.startsWith("    private val ") ||
                t.startsWith("    // --")
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private companion object {
        private const val ManagerPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserUseManager.kt"
        private const val JsPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserUseJS.kt"
    }
}
