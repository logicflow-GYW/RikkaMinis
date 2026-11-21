package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.db.ChatDao
import com.rikkaminis.app.data.db.MessageEntity
import com.rikkaminis.app.debug.HeadlessChatRunner
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [D05 public fallback] The three remaining public read faces plus the row
 * ceiling's truncation source.
 *
 * Everything here is synthetic: no user rows, no credentials, no upstream.
 * The three faces are the REAL production functions (the preview / offload
 * paths run through the real `ChatRepository` against a proxy DAO, and the
 * RPC face is the real `HeadlessChatRunner.extractText` reached by reflection
 * exactly as the debug server calls it).
 */
class ReplayPublicFallbackTest {
    private companion object {
        const val SIGNATURE = "SynthSigD05"
        const val DATA = "SynthDataD05"
        const val PRIVATE_PART =
            """{"type":"anthropicReplayV1","value":{"signature":"$SIGNATURE","data":"$DATA"}}"""

        // A near-name future discriminator: the shared projection keeps it
        // (only the exact reserved name is private), so it is the witness for
        // "public content is not dropped for merely mentioning the marker".
        const val PUBLIC_FIELDS = """{"type":"anthropicReplayV10","value":{"z":0.1234567890123456789,"a":2}}"""
    }

    /** Minimal ChatDao: only the calls the four surfaces actually make. */
    private class Rows {
        val previews = mutableListOf<String?>()
        val writes = mutableListOf<String>()
        var parts = ""
        private val dao = Proxy.newProxyInstance(
            ChatDao::class.java.classLoader,
            arrayOf(ChatDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "updateLastMessage" -> previews.add(args!![1] as String?).let { Unit }
                "loadMessagesPage" -> listOf(
                    MessageEntity(
                        id = "m", sessionId = "s", role = "assistant", partsJson = parts,
                        createdAt = 1L, sortOrder = 0,
                    ),
                )
                "updateMessageParts" -> writes.add(args!![1] as String).let { Unit }
                "sessionIdOf" -> null // not the session tail; observe the persisted rewrite only
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as ChatDao
        val repo = ChatRepository(dao)

        suspend fun preview(raw: String): String? {
            val before = previews.size
            repo.updateSessionPreview("s", raw)
            return previews.drop(before).singleOrNull()
        }

        suspend fun offload(raw: String): String? {
            parts = raw
            return repo.loadMessagePage("s", 0, 1, maxChars = 1_100_000).singleOrNull()?.text
        }

        suspend fun rewrite(raw: String): String {
            repo.updateMessageParts("m", raw)
            return writes.last()
        }
    }

    // The RPC face is the real object, reached the way the debug server does.
    private val extractRpc = HeadlessChatRunner::class.java
        .getDeclaredMethod("extractText", String::class.java)
        .apply { isAccessible = true }

    private fun headless(raw: String): String? = extractRpc.invoke(HeadlessChatRunner, raw) as String?

    private val malformedPrivate = listOf(
        "[$PRIVATE_PART", // complete private object, missing the array close
        """[{"\u0074ype":"anthropicReplayV\u0031","value":"$DATA"""", // escaped discriminator, no closes
    )

    private fun assertNoPrivate(surface: String, value: String?) {
        assertFalse("$surface returned the synthetic signature", value.orEmpty().contains(SIGNATURE))
        assertFalse("$surface returned the synthetic data", value.orEmpty().contains(DATA))
    }

    @Test
    fun previewNeverReplacesAGoodPreviewWithPrivateText() = runBlocking {
        val rows = Rows()
        assertEquals("previous", rows.preview("previous"))
        for (raw in malformedPrivate) {
            val preview = rows.preview(raw)
            assertNoPrivate("preview", preview)
            assertNull("no preview write for a row nothing public survives", preview)
        }
        assertEquals(listOf("previous", "previous"), rows.previews)
    }

    @Test
    fun offloadPageDropsMalformedPrivateRows() = runBlocking {
        val rows = Rows()
        for (raw in malformedPrivate) {
            val text = rows.offload(raw)
            assertNoPrivate("offload page", text)
            assertNull("a row with nothing public left is dropped, not echoed", text)
        }
    }

    @Test
    fun headlessResponseTextNeverCarriesMalformedPrivateRows() {
        for (raw in malformedPrivate) {
            val text = headless(raw)
            assertNoPrivate("Headless response", text)
            assertNull(text)
        }
    }

    @Test
    fun previewIterationFailureFallsBackToTheProjectedJson() = runBlocking {
        // Parses, but the leading number makes getJSONObject(0) throw in the
        // preview reader: the legacy fallback must be the projected spelling.
        val text = Rows().preview("[0,$PRIVATE_PART,$PUBLIC_FIELDS]")
        assertNoPrivate("preview iteration fallback", text)
        assertTrue(text!!.startsWith("[0,"))
        assertTrue(text.contains("\"anthropicReplayV10\""))
        assertTrue("numeric literal is not rewritten", text.contains("0.1234567890123456789"))
        assertTrue("field order is preserved", text.indexOf("\"z\"") < text.indexOf("\"a\""))
    }

    @Test
    fun publicTextSurvivesWithThePrivatePartAtAnyPosition() = runBlocking {
        val rows = Rows()
        val quote = """中😀 引用 {"type":"anthropicReplayV1"}"""
        val public = listOf(
            JSONObject().put("type", "text").put("value", quote).toString(),
            JSONObject().put("type", "text").put("value", "尾").toString(),
        )
        for (index in 0..public.size) {
            val raw = public.toMutableList().apply { add(index, PRIVATE_PART) }.joinToString(",", "[", "]")
            assertEquals(quote, rows.preview(raw))
            assertEquals("$quote\n尾", rows.offload(raw))
            assertEquals("${quote}尾", headless(raw))
        }
    }

    @Test
    fun legacyBadRowsAndBracketProseKeepTheirReadableFallback() = runBlocking {
        val rows = Rows()
        val legacyRows = listOf(
            "old line [ missing close",
            """[{"type":"toolUse","value":{"input":{"type":"anthropicReplayV1"}}}""",
            """{"type":"anthropicReplayV10","value":"not a parts array"}""",
            "Notes: " + "[".repeat(130) + " literal brackets and anthropicReplayV1",
        )
        for (raw in legacyRows) {
            assertEquals(raw.take(100), rows.preview(raw))
            assertEquals(raw, rows.offload(raw))
            assertEquals(raw, headless(raw))
        }
    }

    @Test
    fun mediaToolTitleAndUnknownPartKeepTheirOriginalPresentation() = runBlocking {
        val rows = Rows()
        // Production stores `input` as an escaped JSON string.
        val tool = JSONObject()
            .put("type", "toolUse")
            .put(
                "value",
                JSONObject()
                    .put("name", "file_read")
                    .put(
                        "input",
                        JSONObject()
                            .put("tool_title", "Public anthropicReplayV1 example")
                            .put("nested", JSONObject().put("type", "anthropicReplayV1"))
                            .toString(),
                    ),
            )
            .toString()
        val media = """{"type":"mediaRef","value":{"relativePath":"test/image.png"}}"""
        val result = """{"type":"toolResult","value":{"output":"synthetic result"}}"""
        assertEquals("Public anthropicReplayV1 example", rows.preview("[$tool,$PUBLIC_FIELDS]"))
        assertEquals("Public anthropicReplayV1 example", rows.offload("[$tool,$PUBLIC_FIELDS]"))
        assertEquals("[Image]", rows.preview("[$tool,$media]"))
        assertEquals("[Image]", rows.offload("[$tool,$media]"))
        assertEquals("[Tool result: synthetic result]", rows.offload("[$result]"))
        assertNull(rows.preview("[$PUBLIC_FIELDS]"))
        assertNull(rows.offload("[$PUBLIC_FIELDS]"))
        assertNull(headless("[$PUBLIC_FIELDS,$tool,$media,$result]"))
    }

    private fun cappedText(written: String, originalLength: Int): String {
        assertTrue("UTF-8 row ceiling", written.toByteArray(Charsets.UTF_8).size <= 500_000)
        val arr = JSONArray(written)
        assertEquals("the capped row is still a parts array", 1, arr.length())
        val part = arr.getJSONObject(0)
        assertEquals("text", part.getString("type"))
        val value = part.getString("value")
        assertNoPrivate("capped text", value)
        assertTrue(value.contains("[Content truncated at 500 KB"))
        assertTrue("original length stays the diagnostic", value.endsWith("original length $originalLength chars]"))
        assertEquals("valid UTF-8 round trip", value, value.toByteArray(Charsets.UTF_8).decodeToString())
        return value.substringBeforeLast("\n\n[")
    }

    @Test
    fun oversizedRewriteWrapsOnlyTheProjectedPublicParts() = runBlocking {
        val rows = Rows()
        val largePrivate = PRIVATE_PART.replace(DATA, DATA + "x".repeat(500_001))
        val public = listOf(
            PUBLIC_FIELDS,
            JSONObject().put("type", "text").put("value", "中😀").toString(),
        )
        for (index in 0..public.size) {
            val raw = public.toMutableList().apply { add(index, largePrivate) }.joinToString(",", "[", "]")
            val kept = cappedText(rows.rewrite(raw), raw.length)
            val parts = JSONArray(kept)
            assertEquals("only the public parts remain", public.size, parts.length())
            assertEquals("anthropicReplayV10", parts.getJSONObject(0).getString("type"))
            assertTrue("first public part keeps its fields", kept.indexOf("\"z\"") < kept.indexOf("\"a\""))
            assertEquals("中😀", parts.getJSONObject(1).getString("value"))
        }
    }

    @Test
    fun oversizedPrivateSourcesNeverTruncateFromTheRawRow() = runBlocking {
        val rows = Rows()
        val malformed = "[" + PRIVATE_PART.replace(DATA, DATA + "x".repeat(500_001))
        val overInspectionBudget = "[" + PRIVATE_PART.replace(DATA, DATA + "x".repeat(1_000_001)) + "]"
        for (raw in listOf(malformed, overInspectionBudget)) {
            assertEquals("nothing public certifiable ⇒ empty projection", "[]", cappedText(rows.rewrite(raw), raw.length))
        }
    }

    @Test
    fun rowCeilingIsExactlyFiveHundredThousandBytes() {
        fun row(totalBytes: Int): String {
            val head = "[{\"type\":\"text\",\"value\":\""
            val tail = "\"}]"
            val cjk = "中".repeat((totalBytes - head.length - tail.length) / 3)
            val used = head.length + cjk.length * 3 + tail.length
            return head + cjk + "x".repeat(totalBytes - used) + tail
        }
        for (bytes in listOf(499_999, 500_000, 500_001)) {
            val raw = row(bytes)
            assertEquals(bytes, raw.toByteArray(Charsets.UTF_8).size)
            val capped = ChatRepository.capPartsJsonForRow(raw)
            if (bytes <= 500_000) {
                assertEquals("a row at or under the ceiling passes through untouched", raw, capped)
            } else {
                val kept = cappedText(capped, raw.length)
                assertTrue(kept.startsWith("[{\"type\":\"text\",\"value\":\"中"))
            }
        }
        val fittingPrivate = "[$PRIVATE_PART]"
        assertEquals(
            "a fitting row is not rewritten by the ceiling",
            fittingPrivate,
            ChatRepository.capPartsJsonForRow(fittingPrivate),
        )
    }

    @Test
    fun oversizedPlaintextKeepsItsReadablePrefixAndTheMarker() {
        val prefix = """Notes: literal {"type":"anthropicReplayV1"} 中😀 """
        val legacy = prefix + "x".repeat(600_000) + " [".repeat(130)
        val kept = cappedText(ChatRepository.capPartsJsonForRow(legacy), legacy.length)
        assertTrue(kept.startsWith(prefix))
        assertTrue(kept.length > 490_000)
    }
}
