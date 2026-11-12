package com.rikkaminis.app.provider.openai

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [da1017-01 / ISSUE-01] OpenAI-compatible providers used to drop every image
 * that reached the request through a *tool result* (`read_image`,
 * `browser_use` screenshots) — `grep -n imageData OpenAIProvider.kt` returned
 * 0 hits while AnthropicProvider `:789` and GeminiProvider `:431` both had the
 * branch. This pins the wire shape [OpenAIToolResultImages] now produces, plus
 * the two serializer call sites that feed it.
 *
 * Expected values are hand-written literals (never derived from the code under
 * test, see evidence-discipline): the base64 strings below were produced with
 * an independent encoder (`base64.b64encode`) and the placeholder literal is
 * re-stated from StreamTimeouts.kt rather than read back out of it.
 */
class OpenAIToolResultImagesTest {

    /** `byteArrayOf(1,2,3,4)` → `AQIDBA==` (independent encoder). */
    private val imageBytes = byteArrayOf(1, 2, 3, 4)
    private val imageB64 = "AQIDBA=="

    /** `byteArrayOf(9,9)` → `CQk=`. */
    private val recompressedBytes = byteArrayOf(9, 9)
    private val recompressedB64 = "CQk="

    /**
     * Same encoding the call sites pass: `android.util.Base64.NO_WRAP` is the
     * standard alphabet with padding and no line breaks, which is exactly what
     * the JDK's basic encoder emits. (The sibling `ImageData` branches already
     * ship `"data:$mime;base64,$b64"` strings built the same way.)
     */
    private val encode: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }
    private val noCompression: (ByteArray) -> ByteArray = { it }

    private fun entry(caption: String = "", bytes: ByteArray = imageBytes, mime: String = "image/png") =
        OpenAIToolResultImages.Entry(caption = caption, bytes = bytes, mimeType = mime)

    private fun partTypes(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.getJSONObject(it).getString("type") }

    // ── Chat Completions flavour ──────────────────────────────────────────

    @Test
    fun `chat flavour emits caption then image_url object`() {
        val out = OpenAIToolResultImages.chatUserContent(
            entries = listOf(entry(caption = "[tool result image: call_1]")),
            supportsImages = true,
            compress = noCompression,
            encodeBase64 = encode,
        )

        assertEquals(2, out.length())
        assertEquals("text", out.getJSONObject(0).getString("type"))
        assertEquals("[tool result image: call_1]", out.getJSONObject(0).getString("text"))
        assertEquals("image_url", out.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/png;base64,$imageB64",
            out.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `chat flavour emits one caption-image pair per entry in order`() {
        val out = OpenAIToolResultImages.chatUserContent(
            entries = listOf(
                entry(caption = "[tool result image: call_1]"),
                entry(caption = "[tool result image: call_2]", bytes = byteArrayOf(9, 9), mime = "image/jpeg"),
            ),
            supportsImages = true,
            compress = noCompression,
            encodeBase64 = encode,
        )

        assertEquals(listOf("text", "image_url", "text", "image_url"), partTypes(out))
        assertEquals("[tool result image: call_1]", out.getJSONObject(0).getString("text"))
        assertEquals("[tool result image: call_2]", out.getJSONObject(2).getString("text"))
        assertEquals(
            "data:image/jpeg;base64,$recompressedB64",
            out.getJSONObject(3).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `empty caption emits the image part alone`() {
        val out = OpenAIToolResultImages.chatUserContent(
            entries = listOf(entry(caption = "")),
            supportsImages = true,
            compress = noCompression,
            encodeBase64 = encode,
        )

        assertEquals(listOf("image_url"), partTypes(out))
    }

    @Test
    fun `recompressed bytes flip the declared mime to jpeg`() {
        val out = OpenAIToolResultImages.chatUserContent(
            entries = listOf(entry(caption = "", mime = "image/png")),
            supportsImages = true,
            compress = { recompressedBytes },
            encodeBase64 = encode,
        )

        assertEquals(
            "data:image/jpeg;base64,$recompressedB64",
            out.getJSONObject(0).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `non-vision model gets the shared placeholder instead of the bytes`() {
        val out = OpenAIToolResultImages.chatUserContent(
            entries = listOf(entry(caption = "[tool result image: call_1]")),
            supportsImages = false,
            compress = noCompression,
            encodeBase64 = encode,
        )

        assertEquals(listOf("text", "text"), partTypes(out))
        // [model-defaults-1112] Single source: the emitted text IS the production
        // constant (no literal copy here — the previous revision copied the old
        // wording into two assertions, so a wording change failed this test
        // instead of being caught where it belongs).
        val text = out.getJSONObject(1).getString("text")
        assertEquals(com.rikkaminis.app.provider.VISION_UNSUPPORTED_PLACEHOLDER, text)
        // The wording must name the cause (withheld / undeclared) and the remedy
        // (enable image input), so the model can relay an actionable message.
        assertTrue("cause missing from placeholder: $text", text.contains("withheld"))
        assertTrue("remedy missing from placeholder: $text", text.contains("image input"))
        assertFalse(out.toString().contains(imageB64))
    }

    @Test
    fun `no entries produce an empty array (the zero-regression lever)`() {
        val out = OpenAIToolResultImages.chatUserContent(
            entries = emptyList(),
            supportsImages = true,
            compress = noCompression,
            encodeBase64 = encode,
        )

        assertEquals(0, out.length())
    }

    // ── Responses flavour ─────────────────────────────────────────────────

    @Test
    fun `responses flavour uses input types and a flattened image_url string`() {
        val out = OpenAIToolResultImages.responsesUserContent(
            entries = listOf(entry(caption = "[tool result image: call_1]")),
            supportsImages = true,
            compress = noCompression,
            encodeBase64 = encode,
        )

        assertEquals(2, out.length())
        assertEquals("input_text", out.getJSONObject(0).getString("type"))
        assertEquals("input_image", out.getJSONObject(1).getString("type"))
        assertEquals("data:image/png;base64,$imageB64", out.getJSONObject(1).getString("image_url"))
        // Not the Chat flavour's nested {"url": …} object.
        assertTrue(out.getJSONObject(1).get("image_url") is String)
    }

    @Test
    fun `both flavours carry identical captions and byte strings`() {
        val entries = listOf(
            entry(caption = "[tool result image: call_1]"),
            entry(caption = "", bytes = byteArrayOf(9, 9), mime = "image/jpeg"),
        )
        val chat = OpenAIToolResultImages.chatUserContent(entries, true, noCompression, encode)
        val responses = OpenAIToolResultImages.responsesUserContent(entries, true, noCompression, encode)

        // Only the part-type vocabulary differs between the two flavours.
        assertEquals(listOf("text", "image_url", "image_url"), partTypes(chat))
        assertEquals(listOf("input_text", "input_image", "input_image"), partTypes(responses))
        assertEquals(3, chat.length())
        assertEquals(3, responses.length())
        assertEquals(chat.getJSONObject(0).getString("text"), responses.getJSONObject(0).getString("text"))
        val chatUrl = chat.getJSONObject(2).getJSONObject("image_url").getString("url")
        val responsesUrl = responses.getJSONObject(2).getString("image_url")
        assertEquals(chatUrl, responsesUrl)
        assertEquals("data:image/jpeg;base64,$recompressedB64", chatUrl)
        // The caption-less second entry yields one image part on each side.
        assertEquals("image_url", chat.getJSONObject(2).getString("type"))
        assertEquals("input_image", responses.getJSONObject(2).getString("type"))
    }

    // ── Production wiring (source scan) ───────────────────────────────────

    private val providerSource: String by lazy { readRepoFile(PROVIDER_PATH) }

    @Test
    fun `chat serializer wires tool-result images into a following user message`() {
        val call = "OpenAIToolResultImages.chatUserContent("
        assertEquals(1, countOf(providerSource, call))
        val window = providerSource.substringAfter(call).take(400)
        assertTrue(window, window.contains("entries = toolResultImages"))
        assertTrue(window, window.contains("supportsImages = supportsImages"))
        // The entries are built from ToolResult.imageData, with the tool id in
        // the caption so the model can pair the image to the wire call id.
        val buildWindow = providerSource.substringBefore(call).takeLast(900)
        assertTrue(buildWindow, buildWindow.contains("val bytes = tr.imageData"))
        assertTrue(buildWindow, buildWindow.contains("val mime = tr.imageMimeType"))
        assertTrue(buildWindow, buildWindow.contains("[tool result image: "))
    }

    @Test
    fun `responses serializer wires both tool-result images and top-level imageParts`() {
        val call = "OpenAIToolResultImages.responsesUserContent("
        assertEquals(2, countOf(providerSource, call))
        val firstWindow = providerSource.substringAfter(call).take(400)
        assertTrue(firstWindow, firstWindow.contains("entries = toolResultImages"))
        // [deep-audit-1047 A-03] The legacy-media branch now feeds the serializer
        // ONE list that carries the request-level images AND the message's own
        // image parts (the two used to be exclusive branches, and the per-message
        // carrier was dropped entirely). The assertions below name the DERIVED
        // list, not the old spelling: the entries come from `legacyImages`, and
        // that list is built from the request-level list plus `msg.imageParts`.
        val secondWindow = providerSource.substringAfterLast(call).take(400)
        assertTrue(secondWindow, secondWindow.contains("entries = legacyImages.map"))
        assertTrue(
            providerSource,
            providerSource.contains("(if (attachTopLevelImages) imageParts else emptyList()) + msg.imageParts"),
        )
    }

    @Test
    fun `responses builder takes imageParts and the stream entry passes them through`() {
        assertTrue(
            providerSource.contains("imageParts: List<LLMMessage.ImagePart> = emptyList(),"),
        )
        assertTrue(
            providerSource.contains(
                "buildResponsesAPIBody(messages, systemPrompt, maxTokens, stream = true, temperature = temperature, tools = tools, thinkingLevel = thinkingLevel, imageParts = imageParts)",
            ),
        )
    }

    @Test
    fun `image bytes are never logged`() {
        val objectBody = providerSource.substringAfter("internal object OpenAIToolResultImages {")
        assertFalse(objectBody, objectBody.contains("Log."))
        assertFalse(objectBody, objectBody.contains("println("))
        assertFalse(objectBody, objectBody.contains("AppLogger"))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun countOf(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count += 1
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    private fun readRepoFile(relative: String): String {
        // `user.dir` is nullable on the JVM — the `?: "."` keeps the compiler
        // warning-free (CI reads "本支文件编译警告 0" as a gate).
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private companion object {
        const val PROVIDER_PATH =
            "src/android/app/src/main/java/com/rikkaminis/app/provider/openai/OpenAIProvider.kt"
    }
}
