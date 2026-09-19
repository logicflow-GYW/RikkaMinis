package com.rikkaminis.app.data.repository

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F-226] The `parts_json` cap must be measured in BYTES, not chars.
 *
 * CursorWindow's ceiling is a byte ceiling, and `parts_json` is persisted
 * UTF-8. The old `partsJson.length > 500_000` comparison let CJK / emoji
 * payloads reach 3-4x the intended size — exactly the SQLiteBlobTooBigException
 * the guard exists to prevent. These tests pin the byte semantics and the
 * surrogate-pair safety of the truncation.
 */
class MessagePartsByteCapTest {

    private val cap = ChatRepository.MAX_MESSAGE_PARTS_JSON_BYTES

    @Test
    fun `cap constant is a byte budget of 500 KB`() {
        assertEquals(500_000, cap)
    }

    @Test
    fun `cjk payload is rejected by the byte budget even though chars fit`() {
        // 400_000 CJK chars: under the old 500_000 CHAR limit, but 1.2 MB UTF-8.
        val cjk = "中".repeat(400_000)
        assertEquals(400_000, cjk.length)
        assertTrue("chars alone would have passed the old guard", cjk.length < cap)
        assertTrue("bytes must exceed the cap", cjk.toByteArray(Charsets.UTF_8).size > cap)
    }

    @Test
    fun `truncated parts json honours the byte budget`() {
        val huge = "中".repeat(400_000)
        val out = ChatRepository.buildTruncatedPartsJson(huge)
        assertTrue(
            "final payload must fit the byte budget, was ${out.toByteArray(Charsets.UTF_8).size}",
            out.toByteArray(Charsets.UTF_8).size <= cap,
        )
    }

    @Test
    fun `truncated parts json stays parseable and reports the original length`() {
        val huge = "x".repeat(cap * 3)
        val out = ChatRepository.buildTruncatedPartsJson(huge)
        val arr = JSONArray(out)
        assertEquals(1, arr.length())
        val value = arr.getJSONObject(0).getString("value")
        assertTrue("marker present", value.contains("[Content truncated at"))
        assertTrue("original length reported", value.contains("${huge.length} chars"))
    }

    @Test
    fun `takeByUtf8Bytes never splits a surrogate pair`() {
        // Each emoji is 2 UTF-16 units / 4 UTF-8 bytes.
        val emoji = "\uD83D\uDE00".repeat(100) // 100 x 😀
        val taken = ChatRepository.takeByUtf8Bytes(emoji, 401)
        // 401 bytes does not divide evenly by 4 -> must take 100 (400 bytes), not 100.25.
        assertEquals(400, taken.toByteArray(Charsets.UTF_8).size)
        assertEquals(200, taken.length)
        assertFalse("no lone surrogate left behind", taken.last().isHighSurrogate())
    }

    @Test
    fun `takeByUtf8Bytes returns whole string when it already fits`() {
        val s = "hello world"
        assertEquals(s, ChatRepository.takeByUtf8Bytes(s, 1000))
    }

    @Test
    fun `takeByUtf8Bytes handles empty and zero budget`() {
        assertEquals("", ChatRepository.takeByUtf8Bytes("", 100))
        assertEquals("", ChatRepository.takeByUtf8Bytes("abc", 0))
        assertEquals("", ChatRepository.takeByUtf8Bytes("abc", -5))
    }

    @Test
    fun `mixed ascii and cjk respects the byte budget`() {
        val mixed = "abc中".repeat(50_000) // 200_000 chars, 400_000 bytes
        val taken = ChatRepository.takeByUtf8Bytes(mixed, cap)
        assertTrue(taken.toByteArray(Charsets.UTF_8).size <= cap)
        assertTrue("should keep a substantial prefix", taken.length > 150_000)
    }

    @Test
    fun `reasoning truncation also honours the byte budget`() {
        val huge = "中".repeat(400_000)
        val out = ChatRepository.buildTruncatedText(huge)
        assertTrue(
            "reasoning payload must fit the byte budget, was ${out.toByteArray(Charsets.UTF_8).size}",
            out.toByteArray(Charsets.UTF_8).size <= cap,
        )
        assertTrue(out.contains("chars]"))
    }
}
