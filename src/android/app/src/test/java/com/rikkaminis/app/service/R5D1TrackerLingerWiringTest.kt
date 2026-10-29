package com.rikkaminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [R5-D F-01 / F-04] Wiring shape for the tracker-level half of the tool-slot
 * and completion-linger fixes. `ToolSlotRegistry` / `CompletionLinger` are pure
 * classes (table tests live next to them); `SessionActivityTracker` is an
 * Android singleton (Context / Handler / Looper) the JVM runtime cannot reach,
 * so its four consumer sites are pinned on the compiled sources themselves —
 * the same probe shape as `AppInitSkipGuardWiringTest`, with expectations
 * written as literals no production constant supplies.
 *
 * Pinned here:
 *  - F-01② `publishToolSlot()` resets the display when the registry has no
 *    representative (stale tool line / stale tap target otherwise survive).
 *  - F-01① `setActive()` resets the session's own registry entry (run boundary).
 *  - F-04① `setInactive()` repaints the ongoing notification when the linger
 *    window is armed (the row otherwise spins for the whole 30 s).
 *  - F-04② `dismissOverlay()` / `maybeRefreshService()` cancel the linger and
 *    re-arm the idle stop (the service otherwise stays up for nothing).
 *  - the registry's every entry point stays `synchronized`.
 *
 * Every check is a function of the body text, so the negative control can run
 * the SAME checks against a mutated copy and prove they can actually fail.
 */
class R5D1TrackerLingerWiringTest {

    private val trackerFile = "src/main/java/com/rikkaminis/app/service/SessionActivityTracker.kt"
    private val registryFile = "src/main/java/com/rikkaminis/app/service/ToolSlotRegistry.kt"

    // -- literals the production code must contain (comments stripped first) --

    private val lingerRefreshCall = "AgentForegroundService.refreshIfRunning("
    private val clearedWrite = "completionLingerUntilMs = CompletionLinger.CLEARED"
    private val scheduleStop = "scheduleIdleStop()"
    private val dismissGuard = "if (!shouldRunService()) scheduleIdleStop()"
    private val runBoundaryDrop = "toolSlots.drop(sessionId)"
    private val idleReset = "_currentToolStatus.value = ToolSlotRegistry.IDLE_STATUS"
    private val armedCheck = "completionLingerUntilMs > CompletionLinger.CLEARED"

    // -- F-04① the linger window must repaint the notification ---------------

    @Test
    fun setInactiveRepaintsTheNotificationInsideTheLingerBranch() {
        val body = functionBody(trackerSource(), "fun setInactive(sessionId: String)")
        val norm = normalize(body)
        val armAt = norm.indexOf("CompletionLinger.deadlineOnTurnEnd(")
        assertTrue("deadlineOnTurnEnd expected in setInactive", armAt >= 0)
        val stopAt = norm.indexOf("stopService()", armAt)
        assertTrue("stopService expected after the linger arm", stopAt > armAt)
        val refreshAt = norm.indexOf(lingerRefreshCall, armAt)
        assertTrue(
            "the armed linger window must repaint the ongoing notification before " +
                "the stop is scheduled (arm@$armAt, refresh@$refreshAt, stop@$stopAt) — " +
                "without the refresh row 9001 keeps the finished turn's tool line and " +
                "indeterminate spinner for the whole window",
            refreshAt in armAt..stopAt,
        )
        // The refresh must carry the live counts/status, not a stale snapshot.
        assertTrue(
            norm.substring(armAt, stopAt).contains("sessionCountForNotification()") &&
                norm.substring(armAt, stopAt).contains("statusForNotification()"),
        )
    }

    // -- F-04② dismissing the capsule / returning to the foreground ----------

    @Test
    fun dismissOverlayCancelsTheLingerAndRearmsTheIdleStop() {
        val norm = normalize(functionBody(trackerSource(), "fun dismissOverlay()"))
        assertEquals(
            "dismissOverlay must cancel the armed window exactly once:\n$norm",
            1,
            occurrences(norm, clearedWrite),
        )
        assertTrue(
            "the idle stop must be re-armed through scheduleIdleStop() (same 500 ms " +
                "debounce as every other stop — an immediate stopServiceNow() would " +
                "turn a draft→persisted session swap into a stop/start race)",
            dismissGuard in norm,
        )
    }

    @Test
    fun foregroundReturnCancelsTheLingerWhenNothingRuns() {
        val norm = normalize(functionBody(trackerSource(), "fun maybeRefreshService()"))
        assertTrue(
            "maybeRefreshService must consult the armed window:\n$norm",
            armedCheck in norm,
        )
        assertTrue(
            "a foreground return with nothing left to run must cancel the linger:\n$norm",
            occurrences(norm, clearedWrite) == 1,
        )
        assertTrue("…and re-arm the idle stop", occurrences(norm, scheduleStop) == 1)
    }

    // -- F-01② the display resets when the registry has nothing left ----------

    @Test
    fun publishToolSlotResetsToIdleWhenNoEntryRemains() {
        val norm = normalize(functionBody(trackerSource(), "private fun publishToolSlot()"))
        assertTrue(
            "publishToolSlot must reset the status line when the registry has no " +
                "representative — the stale tool line otherwise outlives the session " +
                "that produced it:\n$norm",
            idleReset in norm,
        )
        // The reset must live in the no-representative arm, after the lookup.
        val lookupAt = norm.indexOf("representative(")
        val resetAt = norm.indexOf(idleReset)
        assertTrue(lookupAt in 0 until resetAt)
    }

    // -- F-01① a new run starts from a clean registry slate -------------------

    @Test
    fun setActiveResetsTheSessionsOwnRegistryEntry() {
        val body = functionBody(trackerSource(), "fun setActive(sessionId: String, onStop:")
        val norm = normalize(body)
        val dropAt = norm.indexOf(runBoundaryDrop)
        val logAt = norm.indexOf("Session activated")
        assertTrue(
            "setActive must drop the session's own registry entry at the run boundary " +
                "(a preflight-refused start has no finish; without the drop its phantom " +
                "depth would outlive the run)",
            dropAt >= 0,
        )
        assertTrue("the drop must precede the activation log", dropAt < logAt)
    }

    // -- the registry's entry points stay serialized --------------------------

    @Test
    fun registryEntryPointsAreSynchronized() {
        val src = registrySource()
        for (signature in listOf(
            "fun start(",
            "fun finish(sessionId: String)",
            "fun drop(sessionId: String)",
            "fun slot(sessionId: String)",
            "fun clear()",
            "fun representative(",
        )) {
            val body = functionBody(src, signature)
            assertTrue(
                "'$signature' must run under synchronized(this) — the registry is shared " +
                    "by agent-loop IO coroutines and the notification readers",
                "synchronized(this)" in body,
            )
        }
    }

    // -- negative control ------------------------------------------------------

    /**
     * Proves this file can fail: the two fixes this pins are removed from a
     * copy of the real source and the very same checks must reject it.
     */
    @Test
    fun `checks reject a source that lost the linger refresh and the dismissal cancel`() {
        val setInactive = normalize(functionBody(trackerSource(), "fun setInactive(sessionId: String)"))
        val mutated = setInactive.replace(lingerRefreshCall, "")
        val lingerGone = try {
            assertWindowContains(setInactive, "CompletionLinger.deadlineOnTurnEnd(", lingerRefreshCall, "stopService()")
            assertWindowContains(mutated, "CompletionLinger.deadlineOnTurnEnd(", lingerRefreshCall, "stopService()")
            false
        } catch (e: AssertionError) {
            true
        }
        assertTrue("the mutation must fail the linger-refresh check", lingerGone)

        val dismissBody = normalize(functionBody(trackerSource(), "fun dismissOverlay()"))
        val cancelGone = try {
            assertEquals(1, occurrences(dismissBody.replace(clearedWrite, ""), clearedWrite))
            false
        } catch (e: AssertionError) {
            true
        }
        assertTrue("the mutation must fail the dismissal-cancel check", cancelGone)

        val publishBody = normalize(functionBody(trackerSource(), "private fun publishToolSlot()"))
        val resetGone = try {
            val hadReset = idleReset in publishBody
            assertTrue(hadReset)
            assertTrue(idleReset in publishBody.replace(idleReset, ""))
            false
        } catch (e: AssertionError) {
            true
        }
        assertTrue("the mutation must fail the idle-reset check", resetGone)

        val setActiveBody = normalize(functionBody(trackerSource(), "fun setActive(sessionId: String, onStop:"))
        val dropGone = try {
            assertTrue(setActiveBody.indexOf(runBoundaryDrop) >= 0)
            assertTrue(setActiveBody.replace(runBoundaryDrop, "").indexOf(runBoundaryDrop) >= 0)
            false
        } catch (e: AssertionError) {
            true
        }
        assertTrue("the mutation must fail the run-boundary-drop check", dropGone)
    }

    /** The setInactive window check, as a reusable function for the control. */
    private fun assertWindowContains(body: String, from: String, needle: String, until: String) {
        val armAt = body.indexOf(from)
        val stopAt = body.indexOf(until, armAt)
        val refreshAt = body.indexOf(needle, armAt)
        assertTrue(refreshAt in armAt..stopAt)
    }

    // -- helpers ---------------------------------------------------------------

    private fun trackerSource(): String = sourceFile(trackerFile).readText()
    private fun registrySource(): String = sourceFile(registryFile).readText()

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

    private fun normalize(text: String): String = text
        .replace(Regex("""//[^\n]*"""), " ")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

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

    /** Whole function: braces balanced from the first `{` to its matching `}`. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("could not find '$signature'", start >= 0)
        val openIdx = source.indexOf('{', start)
        assertTrue("no body opening for '$signature'", openIdx >= 0)
        var depth = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var escaped = false
        for (i in openIdx until source.length) {
            val c = source[i]
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                continue
            }
            if (inBlockComment) {
                if (c == '*' && source.getOrNull(i + 1) == '/') inBlockComment = false
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
                c == '/' && source.getOrNull(i + 1) == '/' -> inLineComment = true
                c == '/' && source.getOrNull(i + 1) == '*' -> inBlockComment = true
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("unbalanced braces after '$signature'")
    }
}
