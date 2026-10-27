package com.rikkaminis.app.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1044 B-01] The quick test EXECUTES the model it advertised.
 *
 * `applicableKinds` resolves through `ModelsDevApi.effectiveModel` — it has to:
 * that is how the image / speech / transcription cards appear at all (the stored
 * built-in entries declare no modality). The three execution calls then handed
 * the worker `entry.model` — the stored, un-enriched entry.
 *
 * Across the `:modelservice` boundary `ModelRequestJson.encodeModel` OMITS a
 * null `outputModalities`, and that process never calls `ModelsDevApi.init`, so
 * the worker cannot enrich it back. Consequences: Gemini image generation lost
 * `responseModalities: ["TEXT","IMAGE"]` ("No image returned"), and the
 * OpenAI-compatible providers were routed to `/v1/chat/completions` instead of
 * `/v1/images/generations` (HTTP 400/404).
 *
 * The card is a Composable with a Context-bound gateway, so the request itself
 * is not JVM-pinnable. This is a source-shape probe over `performTest`: the
 * three execution arguments must carry the enriched model, while the two
 * STRING-id arguments (`entry.model.id` / `entry.baseModel.id` — the voice
 * adapter takes the entry as its model / voice name) must stay untouched.
 */
class QuickTestEffectiveModelTest {

    private val quickTestRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/components/QuickTestSheet.kt"

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("$rel not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun read(rel: String): String = repoFile(rel).readText()

    /**
     * Drops `//` and block comments so doc text cannot satisfy an arm.
     *
     * STRING-AWARE on purpose: ChatScreen.kt contains a wildcard mime literal
     * whose text is a slash followed by an asterisk — inside a string literal.
     * A naive stripper treats it as a comment opener and eats the rest of the
     * file (until the next comment terminator), which silently blanks every
     * later line and turns a source probe into a green nothing. The string is
     * kept whole here, and a comment marker inside it never starts a comment.
     */
    private fun codeOnly(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"' && text.startsWith("\"\"\"", i)) {
                val end = text.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) text.length else end + 3
                sb.append(text, i, stop)
                i = stop
            } else if (c == '"') {
                var j = i + 1
                while (j < text.length && text[j] != '"') {
                    if (text[j] == '\\') j++
                    j++
                }
                val stop = if (j < text.length) j + 1 else text.length
                sb.append(text, i, stop)
                i = stop
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n') i++
            } else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                val end = text.indexOf("*/", i)
                i = if (end < 0) text.length else end + 2
                sb.append(' ')
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun count(haystack: String, needle: String): Int {
        var n = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            n++
            i = haystack.indexOf(needle, i + 1)
        }
        return n
    }

    /** The body of `private suspend fun performTest(…)` — the one under test. */
    private fun performTestBody(code: String): String {
        val at = code.indexOf("fun performTest(")
        assertTrue("performTest not found", at >= 0)
        val end = code.indexOf("\nprivate suspend fun synthesizeTestClip(", at)
        val stop = if (end < 0) code.length else end
        return code.substring(at, stop)
    }

    @Test
    fun `the execution resolves the effective model`() {
        val body = performTestBody(codeOnly(read(quickTestRel)))
        assertTrue(
            "performTest must resolve ModelsDevApi.effectiveModel(entry)",
            body.contains("ModelsDevApi.effectiveModel(entry)"),
        )
    }

    @Test
    fun `no execution argument carries the raw entry`() {
        val body = performTestBody(codeOnly(read(quickTestRel)))
        assertEquals(
            "the worker must never receive the un-enriched entry — it cannot enrich it " +
                "back (no ModelsDevApi.init in :modelservice)",
            0,
            count(body, "resolvedModel = entry.model"),
        )
        assertEquals(
            "the worker must never receive the un-enriched entry as the model carrier",
            0,
            count(body, "= entry.model,"),
        )
        assertEquals(
            "the worker must never receive the un-enriched base entry as the model carrier",
            0,
            count(body, "= entry.baseModel,"),
        )
    }

    @Test
    fun `the three execution sites carry the enriched model`() {
        val body = performTestBody(codeOnly(read(quickTestRel)))
        assertEquals(
            "the transcription, text and image requests all carry the enriched model",
            3,
            count(body, "= effectiveModel"),
        )
    }

    @Test
    fun `the voice string ids are untouched`() {
        // The voice adapter takes the entry id AS the model / voice name; that
        // is an id, not the capability carrier, and must not be enriched.
        val body = performTestBody(codeOnly(read(quickTestRel)))
        assertTrue(
            "the speech-out request must keep passing the entry id as the voice",
            body.contains("voice = entry.model.id"),
        )
        assertTrue(
            "the transcription request must keep passing the entry id as the model name",
            body.contains("model = entry.baseModel.id"),
        )
    }
}
