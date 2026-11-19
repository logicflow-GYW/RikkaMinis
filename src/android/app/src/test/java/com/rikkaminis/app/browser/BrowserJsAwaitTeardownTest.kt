package com.rikkaminis.app.browser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [FIX-DEFERRED-1119 F-05] `evaluateJavascript`'s per-call deferred has no slot
 * to be taken out of, so a renderer killed *while* the call waits used to leave
 * it waiting forever (`BrowserUseManager` is the only waiter `onRendererGone`
 * could not reach; the async-JS bridge request next to it was already covered by
 * `AsyncJsSlot`).
 *
 * The fix is a terminal event — `tabDeathSignal`, completed by `onRendererGone`
 * and `destroy()` — raced against the result. What must hold:
 *
 *  - the wait **terminates** on the death event (the pre-fix shape hangs, so
 *    these tests would time out rather than assert);
 *  - the answer is the bridge's own `{"error":…}` shape, built from the latch
 *    that teardown set at wake-up time;
 *  - a delivered result still wins, and a tie between the two prefers the
 *    result;
 *  - the caller's own cancellation still propagates.
 *
 * Behavioural arms run against the production helper
 * (`awaitJsResultOrTabDeath`); the wiring arms read the real source, because a
 * helper that nothing calls would not fix anything.
 */
class BrowserJsAwaitTeardownTest {

    // ── behaviour ────────────────────────────────────────────────────────

    @Test
    fun `a death during the wait answers promptly with the dead-tab error`() {
        val result = CompletableDeferred<String>()
        val death = CompletableDeferred<Unit>()
        val started = System.nanoTime()
        val answer = runBounded {
            // Death arrives AFTER the wait began — the exact window the
            // pre-fix code had no wake-up for.
            launch {
                delay(30)
                death.complete(Unit)
            }
            awaitJsResultOrTabDeath(result, death) { DEAD_ANSWER }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(DEAD_ANSWER, answer)
        assertTrue("the wait must end on the death event, took ${elapsedMs}ms", elapsedMs < 2_000)
        assertTrue("the result must never be forged", !result.isCompleted)
    }

    @Test
    fun `a death event that already fired answers without waiting at all`() {
        val result = CompletableDeferred<String>()
        val death = CompletableDeferred<Unit>()
        death.complete(Unit)
        val started = System.nanoTime()
        val answer = runBounded { awaitJsResultOrTabDeath(result, death) { DEAD_ANSWER } }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(DEAD_ANSWER, answer)
        assertTrue("an already-dead tab must not cost a wait, took ${elapsedMs}ms", elapsedMs < 1_000)
    }

    @Test
    fun `a delivered result is returned unchanged and the wait does not depend on death`() {
        val result = CompletableDeferred<String>()
        val death = CompletableDeferred<Unit>()
        val answer = runBounded {
            launch {
                delay(10)
                result.complete("\"page title\"")
            }
            awaitJsResultOrTabDeath(result, death) { DEAD_ANSWER }
        }
        assertEquals("\"page title\"", answer)
        assertFalse("the death event must stay un-fired", death.isCompleted)
    }

    @Test
    fun `a result already in hand wins a tie against an already-fired death`() {
        val result = CompletableDeferred<String>()
        val death = CompletableDeferred<Unit>()
        result.complete("42")
        death.complete(Unit)
        val answer = runBounded { awaitJsResultOrTabDeath(result, death) { DEAD_ANSWER } }
        assertEquals("42", answer)
    }

    @Test
    fun `the death answer is computed at wake-up time, not captured up front`() {
        val result = CompletableDeferred<String>()
        val death = CompletableDeferred<Unit>()
        var destroyed = false
        var rendererDead = false
        val answer = runBounded {
            launch {
                delay(20)
                // Teardown order: latch first, signal second.
                rendererDead = true
                death.complete(Unit)
            }
            awaitJsResultOrTabDeath(result, death) { deadTabAsyncJsAnswer(destroyed, rendererDead)!! }
        }
        val json = JSONObject(answer)
        assertTrue("the answer must be the bridge's error shape: $answer", json.has("error"))
        assertEquals(
            "This tab's WebView renderer was killed by the system; " +
                "this tab can no longer load pages — open a new tab.",
            json.getString("error"),
        )
    }

    @Test
    fun `the caller's own cancellation propagates instead of being swallowed`() {
        val result = CompletableDeferred<String>()
        val death = CompletableDeferred<Unit>()
        try {
            runBlocking {
                withTimeout(50) {
                    awaitJsResultOrTabDeath(result, death) { DEAD_ANSWER }
                }
            }
            fail("a cancelled wait must not answer")
        } catch (_: TimeoutCancellationException) {
            // expected
        }
    }

    // ── wiring (source shape) ────────────────────────────────────────────

    @Test
    fun `evaluateJavascript races its deferred against the tab death signal`() {
        val body = memberBody(codeOnly(readRepoFile(ManagerPath)), "private suspend fun evaluateJavascript(")
        assertTrue(
            "evaluateJavascript must await through the death race:\n$body",
            body.contains("awaitJsResultOrTabDeath(deferred, tabDeathSignal)"),
        )
        assertFalse(
            "a bare deferred.await() has no wake-up (the pre-fix shape):\n$body",
            body.contains("deferred.await()"),
        )
        // Anchored INSIDE the race: `deadTabAsyncJsAnswer(destroyed, rendererDead)!!`
        // also appears in the entry guard above, so a bare "does this file
        // contain it" check would stay green while the call site answered with
        // a constant.
        val raceAt = body.indexOf("awaitJsResultOrTabDeath(deferred, tabDeathSignal)")
        val afterRace = if (raceAt < 0) "" else body.substring(raceAt, minOf(body.length, raceAt + 200))
        assertTrue(
            "the death answer must be derived from the latches, at the race site:\n$afterRace",
            afterRace.contains("deadTabAsyncJsAnswer(destroyed, rendererDead)!!"),
        )
        assertTrue(
            "the entry guard must stay — it answers without a round trip:\n$body",
            body.contains("if (webViewInert())"),
        )
    }

    @Test
    fun `both teardown paths complete the signal after their latch`() {
        val src = codeOnly(readRepoFile(ManagerPath))
        val rendererGone = memberBody(src, "override fun onRendererGone(")
        val latchAt = rendererGone.indexOf("rendererDead = true")
        val signalAt = rendererGone.indexOf("tabDeathSignal.complete(Unit)")
        assertTrue("onRendererGone must arm the latch:\n$rendererGone", latchAt >= 0)
        assertTrue(
            "onRendererGone must complete the terminal signal:\n$rendererGone",
            signalAt >= 0,
        )
        assertTrue(
            "the latch must be set BEFORE the signal — the death answer reads it",
            latchAt < signalAt,
        )
        val destroy = memberBody(src, "fun destroy()")
        assertTrue(
            "destroy() must complete the terminal signal:\n$destroy",
            destroy.contains("tabDeathSignal.complete(Unit)"),
        )
        assertTrue(
            "the signal must be declared once, in the manager:\n$src",
            src.contains("private val tabDeathSignal = CompletableDeferred<Unit>()"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Bound every wait: a regression must fail as a timeout, not hang the run. */
    private fun <T> runBounded(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T =
        try {
            runBlocking { withTimeout(3_000) { block() } }
        } catch (e: TimeoutCancellationException) {
            fail("the wait never returned — the death event does not wake it")
            throw e
        }

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

    /** Blank comment lines: a claim made only in KDoc must not satisfy a probe. */
    private fun codeOnly(text: String): String = stripLiteralsAndComments(text)

    /**
     * Blank string/char literals and comments, preserving offsets.
     *
     * Slicing on the raw text would let a `{` inside a literal (this file's own
     * `JSONObject("{\"v\":$result}")` is one) move a brace counter — the body
     * would be cut short and the arm would read the wrong text.
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

    /** Body of `header … { … }` (braces matched on an already-stripped view). */
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
        const val ManagerPath =
            "src/android/app/src/main/java/com/rikkaminis/app/browser/BrowserUseManager.kt"

        /** Literal on purpose: expectations must not be derived from the impl. */
        const val DEAD_ANSWER =
            "{\"error\":\"This tab's WebView renderer was killed by the system; " +
                "this tab can no longer load pages — open a new tab.\"}"
    }
}
