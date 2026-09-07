package com.openminis.app.browser

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/browser-console-network-upload] Parsing tests for the three new
 * browser_use actions and their params (clear / paths). Pure JVM — the
 * WebView-side behavior needs a device, but the input contract is exactly
 * what the LLM emits and must not drift.
 */
class BrowserActionInputParseTest {

    @Test
    fun parsesGetConsoleMessagesWithClear() {
        val json = JSONObject()
            .put("action", "get_console_messages")
            .put("clear", true)
            .toString()
        val input = BrowserActionInput.parse(json)!!
        assertEquals(BrowserAction.GET_CONSOLE_MESSAGES, input.action)
        assertTrue(input.clear)
        assertNull(input.paths)
    }

    @Test
    fun parsesGetNetworkRequestsDefaultNoClear() {
        val input = BrowserActionInput.parse(
            JSONObject().put("action", "get_network_requests").toString(),
        )!!
        assertEquals(BrowserAction.GET_NETWORK_REQUESTS, input.action)
        assertFalse(input.clear)
    }

    @Test
    fun parsesFileUploadPathsArray() {
        val input = BrowserActionInput.parse(
            JSONObject()
                .put("action", "file_upload")
                .put(
                    "paths",
                    org.json.JSONArray()
                        .put("/var/minis/attachments/a.png")
                        .put("/tmp/report.pdf"),
                )
                .toString(),
        )!!
        assertEquals(BrowserAction.FILE_UPLOAD, input.action)
        assertEquals(listOf("/var/minis/attachments/a.png", "/tmp/report.pdf"), input.paths)
    }

    @Test
    fun parsesFileUploadPathsAsJsonString() {
        // Schema-faithful models may emit the array as a JSON-encoded STRING
        // (mirrors the cookies dual-shape handling).
        val input = BrowserActionInput.parse(
            JSONObject()
                .put("action", "file_upload")
                .put("paths", "[\"/tmp/a.png\",\"/tmp/b.png\"]")
                .toString(),
        )!!
        assertEquals(listOf("/tmp/a.png", "/tmp/b.png"), input.paths)
    }

    @Test
    fun parsesFileUploadPathsAsCommaSeparatedString() {
        val input = BrowserActionInput.parse(
            JSONObject()
                .put("action", "file_upload")
                .put("paths", "/var/minis/attachments/x.jpg, /var/minis/workspace/y.csv")
                .toString(),
        )!!
        assertEquals(
            listOf("/var/minis/attachments/x.jpg", "/var/minis/workspace/y.csv"),
            input.paths,
        )
    }

    @Test
    fun fileUploadWithoutPathsParsesWithNullPaths() {
        val input = BrowserActionInput.parse(
            JSONObject().put("action", "file_upload").toString(),
        )!!
        assertEquals(BrowserAction.FILE_UPLOAD, input.action)
        assertNull(input.paths) // Manager turns this into a usage error
    }

    @Test
    fun blankPathsEntriesAreDropped() {
        val input = BrowserActionInput.parse(
            JSONObject()
                .put("action", "file_upload")
                .put("paths", "  , /tmp/ok.png , ")
                .toString(),
        )!!
        assertEquals(listOf("/tmp/ok.png"), input.paths)
    }

    @Test
    fun emptyPathsStringStaysNull() {
        val input = BrowserActionInput.parse(
            JSONObject().put("action", "file_upload").put("paths", "   ").toString(),
        )!!
        assertNull(input.paths)
    }

    @Test
    fun unknownActionStillReturnsNull() {
        assertNull(BrowserActionInput.parse("""{"action":"definitely_not_real"}"""))
    }

    @Test
    fun clearDefaultsFalseAndDoesNotLeakIntoOtherActions() {
        val input = BrowserActionInput.parse(
            JSONObject().put("action", "navigate").put("url", "https://example.com").toString(),
        )!!
        assertFalse(input.clear)
    }
}
