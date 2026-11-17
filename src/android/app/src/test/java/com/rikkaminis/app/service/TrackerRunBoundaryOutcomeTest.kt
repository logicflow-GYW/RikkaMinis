package com.rikkaminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/**
 * [WAVE-1118 D7 F-02 + R4 F-03] Wiring of two `SessionActivityTracker` run-boundary
 * faces.
 *
 * `SessionActivityTracker` is an Android singleton (Handler/Looper/Context, an
 * `AgentForegroundService` static) that the JVM runtime cannot drive, so — like
 * `R5D1TrackerLingerWiringTest` and `SessionActivityStreamErrorTest`, which pin
 * the same object the same way — these are source-shape assertions on the real
 * file, and every assertion has a negative control that runs it against a
 * mutated copy to prove it can fail.
 *
 * Pinned here:
 *  - F-02: a new run resets `_lastToolOutcome`. The completion capsule renders
 *    `state.lastOutcome`, and nothing else writes it on a text-only turn
 *    (`setInactive` writes only on the error path, `applyToolUpdate` only when a
 *    tool actually finishes), so a failed run's red ✗ used to be stamped onto
 *    the NEXT run's clean reply.
 *  - R4 F-03: `isAppForeground` is `@Volatile`. It is written from the Main
 *    thread (app lifecycle) and read wherever a stream's `finally` settles; a
 *    plain field gives no happens-before edge, so the settle could read a stale
 *    `true` and skip the background completion window.
 */
class TrackerRunBoundaryOutcomeTest {

    private val trackerFile = "src/android/app/src/main/java/com/rikkaminis/app/service/SessionActivityTracker.kt"

    @Test
    fun `setActive clears the previous run's tool outcome`() {
        val body = setActiveBody(trackerSource())
        assertTrue(
            "setActive must reset _lastToolOutcome (otherwise a text-only turn inherits the " +
                "previous run's ✗):\n$body",
            resetsOutcome(body),
        )
        assertTrue(
            "the other per-run display fields must keep being cleared next to it",
            withoutComments(body).contains("_lastReplyExcerpt.value = null") &&
                withoutComments(body).contains("_lastToolStatus.value = null"),
        )
    }

    @Test
    fun `negative control - a setActive without the outcome reset is rejected`() {
        val body = setActiveBody(trackerSource())
        val mutated = body.replace("_lastToolOutcome.value = ToolOutcome.Unknown\n", "")
        assertTrue("the negative control must actually mutate the body", mutated != body)
        assertFalse(
            "a setActive that keeps the stale outcome must be rejected",
            resetsOutcome(mutated),
        )
    }

    @Test
    fun `isAppForeground is volatile`() {
        val source = trackerSource()
        assertTrue(
            "isAppForeground is written on Main and read from the settling thread — it needs @Volatile",
            isVolatileDeclaration(source),
        )
    }

    @Test
    fun `negative control - a plain isAppForeground field is rejected`() {
        val mutated = trackerSource().replace(
            "@Volatile\n    private var isAppForeground: Boolean = true",
            "private var isAppForeground: Boolean = true",
        )
        assertTrue("the negative control must actually mutate the source", mutated != trackerSource())
        assertFalse(isVolatileDeclaration(mutated))
    }

    // ── the checks ──────────────────────────────────────────────────────────

    private fun setActiveBody(source: String): String =
        bodyOf(source, "fun setActive(sessionId: String, onStop:")

    private fun resetsOutcome(body: String): Boolean =
        withoutComments(body).contains("_lastToolOutcome.value = ToolOutcome.Unknown")

    private fun isVolatileDeclaration(source: String): Boolean {
        val decl = "private var isAppForeground: Boolean = true"
        val at = source.indexOf(decl)
        if (at < 0) return false
        // Look at the two lines above the declaration: the annotation must be
        // the last thing before it (a @Volatile on some other field does not
        // count).
        val before = source.substring(0, at).trimEnd()
        val lastLine = before.substringAfterLast('\n').trim()
        return lastLine == "@Volatile"
    }

    private fun withoutComments(text: String): String = text
        .replace(Regex("""//[^\n]*"""), " ")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")

    private fun trackerSource(): String =
        readRepoFile(trackerFile)

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun bodyOf(source: String, header: String): String {
        val at = source.indexOf(header)
        assertTrue("anchor missing: $header", at >= 0)
        val open = source.indexOf('{', at)
        assertTrue("no body brace after $header", open >= 0)
        var depth = 0
        var inStr = false
        var escaped = false
        var i = open
        while (i < source.length) {
            val c = source[i]
            if (inStr) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return source.substring(at, i + 1)
                    }
                }
            }
            i++
        }
        error("unbalanced braces after $header")
    }
}
