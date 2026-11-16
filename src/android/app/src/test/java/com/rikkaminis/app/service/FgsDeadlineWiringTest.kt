package com.rikkaminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [FGS-DEADLINE-1116] Shape probes for the foreground-service deadline.
 *
 * Android requires `startForeground()` within 5 s of any
 * `startForegroundService()`; `stopSelf()` does not discharge it. On
 * 2026-11-16 10:10:16 the swipe-from-recents path returned straight out of
 * `onTaskRemoved` with a bare `stopSelf()` and the process died with
 * `ForegroundServiceDidNotStartInTimeException` — after four short-lived
 * starts in 40 seconds.
 *
 * `onTaskRemoved` is a Service lifecycle method, so there is no JVM way to
 * invoke it (no Robolectric in this module). The decisions are therefore
 * asserted against the compiled sources, in the same probe shape as
 * `AppInitSkipGuardWiringTest`, with every negative control mutating a copy of
 * the real text and re-running the very same assertion functions.
 */
class FgsDeadlineWiringTest {

    private val fgsFile = "src/main/java/com/rikkaminis/app/service/AgentForegroundService.kt"

    // ── the decisions, written out as literals ───────────────────────────────

    /** The one exit that satisfies the deadline before unwinding. */
    private val sharedExit = "satisfyForegroundDeadlineThenStop()"

    /** What the swipe-from-recents branch must NOT do on its own any more. */
    private val bareStop = "stopSelf()"

    /** The gate that keeps an already-foreground instance from re-attaching. */
    private val foregroundGate = "if (!foregroundStarted)"

    /** The stub row is built in exactly one place now. */
    private val inlineStub = ".setContentTitle(\"RikkaMinis\")"

    private val emptySessionGate = "if (SessionActivityTracker.activeSessions.value.isEmpty())"

    private val initSkipGuard = "if (isSafeMode() || isInitSkipped() || isDbGuidanceMode())"

    // ── assertions, reused by the negative controls ──────────────────────────

    /**
     * The empty-session branch of onTaskRemoved must unwind through the shared
     * exit and must not dispose of the service by itself.
     */
    private fun assertTaskRemovedUsesSharedExit(source: String) {
        val body = functionBody(source, "override fun onTaskRemoved")
        val branch = guardedBranch(body, emptySessionGate)
        assertTrue(
            "the no-active-session branch must go through $sharedExit:\n$branch",
            branch.contains(sharedExit),
        )
        assertFalse(
            "the no-active-session branch must not call $bareStop directly — that leaves the " +
                "startForegroundService() deadline unmet and kills the process:\n$branch",
            branch.contains(bareStop),
        )
        assertEquals(
            "the branch should reach the shared exit exactly once:\n$branch",
            1,
            occurrences(branch, sharedExit),
        )
    }

    /**
     * The shared exit itself must satisfy the deadline before it stops: the
     * stub is attached first and only then is the service retired.
     */
    private fun assertSharedExitAttachesForegroundFirst(source: String) {
        val body = functionBody(source, "private fun satisfyForegroundDeadlineThenStop()")
        val attachAt = body.indexOf("startForeground(")
        val stopAt = body.lastIndexOf(bareStop)
        assertTrue(
            "the shared exit must call startForeground() at all — without it the 5 s deadline " +
                "is never met and the process is killed at expiry:\n$body",
            attachAt >= 0,
        )
        assertTrue(
            "the shared exit must call $bareStop:\n$body",
            stopAt >= 0,
        )
        assertTrue(
            "startForeground() must run BEFORE $bareStop (attach@$attachAt, stop@$stopAt):\n$body",
            attachAt < stopAt,
        )
        assertTrue(
            "the attach must be gated on $foregroundGate so an instance that already reached the " +
                "foreground is not re-attached on its way out:\n$body",
            body.contains(foregroundGate),
        )
    }

    /** Both exits share one implementation — the stub is not duplicated again. */
    private fun assertInitSkipReusesSharedExit(source: String) {
        val body = functionBody(source, "override fun onStartCommand")
        val branch = guardedBranch(body, initSkipGuard)
        assertTrue(
            "the init-skip branch must unwind through $sharedExit:\n$branch",
            branch.contains(sharedExit),
        )
        assertFalse(
            "the stub notification must not be built inline a second time — one implementation, " +
                "one place to drift:\n$branch",
            branch.contains(inlineStub),
        )
    }

    // ── the assertions against the real source ───────────────────────────────

    @Test
    fun `swipe-from-recents exits through the shared deadline exit`() {
        assertTaskRemovedUsesSharedExit(fgsSource())
    }

    @Test
    fun `the shared exit attaches a foreground notification before stopping`() {
        assertSharedExitAttachesForegroundFirst(fgsSource())
    }

    @Test
    fun `the init-skip path reuses the same exit instead of a second stub`() {
        assertInitSkipReusesSharedExit(fgsSource())
    }

    // ── negative controls ───────────────────────────────────────────────────

    /**
     * Reverting the fix — putting the bare `stopSelf()` back into the
     * swipe-from-recents branch — must turn the first assertion red. Without
     * this arm a test that still passes after the fix is undone would be
     * worthless.
     */
    @Test
    fun `reverting to a bare stopSelf in onTaskRemoved is rejected`() {
        val body = functionBody(fgsSource(), "override fun onTaskRemoved")
        val branch = guardedBranch(body, emptySessionGate)
        val mutatedBranch = branch.replace(sharedExit, bareStop)
        assertFalse(
            "the mutation must really have replaced the shared exit — otherwise this control proves nothing",
            mutatedBranch.contains(sharedExit),
        )
        val mutatedSource = body.replace(branch, mutatedBranch)
        val thrown = assertThrowsAny { assertTaskRemovedUsesSharedExit(mutatedSource) }
        assertTrue("the mutated source must be rejected", thrown)
    }

    /**
     * Dropping the attach step from the shared exit (i.e. stopping without
     * ever entering the foreground) must turn the second assertion red — that
     * is exactly the crash this file exists to prevent.
     */
    @Test
    fun `a shared exit that stops without attaching is rejected`() {
        val body = functionBody(fgsSource(), "private fun satisfyForegroundDeadlineThenStop()")
        val mutatedBody = body.replace("startForeground(", "startNothingAtAll(")
        assertFalse(
            "the mutation must really have removed the attach — otherwise this control proves nothing",
            mutatedBody.contains("startForeground("),
        )
        val mutatedSource = fgsSource().replace(body, mutatedBody)
        val thrown = assertThrowsAny { assertSharedExitAttachesForegroundFirst(mutatedSource) }
        assertTrue("the mutated source must be rejected", thrown)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun assertThrowsAny(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (e: AssertionError) {
        true
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (from <= text.length - needle.length) {
            val hit = text.indexOf(needle, from)
            if (hit < 0) return count
            count++
            from = hit + 1
        }
        return count
    }

    /** The `{ … }` block introduced by [guard], braces balanced. */
    private fun guardedBranch(body: String, guard: String): String {
        val guardAt = body.indexOf(guard)
        assertTrue("could not find '$guard' in:\n$body", guardAt >= 0)
        val openAt = body.indexOf('{', guardAt)
        assertTrue("no body opening after '$guard'", openAt >= 0)
        val closeAt = matchingBrace(body, openAt)
        assertTrue("unbalanced braces after '$guard'", closeAt > openAt)
        return body.substring(guardAt, closeAt + 1)
    }

    /** Whole function: braces balanced from the first `{` to its matching `}`. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("could not find '$signature' in the source", start >= 0)
        val openAt = source.indexOf('{', start)
        assertTrue("no body opening for '$signature'", openAt >= 0)
        val closeAt = matchingBrace(source, openAt)
        assertTrue("unbalanced braces in '$signature'", closeAt > openAt)
        return source.substring(start, closeAt + 1)
    }

    /** Index of the `}` matching the `{` at [openIndex], skipping comments and strings. */
    private fun matchingBrace(text: String, openIndex: Int): Int {
        var depth = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var escaped = false
        for (i in openIndex until text.length) {
            val c = text[i]
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                continue
            }
            if (inBlockComment) {
                if (c == '*' && text.getOrNull(i + 1) == '/') inBlockComment = false
                continue
            }
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when {
                c == '/' && text.getOrNull(i + 1) == '/' -> inLineComment = true
                c == '/' && text.getOrNull(i + 1) == '*' -> inBlockComment = true
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    // ── source location (same technique as DatabaseVersionGuardTest) ──────────

    private fun fgsSource(): String = sourceFile(fgsFile).readText()

    private fun sourceFile(rel: String): File {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        error("'$rel' not found from ${File(".").absoluteFile}")
    }
}
