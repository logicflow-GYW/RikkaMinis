package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [fix/r3-data-1119 F-14] `RenderMdAudio` (the main-chat audio card) must
 * carry the same MediaPlayer lifecycle contract its twin `MinisAudioBlock`
 * got in FIX-R3-C F-12.
 *
 * F-12 fixed the markdown renderer and missed this one, so the main chat kept
 * both halves of the defect:
 *  1. `prepare()` ran on Dispatchers.IO while `release()` ran on the main
 *     thread the moment the block left composition. Coroutine cancellation
 *     cannot interrupt a thread already inside native `prepare()`, so the
 *     same native handle was prepared and released concurrently.
 *  2. The click gate was `prepared` only, while its own tap handler had an
 *     `!prepared` branch that hands the file to an external player — so the
 *     files MediaPlayer cannot decode (exactly the ones needing the
 *     fallback) disabled their own fallback.
 *
 * Composable code cannot be executed on a plain JVM, so this is a source-shape
 * test over the function body: comment-stripped, body-scoped (a match in
 * MinisAudioBlock next door must not satisfy it), and anchored on the
 * DECISIONS rather than on formatting.
 */
class StreamingMarkdownAudioHostTest {

    private fun sourceFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(
                dir,
                "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/StreamingMarkdownText.kt",
            )
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("StreamingMarkdownText.kt not found from ${System.getProperty("user.dir")}")
    }

    /** Blank out `//` and block comments; string literals are preserved. */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var state: String? = null
        while (i < text.length) {
            val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
            when (state) {
                null -> when {
                    two == "//" -> { state = "line"; i += 2 }
                    two == "/*" -> { state = "block"; i += 2 }
                    // A string literal can CONTAIN "//" or "/*" (this file has
                    // `"audio/*"`); without this branch that text opened a
                    // "comment" and ate the rest of the file.
                    text[i] == '"' -> { state = "str"; out.append('"'); i += 1 }
                    else -> { out.append(text[i]); i += 1 }
                }
                "line" -> {
                    if (text[i] == '\n') { state = null; out.append('\n') }
                    i += 1
                }
                "block" -> if (two == "*/") { state = null; i += 2 } else i += 1
                "str" -> when {
                    text[i] == '\\' && i + 1 < text.length -> {
                        out.append(text[i]).append(text[i + 1]); i += 2
                    }
                    text[i] == '"' -> { state = null; out.append('"'); i += 1 }
                    else -> { out.append(text[i]); i += 1 }
                }
                else -> i += 1
            }
        }
        return out.toString()
    }

    /**
     * Same-length copy with string literals blanked out (quotes kept). Used
     * ONLY for brace matching: a `"${...}"` template or a `"{"` literal would
     * otherwise unbalance the count, while the assertions below still need the
     * real string contents. Because the copy has identical length and line
     * breaks, an index found here is valid in the original.
     */
    private fun blankStrings(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inStr = false
        while (i < text.length) {
            val c = text[i]
            when {
                !inStr && c == '"' -> { inStr = true; out.append('"') }
                inStr && c == '\\' && i + 1 < text.length -> {
                    out.append(' ').append(if (text[i + 1] == '\n') '\n' else ' ')
                    i += 1
                }
                inStr && c == '"' -> { inStr = false; out.append('"') }
                inStr -> out.append(if (c == '\n') '\n' else ' ')
                else -> out.append(c)
            }
            i += 1
        }
        return out.toString()
    }

    private fun bodyOf(source: String, signature: String): String {
        val at = source.indexOf(signature)
        assertTrue("`$signature` not found", at >= 0)
        // Brace matching on the string-blanked copy; slicing on the original.
        val shape = blankStrings(source)
        val open = shape.indexOf('{', at)
        assertTrue("`$signature` has no body brace", open > 0)
        var depth = 0
        var i = open
        while (i < shape.length) {
            when (shape[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("`$signature` body is unbalanced")
    }

    private fun renderMdAudioBody(): String =
        bodyOf(stripComments(sourceFile().readText()), "private fun RenderMdAudio(block: MdBlock.Audio)")

    @Test
    fun `prepare and release go through one serial worker`() {
        val body = renderMdAudioBody()

        assertTrue(
            "RenderMdAudio must build a SerialMediaOps for its player",
            body.contains("SerialMediaOps()"),
        )
        assertTrue(
            "prepare must run inside runPreparing (the serial worker)",
            body.contains("runPreparing {"),
        )
        assertTrue(
            "release must be QUEUED behind prepare, not called inline on main",
            body.contains("enqueue { p.release() }"),
        )
        assertTrue(
            "the worker must be shut down when the block leaves composition",
            body.contains("ops.shutdown()"),
        )
        assertFalse(
            "release() must not be called inline from onDispose any more",
            body.contains("onDispose { try { player?.release() } catch (_: Throwable) {} }"),
        )
    }

    @Test
    fun `a failed prepare still lets the card open an external player`() {
        val body = renderMdAudioBody()

        assertTrue(
            "the card must track prepareFailed",
            body.contains("var prepareFailed by remember"),
        )
        assertTrue(
            "a failed prepare must be recorded next to the successful flag",
            body.contains("prepareFailed = !ok"),
        )
        assertTrue(
            "prepareFailed must be part of the click enable gate, or the " +
                "external-player fallback stays unreachable",
            body.contains("(player == null || prepared || prepareFailed)"),
        )
        assertTrue(
            "the fallback itself must still be there",
            body.contains("openMdMediaExternally(context, it, \"audio/*\")"),
        )
    }

    @Test
    fun `the audio host keeps its data-source keys`() {
        val body = renderMdAudioBody()

        // A regression guard for the dv19-B06 contract while this function is
        // being edited: playback state must still be keyed on the media source.
        for (state in listOf("prepared", "prepareFailed", "isPlaying", "positionMs")) {
            assertTrue(
                "`var $state` must stay keyed on resolvedMediaSourceKey(file)",
                body.contains("var $state by remember(resolvedMediaSourceKey(file))"),
            )
        }
    }
}
