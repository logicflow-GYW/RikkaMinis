package com.rikkaminis.app.ui.chat

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [FIX-DEFERRED-1119 F-21] `resume()` persisted its continuation reminder with
 * `runCatching { chatRepository.appendMessage(…) }`. `appendMessage` is a
 * suspending Room write, so a Stop (which cancels the stream job) makes it throw
 * `CancellationException` — and `runCatching` caught it as an ordinary failure.
 * The turn then kept executing the two statements the enclosing
 * `catch (CancellationException)` exists to skip: it appended a `user` turn to
 * the in-memory history (with `dbMessageId = null`, because the row may never
 * have landed) and re-armed `SessionActivityTracker` after the user had stopped
 * the run.
 *
 * Same family as the repairs already made in `ChatModelRouting`,
 * `AgentLoopEngine` and `ConversationHistoryTool`: the rule is that cancellation
 * is never a failure. Both arms are pinned — cancellation propagates *and* is
 * never handed to the failure reporter; a real `IOException` is still reported
 * and answered as null.
 */
class ChatResumePersistCancellationTest {

    // ── behaviour of the production helper ───────────────────────────────

    @Test
    fun `a cancellation is rethrown and never reported as a failure`() {
        val reported = mutableListOf<Exception>()
        try {
            runBlocking {
                attemptUnlessCancelled(onFailure = { reported.add(it) }) {
                    throw CancellationException("user stopped")
                }
            }
            fail("the cancellation must propagate out of the guarded step")
        } catch (e: CancellationException) {
            assertEquals("user stopped", e.message)
        }
        assertTrue("onFailure must not see a cancellation: $reported", reported.isEmpty())
    }

    @Test
    fun `an ordinary write failure answers null and is reported`() {
        val reported = mutableListOf<Exception>()
        val row = runBlocking {
            attemptUnlessCancelled(onFailure = { reported.add(it) }) {
                throw IOException("database is locked")
            }
        }
        assertNull("a failed persist must answer null (the pre-fix getOrNull shape)", row)
        assertEquals(1, reported.size)
        assertEquals("database is locked", reported[0].message)
    }

    @Test
    fun `a successful write is returned and nothing is reported`() {
        val reported = mutableListOf<Exception>()
        val row = runBlocking {
            attemptUnlessCancelled(onFailure = { reported.add(it) }) { "row-7" }
        }
        assertEquals("row-7", row)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `nothing after the guarded persist runs when the stop lands during it`() {
        // Models the resume block's statement order on top of the production
        // helper: `agentHistory.add` and `setActive` sit after the persist.
        val events = mutableListOf<String>()
        try {
            runBlocking {
                attemptUnlessCancelled(onFailure = { events.add("reported") }) {
                    events.add("persist")
                    throw CancellationException("stop")
                }
                events.add("history")
                events.add("setActive")
            }
            fail("the cancellation must unwind the resume body")
        } catch (_: CancellationException) {
            // expected
        }
        assertEquals(listOf("persist"), events)
    }

    // ── wiring (source shape) ────────────────────────────────────────────

    @Test
    fun `the resume reminder uses the non-swallowing helper before touching history`() {
        val src = codeOnly(readRepoFile(ViewModelPath))
        val block = memberBody(src, "if (agentHistory.lastOrNull()?.role == LLMMessage.Role.ASSISTANT)")
        assertTrue(
            "the reminder persist must go through attemptUnlessCancelled:\n$block",
            block.contains("attemptUnlessCancelled("),
        )
        assertTrue(
            "runCatching still swallows the cancellation:\n$block",
            !block.contains("runCatching"),
        )
        val guardedAt = block.indexOf("attemptUnlessCancelled(")
        val historyAt = block.indexOf("agentHistory.add(")
        assertTrue("the history append must still be there:\n$block", historyAt >= 0)
        assertTrue(
            "the history append must come AFTER the guarded persist (a cancelled " +
                "write may have no row, so the turn must not be recorded):\n$block",
            guardedAt < historyAt,
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private fun codeOnly(text: String): String = stripLiteralsAndComments(text)

    /**
     * Blank string/char literals and comments, preserving offsets: the block
     * under test carries a raw string (`"""[{"type":…}]"""`) whose braces would
     * otherwise move a brace counter and cut the slice short.
     */
    private fun stripLiteralsAndComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inRawString = false
        var inChar = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inLineComment -> {
                    out.append(if (c == '\n') '\n' else ' ')
                    if (c == '\n') inLineComment = false
                    i++
                }
                inBlockComment -> {
                    out.append(if (c == '\n') '\n' else ' ')
                    if (c == '*' && next == '/') {
                        out.append(' ')
                        i += 2
                        inBlockComment = false
                    } else {
                        i++
                    }
                }
                inRawString -> {
                    out.append(if (c == '\n') '\n' else ' ')
                    if (c == '"' && next == '"' && i + 2 < src.length && src[i + 2] == '"') {
                        out.append("  ")
                        i += 3
                        inRawString = false
                    } else {
                        i++
                    }
                }
                inChar -> {
                    out.append(if (c == '\n') '\n' else ' ')
                    when {
                        c == '\\' -> {
                            out.append(' ')
                            i += 2
                        }
                        c == '\'' -> {
                            i++
                            inChar = false
                        }
                        else -> i++
                    }
                }
                inString -> {
                    out.append(if (c == '\n') '\n' else ' ')
                    when {
                        c == '\\' -> {
                            out.append(' ')
                            i += 2
                        }
                        c == '"' -> {
                            i++
                            inString = false
                        }
                        else -> i++
                    }
                }
                c == '/' && next == '/' -> {
                    out.append("  ")
                    i += 2
                    inLineComment = true
                }
                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    inBlockComment = true
                }
                c == '"' && next == '"' && i + 2 < src.length && src[i + 2] == '"' -> {
                    out.append("   ")
                    i += 3
                    inRawString = true
                }
                c == '\'' -> {
                    out.append(' ')
                    i++
                    inChar = true
                }
                c == '"' -> {
                    out.append(' ')
                    i++
                    inString = true
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun memberBody(stripped: String, header: String): String {
        val start = stripped.indexOf(header)
        assertTrue("$header not found — probe anchor lost", start >= 0)
        val open = stripped.indexOf('{', start)
        assertTrue("$header has no body", open > start)
        var depth = 0
        var i = open
        while (i < stripped.length) {
            when (stripped[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripped.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces in $header")
    }

    private companion object {
        const val ViewModelPath =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"
    }
}
