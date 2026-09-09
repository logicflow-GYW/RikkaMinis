package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ChatViewModelUtils] pure functions extracted from
 * [ChatViewModel].
 */
class ChatViewModelUtilsTest {

    // ── streamFlushThrottleMs ──────────────────────────────────────────────

    @Test fun `flushThrottle under 500 returns 200ms`() {
        assertEquals(200L, streamFlushThrottleMs(0))
        assertEquals(200L, streamFlushThrottleMs(499))
    }

    @Test fun `flushThrottle 500 to 1999 returns 300ms`() {
        assertEquals(300L, streamFlushThrottleMs(500))
        assertEquals(300L, streamFlushThrottleMs(1_999))
    }

    @Test fun `flushThrottle 2000 to 31999 returns 500ms`() {
        assertEquals(500L, streamFlushThrottleMs(2_000))
        assertEquals(500L, streamFlushThrottleMs(31_999))
    }

    @Test fun `flushThrottle 32000 to 63999 returns 1000ms`() {
        assertEquals(1_000L, streamFlushThrottleMs(32_000))
        assertEquals(1_000L, streamFlushThrottleMs(63_999))
    }

    @Test fun `flushThrottle 64000 to 127999 returns 1500ms`() {
        assertEquals(1_500L, streamFlushThrottleMs(64_000))
        assertEquals(1_500L, streamFlushThrottleMs(127_999))
    }

    @Test fun `flushThrottle 128000 and above returns 2000ms`() {
        assertEquals(2_000L, streamFlushThrottleMs(128_000))
        assertEquals(2_000L, streamFlushThrottleMs(1_000_000))
    }

    @Test fun `flushThrottle boundary values are exact`() {
        assertEquals(200L, streamFlushThrottleMs(499))
        assertEquals(300L, streamFlushThrottleMs(500))
        assertEquals(300L, streamFlushThrottleMs(1_999))
        assertEquals(500L, streamFlushThrottleMs(2_000))
        assertEquals(500L, streamFlushThrottleMs(31_999))
        assertEquals(1_000L, streamFlushThrottleMs(32_000))
        assertEquals(1_000L, streamFlushThrottleMs(63_999))
        assertEquals(1_500L, streamFlushThrottleMs(64_000))
        assertEquals(1_500L, streamFlushThrottleMs(127_999))
        assertEquals(2_000L, streamFlushThrottleMs(128_000))
    }

    // ── friendlyToolTitle ──────────────────────────────────────────────────

    @Test fun `friendlyToolTitle known tools return humanized labels`() {
        assertEquals("Execute Shell", friendlyToolTitle("shell_execute"))
        assertEquals("Read File", friendlyToolTitle("file_read"))
        assertEquals("Write File", friendlyToolTitle("file_write"))
        assertEquals("Edit File", friendlyToolTitle("file_edit"))
        assertEquals("Browse Web", friendlyToolTitle("browser_use"))
        assertEquals("Search Web", friendlyToolTitle("web_search"))
        assertEquals("Read Memory", friendlyToolTitle("memory_get"))
        assertEquals("Write Memory", friendlyToolTitle("memory_write"))
        assertEquals("Read Image", friendlyToolTitle("read_image"))
    }

    @Test fun `friendlyToolTitle unknown snake_case is title-cased`() {
        assertEquals("My Custom Tool", friendlyToolTitle("my_custom_tool"))
    }

    @Test fun `friendlyToolTitle single word returns capitalized`() {
        assertEquals("Echo", friendlyToolTitle("echo"))
    }

    @Test fun `friendlyToolTitle empty string returns empty string`() {
        assertEquals("", friendlyToolTitle(""))
    }

    @Test fun `friendlyToolTitle already camelCase kept as-is`() {
        assertEquals("MyTool", friendlyToolTitle("MyTool"))
    }

    @Test fun `friendlyToolTitle multiple underscores`() {
        assertEquals("A B C", friendlyToolTitle("a_b_c"))
    }

    @Test fun `friendlyToolTitle leading underscore`() {
        // split("_") gives ["", "a"] → filter { it.isNotEmpty() } → ["a"] → "A"
        assertEquals("A", friendlyToolTitle("_a"))
    }

    // ── parseToolParams ────────────────────────────────────────────────────

    @Test fun `parseToolParams blank input returns empty map`() {
        assertTrue(parseToolParams("").isEmpty())
        assertTrue(parseToolParams("  ").isEmpty())
    }

    @Test fun `parseToolParams valid JSON returns mapped entries`() {
        val result = parseToolParams("""{"path":"/tmp/x","count":42}""")
        assertEquals("/tmp/x", result["path"])
        assertEquals(42, result["count"])
    }

    @Test fun `parseToolParams null value maps to null`() {
        val result = parseToolParams("""{"path":null}""")
        assertNull(result["path"])
        assertTrue(result.containsKey("path"))
    }

    @Test fun `parseToolParams malformed JSON returns empty map`() {
        assertTrue(parseToolParams("{broken}").isEmpty())
        assertTrue(parseToolParams("not json").isEmpty())
    }

    @Test fun `parseToolParams empty object returns empty map`() {
        assertTrue(parseToolParams("{}").isEmpty())
    }

    @Test fun `parseToolParams nested objects`() {
        val result = parseToolParams("""{"outer":{"inner":"val"}}""")
        // org.json.JSONObject stores nested objects as JSONObject, not Map
        assertEquals("val", (result["outer"] as? org.json.JSONObject)?.getString("inner"))
    }

    @Test fun `parseToolParams array values`() {
        val result = parseToolParams("""{"items":[1,2,3]}""")
        val arr = result["items"] as? org.json.JSONArray
        assertEquals(3, arr?.length())
    }

    // ── escapeJson ─────────────────────────────────────────────────────────

    @Test fun `escapeJson plain text wraps in quotes`() {
        assertEquals("\"hello\"", escapeJson("hello"))
    }

    @Test fun `escapeJson double quote is escaped`() {
        assertEquals("\"say \\\"hi\\\"\"", escapeJson("say \"hi\""))
    }

    @Test fun `escapeJson backslash is escaped`() {
        assertEquals("\"a\\\\b\"", escapeJson("a\\b"))
    }

    @Test fun `escapeJson newline becomes backslash-n`() {
        assertEquals("\"a\\nb\"", escapeJson("a\nb"))
    }

    @Test fun `escapeJson carriage return becomes backslash-r`() {
        assertEquals("\"a\\rb\"", escapeJson("a\rb"))
    }

    @Test fun `escapeJson tab becomes backslash-t`() {
        assertEquals("\"a\\tb\"", escapeJson("a\tb"))
    }

    @Test fun `escapeJson control char becomes unicode escape`() {
        assertEquals("\"\\u0000\"", escapeJson("\u0000"))
        assertEquals("\"\\u001f\"", escapeJson("\u001f"))
    }

    @Test fun `escapeJson empty string`() {
        assertEquals("\"\"", escapeJson(""))
    }

    @Test fun `escapeJson mixed special chars`() {
        assertEquals("\"\\\"\\\\\\n\\r\\t\"", escapeJson("\"\\\n\r\t"))
    }

    @Test fun `escapeJson normal chars unaffected`() {
        assertEquals("\"abc123\"", escapeJson("abc123"))
    }

    // ── extractPartialStringValue ──────────────────────────────────────────

    @Test fun `extractPartialStringValue simple key`() {
        assertEquals("world", extractPartialStringValue("hello", """{"hello": "world"}"""))
    }

    @Test fun `extractPartialStringValue key not found returns null`() {
        assertNull(extractPartialStringValue("missing", """{"hello": "world"}"""))
    }

    @Test fun `extractPartialStringValue key with escaped chars`() {
        assertEquals("line1\nline2", extractPartialStringValue("text", """{"text": "line1\nline2"}"""))
    }

    @Test fun `extractPartialStringValue key with escaped quotes`() {
        assertEquals("say \"hi\"", extractPartialStringValue("msg", """{"msg": "say \"hi\""}"""))
    }

    @Test fun `extractPartialStringValue no space after colon`() {
        assertEquals("val", extractPartialStringValue("k", """{"k":"val"}"""))
    }

    @Test fun `extractPartialStringValue empty string value`() {
        assertEquals("", extractPartialStringValue("k", """{"k": ""}"""))
    }

    @Test fun `extractPartialStringValue partial truncated json`() {
        // Simulate streaming JSON where the value is truncated
        assertEquals("hello", extractPartialStringValue("msg", """{"msg": "hello"""))
    }

    @Test fun `extractPartialStringValue multiple keys picks correct one`() {
        assertEquals("two", extractPartialStringValue("second", """{"first": "one", "second": "two", "third": "three"}"""))
    }

    // ── findUnescapedEnd ───────────────────────────────────────────────────

    @Test fun `findUnescapedEnd normal string`() {
        assertEquals("hello", findUnescapedEnd("hello\""))
    }

    @Test fun `findUnescapedEnd no quote returns whole string`() {
        assertEquals("hello", findUnescapedEnd("hello"))
    }

    @Test fun `findUnescapedEnd escaped quote skipped`() {
        assertEquals("say \\\"hi", findUnescapedEnd("say \\\"hi\""))
    }

    @Test fun `findUnescapedEnd empty string`() {
        assertEquals("", findUnescapedEnd(""))
    }

    @Test fun `findUnescapedEnd empty string with quote`() {
        assertEquals("", findUnescapedEnd("\""))
    }

    @Test fun `findUnescapedEnd multiple escaped`() {
        assertEquals("a\\\\b\\\"c", findUnescapedEnd("a\\\\b\\\"c\""))
    }

    // ── unescapePartialJsonString ──────────────────────────────────────────

    @Test fun `unescapePartialJsonString newline`() {
        assertEquals("a\nb", unescapePartialJsonString("a\\nb"))
    }

    @Test fun `unescapePartialJsonString tab`() {
        assertEquals("a\tb", unescapePartialJsonString("a\\tb"))
    }

    @Test fun `unescapePartialJsonString quote`() {
        assertEquals("a\"b", unescapePartialJsonString("a\\\"b"))
    }

    @Test fun `unescapePartialJsonString slash`() {
        assertEquals("a/b", unescapePartialJsonString("a\\/b"))
    }

    @Test fun `unescapePartialJsonString backslash`() {
        assertEquals("a\\b", unescapePartialJsonString("a\\\\b"))
    }

    @Test fun `unescapePartialJsonString multiple escapes`() {
        assertEquals("a\nb\tc\"d/e\\f", unescapePartialJsonString("a\\nb\\tc\\\"d\\/e\\\\f"))
    }

    @Test fun `unescapePartialJsonString no escapes returns same`() {
        assertEquals("hello", unescapePartialJsonString("hello"))
    }

    @Test fun `unescapePartialJsonString empty string`() {
        assertEquals("", unescapePartialJsonString(""))
    }

    // ── stripSystemReminders ───────────────────────────────────────────────

    @Test fun `stripSystemReminders no reminder returns same`() {
        assertEquals("Hello world", stripSystemReminders("Hello world"))
    }

    @Test fun `stripSystemReminders reminder block removed`() {
        val result = stripSystemReminders("before<system-reminder>some reminder</system-reminder>after")
        assertEquals("beforeafter", result)
    }

    @Test fun `stripSystemReminders surrounding whitespace also removed`() {
        val result = stripSystemReminders("before <system-reminder>info</system-reminder> after")
        assertEquals("beforeafter", result)
    }

    @Test fun `stripSystemReminders multi-line reminder`() {
        val input = "prefix\n<system-reminder>\nline1\nline2\n</system-reminder>\nsuffix"
        assertEquals("prefixsuffix", stripSystemReminders(input))
    }

    @Test fun `stripSystemReminders empty string returns empty`() {
        assertEquals("", stripSystemReminders(""))
    }

    @Test fun `stripSystemReminders only reminder returns empty`() {
        assertEquals("", stripSystemReminders("<system-reminder>just a reminder</system-reminder>"))
    }

    @Test fun `stripSystemReminders no closing tag returns text as-is`() {
        val input = "before<system-reminder>no closing tag"
        assertEquals(input, stripSystemReminders(input))
    }

    // ── stripAttachedFilesXml ──────────────────────────────────────────────

    @Test fun `stripAttachedFilesXml no tag returns same`() {
        assertEquals("Hello world", stripAttachedFilesXml("Hello world"))
    }

    @Test fun `stripAttachedFilesXml tag removed`() {
        val result = stripAttachedFilesXml("before<user-attached-files><file>a.txt</file></user-attached-files>after")
        assertEquals("beforeafter", result)
    }

    @Test fun `stripAttachedFilesXml empty string`() {
        assertEquals("", stripAttachedFilesXml(""))
    }

    @Test fun `stripAttachedFilesXml only tag returns empty`() {
        assertEquals("", stripAttachedFilesXml("<user-attached-files>stuff</user-attached-files>"))
    }

    @Test fun `stripAttachedFilesXml no closing tag strips from start`() {
        assertEquals("", stripAttachedFilesXml("<user-attached-files>unclosed after"))
    }

    @Test fun `stripAttachedFilesXml multi-line content`() {
        val input = "prefix\n<user-attached-files>\nfile1.txt\nfile2.txt\n</user-attached-files>\nsuffix"
        assertEquals("prefix\n\nsuffix", stripAttachedFilesXml(input))
    }
}