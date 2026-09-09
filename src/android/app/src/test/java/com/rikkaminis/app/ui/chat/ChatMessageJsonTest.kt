package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.MediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure JSON/text serialization helpers extracted in FE-4
 * route B ([escapeJson] / [buildMediaRefPartJson] / [buildUserPartsJson] /
 * [parseTitleResponse]). Behavior mirrors the former inline logic in
 * ChatViewModel (fcf9470).
 */
class ChatMessageJsonTest {

    // ── buildMediaRefPartJson ──────────────────────────────────

    @Test
    fun `buildMediaRefPartJson without linuxPath`() {
        val ref = MediaRef(id = "i1", relativePath = "2024/01/01/s/i1.png", mimeType = "image/png")
        val json = buildMediaRefPartJson(ref)
        assertTrue(json.contains("\"type\":\"mediaRef\""))
        assertTrue(json.contains("\"id\":\"i1\""))
        assertTrue(json.contains("\"mimeType\":\"image/png\""))
        assertTrue(!json.contains("linuxPath"))            // omit nil
        assertTrue(!json.contains("originalFileName"))    // omit nil
    }

    @Test
    fun `buildMediaRefPartJson includes optional fields`() {
        val ref = MediaRef(
            id = "i1", relativePath = "r", mimeType = "image/png", originalFileName = "a.png",
        )
        val json = buildMediaRefPartJson(ref, linuxPath = "/var/minis/attachments/uploads/a.png")
        assertTrue(json.contains("\"originalFileName\":\"a.png\""))
        assertTrue(json.contains("\"linuxPath\":\"/var/minis/attachments/uploads/a.png\""))
    }

    // ── buildUserPartsJson ─────────────────────────────────────

    @Test
    fun `buildUserPartsJson with text and no media`() {
        val out = buildUserPartsJson("hi", emptyList())
        assertEquals("""[{"type":"text","value":"hi"}]""", out)
    }

    @Test
    fun `buildUserPartsJson empty text with media omits text part`() {
        val out = buildUserPartsJson("", listOf("""{"type":"mediaRef"}"""))
        assertEquals("""[{"type":"mediaRef"}]""", out)
    }

    @Test
    fun `buildUserPartsJson empty text no media still emits empty text part`() {
        val out = buildUserPartsJson("", emptyList())
        assertEquals("""[{"type":"text","value":""}]""", out)
    }

    @Test
    fun `buildUserPartsJson appends attachedFilesXml as trailing text part`() {
        val out = buildUserPartsJson("hi", emptyList(), attachedFilesXml = "<files></files>")
        assertTrue(out.contains("""{"type":"text","value":"hi"}"""))
        assertTrue(out.contains("""{"type":"text","value":"<files></files>"}"""))
    }

    @Test
    fun `buildUserPartsJson escapes text content`() {
        val out = buildUserPartsJson("a\"b", emptyList())
        assertEquals("""[{"type":"text","value":"a\"b"}]""", out)
    }

    // ── parseTitleResponse ─────────────────────────────────────

    @Test
    fun `parseTitleResponse parses plain JSON object`() {
        assertEquals("My Title" to "Coding", parseTitleResponse("""{"title":"My Title","category":"Coding"}"""))
    }

    @Test
    fun `parseTitleResponse falls back to regex when not valid json`() {
        assertEquals("My Title" to "Coding", parseTitleResponse("""{"title": "My Title", "category": "Coding"}"""))
    }

    @Test
    fun `parseTitleResponse regex fallback without category`() {
        val (title, category) = parseTitleResponse("""blah {"title": "Only"} blah""")
        assertEquals("Only", title)
        assertNull(category)
    }

    @Test
    fun `parseTitleResponse plain text fallback uses first line truncated`() {
        val long = "x".repeat(80)
        val (title, category) = parseTitleResponse(long)
        assertEquals("x".repeat(50), title)
        assertNull(category)
    }

    @Test
    fun `parseTitleResponse strips code fences`() {
        val (title, _) = parseTitleResponse("```json\n{\"title\":\"T\"}\n```")
        assertEquals("T", title)
    }
}
