package com.rikkaminis.app.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * [WAVE-1118 R3-F01] `PersistentShell.stop()`'s reap step had an open EFFECT
 * edge: the daemon reaper ran `proc.waitFor(2, SECONDS)` as a bare statement and
 * threw the answer away, while `process` had already been nulled (so `isAlive`
 * reads false). A tracer stuck in D-state, a ptrace-stopped process or a
 * reparented tree therefore outlived the "shell stopped" log line with nothing
 * anywhere saying so.
 *
 * The fix consumes the answer ([internalAwaitProcessGone]) and logs an error when
 * the tree survived; the wiring side checks — on comment-stripped source — that
 * the reaper really asks, and that no bare `waitFor(...)` statement came back.
 *
 * A fake [Process] is enough here: this defect is about whether a boolean is
 * read, not about kernel behaviour, and `stop()` itself needs a Context plus a
 * live PRoot tree.
 */
class PersistentShellReapTest {

    // ── 1. the verdict ──────────────────────────────────────────────────

    @Test
    fun `a process that exits inside the window is confirmed gone`() {
        assertTrue(internalAwaitProcessGone(FakeProcess(aliveAfterWait = false), 2_000L))
    }

    @Test
    fun `a process that outlives the window is NOT confirmed gone`() {
        assertFalse(internalAwaitProcessGone(FakeProcess(aliveAfterWait = true), 2_000L))
    }

    @Test
    fun `a still-live process object is not confirmed gone even if waitFor said so`() {
        // Zombie-but-present / raced exit: `waitFor` returned true but the object
        // still reports alive. Treating that as gone is what let the leak go
        // unnoticed, so the pair must be required.
        assertFalse(internalAwaitProcessGone(FakeProcess(aliveAfterWait = false, stillAliveAfterWait = true), 2_000L))
    }

    @Test
    fun `an InterruptedException from waitFor is a survivor, not a crash`() {
        assertFalse(internalAwaitProcessGone(FakeProcess(throwOnWait = true), 2_000L))
    }

    // ── 2. wiring ────────────────────────────────────────────────────────

    @Test
    fun `the reaper consumes the verdict instead of discarding it`() {
        val code = stripComments(
            readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/PersistentShell.kt"),
        )
        assertTrue(
            "the reaper must ask the extracted predicate",
            code.contains("internalAwaitProcessGone(proc, REAP_WAIT_MS)"),
        )
        assertTrue(
            "and must branch on the answer",
            code.contains("if (!gone)") && code.contains("survived SIGKILL"),
        )
        // The declared defect: a bare `waitFor(...)` statement whose boolean
        // nobody read. Pinned by COUNT + POSITION rather than by a regex over
        // statement shapes: exactly one `waitFor` may exist in this file and it
        // must be the helper's own, whose answer is bound to `exited`. Removing
        // the fix re-adds the reaper's bare call and this goes red on the count.
        val occurrences = WAIT_FOR.findAll(code).count()
        assertTrue("exactly one waitFor( must exist in this file, found $occurrences", occurrences == 1)
        val helperAt = code.indexOf("internal fun internalAwaitProcessGone(")
        val waitAt = code.indexOf(".waitFor(")
        assertTrue("the only waitFor( must be inside the helper", helperAt > 0 && waitAt > helperAt)
        assertTrue(
            "and its answer must be bound, not discarded",
            code.contains("val exited = runCatching {"),
        )
    }

    @Test
    fun `the reap budget is an explicit named constant`() {
        val code = stripComments(
            readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/PersistentShell.kt"),
        )
        assertTrue(
            "the timeout must be named with a reason, not an inline literal",
            code.contains("internal const val REAP_WAIT_MS = 2_000L"),
        )
    }

    // ── fake process ────────────────────────────────────────────────────

    /**
     * Minimal [Process] stand-in. Only `waitFor(timeout, unit)` and `isAlive`
     * matter to the predicate; everything else throws so an accidental call is
     * loud rather than silently "working".
     */
    private class FakeProcess(
        private val aliveAfterWait: Boolean = false,
        private val stillAliveAfterWait: Boolean = aliveAfterWait,
        private val throwOnWait: Boolean = false,
    ) : Process() {
        override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()
        override fun getInputStream(): InputStream = throw UnsupportedOperationException()
        override fun getErrorStream(): InputStream = throw UnsupportedOperationException()
        override fun waitFor(): Int = throw UnsupportedOperationException()
        override fun exitValue(): Int = throw UnsupportedOperationException()
        override fun destroy() = Unit

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (throwOnWait) throw InterruptedException("reaper interrupted")
            return !aliveAfterWait
        }

        override fun isAlive(): Boolean = stillAliveAfterWait
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, "src/android/app").isDirectory) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun readRepoFile(relative: String): String = File(repoRoot(), "src/android/$relative").readText()

    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> if (c == '\n') { inLine = false; sb.append(c) }
                inBlock -> if (c == '*' && next == '/') { inBlock = false; i += 1 }
                inString -> {
                    sb.append(c)
                    if (c == '\\') { sb.append(next); i += 1 } else if (c == '"') inString = false
                }
                else -> when {
                    c == '"' -> { inString = true; sb.append(c) }
                    c == '/' && next == '/' -> { inLine = true; i += 1 }
                    c == '/' && next == '*' -> { inBlock = true; i += 1 }
                    else -> sb.append(c)
                }
            }
            i += 1
        }
        return sb.toString()
    }

    private companion object {
        /** Every `waitFor(` call in the file — the count is the assertion. */
        val WAIT_FOR = Regex("""\.waitFor\(""")
    }
}
