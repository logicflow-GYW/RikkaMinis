package com.rikkaminis.app.sandbox

import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Test

/**
 * [meta-audit-1028 E3 / F-09 / F-10] The two defects live where a decision is
 * taken inside a COROUTINE that no desktop JVM can run (PersistentShell needs a
 * Context, a PTY and a Process). The decisions are therefore pinned on the
 * real source text — the same convention the repo already uses for the
 * browser / accessibility shapes ([BrowserTabPoolSaveStateTest]) — and the
 * ALGEBRA of the decision is pinned by the pure helpers, which ARE extracted
 * for JVM use.
 *
 * Split of responsibility:
 *   * source shape  → WHICH decision the site makes (this file);
 *   * pure algebra  → WHAT that decision yields (internalTimeoutOutput, the
 *     UTF-8 budget's freeze behaviour) — see [TrackedStallAndTimeoutTest].
 *
 * A literal-anchor test that only greps for a name is worthless, so every
 * anchor below is a whole expression, and each shape case has a matching
 * NEGATIVE control: the anchor must NOT be satisfiable by the pre-fix form.
 */
class TrackedShellDecisionShapeTest {

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    private fun shellSrc(): String =
        readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/PersistentShell.kt")

    // ── F-09: the progress signal must be the RECEIVED counter ─────────────

    @Test
    fun `the stall guard compares the received counter, not the buffer length`() {
        val src = shellSrc()
        assertTrue(
            "the stall guard must read the monotonic received counter — the buffer " +
                "length freezes once the output budget is exhausted, which is what " +
                "made a live command look stalled (F-09)",
            src.contains("val received = cb.totalCharsReceived"),
        )
        assertTrue(
            "the progress comparison must be against the received counter",
            src.contains("if (received != stall.lastReceivedChars)"),
        )
        assertTrue(
            "the stall state must carry the received counter",
            src.contains("var lastReceivedChars: Long = 0L"),
        )
    }

    @Test
    fun `the buffer length is not used as the progress signal anywhere`() {
        // Negative control: the pre-fix expression must be GONE. If a future
        // refactor reintroduced `cb.output.length != stall.…` the defect would
        // be back, and only this assertion notices.
        val src = shellSrc()
        assertFalse(
            "the stall guard must not compare cb.output.length again (F-09)",
            src.contains("cb.output.length != stall."),
        )
    }

    @Test
    fun `the received counter is fed by the reader, not by the buffer`() {
        val src = shellSrc()
        assertTrue(
            "the counter must be advanced where chunks arrive, before the budget " +
                "clips them — otherwise it freezes at the ceiling exactly like the " +
                "buffer did",
            src.contains("receivedChars.addAndGet(text.length.toLong())"),
        )
        assertTrue(
            "the counter must live next to the byte budget in the callback",
            src.contains("private val receivedChars = java.util.concurrent.atomic.AtomicLong(0L)"),
        )
    }

    @Test
    fun `the stall guard comment no longer claims the buffer length is progress`() {
        // The old comment ("a length change is progress") is what made the
        // defect look intentional for years. It must not survive.
        val src = shellSrc()
        assertFalse(
            "the stale 'length change is progress' rationale must be gone",
            src.contains("a length change is progress"),
        )
    }

    // ── F-10: the timeout must keep what was printed ───────────────────────

    @Test
    fun `the timeout branch returns the captured output`() {
        val src = shellSrc()
        assertTrue(
            "the timeout branch must return the captured output — returning only " +
                "the notice threw away every clue about where the command stopped (F-10)",
            src.contains("internalTimeoutOutput(cb.output.toString(), timeout)"),
        )
        assertTrue(
            "the timeout branch must keep the truncation flag",
            src.contains("TIMEOUT_EXIT_CODE,\n                    cb.truncated,"),
        )
    }

    @Test
    fun `the bare timeout notice is not what the branch returns`() {
        // Negative control for the pre-fix form.
        val src = shellSrc()
        assertFalse(
            "the timeout branch must not discard the captured output (F-10)",
            src.contains(
                "CommandResult(\"[Command timed out after \${timeout / 1000}s]\", TIMEOUT_EXIT_CODE)",
            ),
        )
    }

    @Test
    fun `the stall branch and the timeout branch now agree in shape`() {
        // Both failures now carry the captured text plus a trailing notice.
        val src = shellSrc()
        val stall = src.indexOf("cb.output.toString() + stallMessage(stallAfterMs)")
        val timeout = src.indexOf("internalTimeoutOutput(cb.output.toString(), timeout)")
        assertTrue("stall branch anchor missing", stall >= 0)
        assertTrue("timeout branch anchor missing", timeout >= 0)
    }

    // ── F-14: the guest temp dir is pinned, and pinned LAST ────────────────

    @Test
    fun `the guest temp dir is pinned to the guest path in the agent shell`() {
        val src = shellSrc()
        assertTrue(src.contains("env[\"TMPDIR\"] = \"/tmp\""))
        assertTrue(src.contains("env[\"TMP\"] = \"/tmp\""))
    }

    @Test
    fun `the guest temp pin comes after the process-global environment merge`() {
        // Ordering is the whole point of F-14: a value written BEFORE the
        // merge can be overwritten by the process-global map, which is how the
        // host TMPDIR came back in the first place.
        val src = shellSrc()
        val merge = src.indexOf("for ((key, value) in PRootKernel.customEnvironment) {")
        val tmpdir = src.indexOf("env[\"TMPDIR\"] = \"/tmp\"")
        assertTrue("customEnvironment merge not found", merge >= 0)
        assertTrue("TMPDIR pin not found", tmpdir >= 0)
        assertTrue(
            "the guest temp pin must be applied AFTER the customEnvironment " +
                "merge (F-14): merge=$merge tmpdir=$tmpdir",
            tmpdir > merge,
        )
    }

    @Test
    fun `the interactive PTY pins the same guest temp pair`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/TerminalSession.kt")
        assertTrue("TerminalSession must pin TMPDIR", src.contains("envMap[\"TMPDIR\"] = \"/tmp\""))
        assertTrue("TerminalSession must pin TMP", src.contains("envMap[\"TMP\"] = \"/tmp\""))
    }

    @Test
    fun `the interactive PTY pin also comes after the process-global merge`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/TerminalSession.kt")
        val merge = src.indexOf("for ((k, v) in PRootKernel.customEnvironment) envMap[k] = v")
        val tmpdir = src.indexOf("envMap[\"TMPDIR\"] = \"/tmp\"")
        assertTrue("customEnvironment merge not found", merge >= 0)
        assertTrue("TMPDIR pin not found", tmpdir >= 0)
        assertTrue(
            "the PTY pin must be applied AFTER the customEnvironment merge (F-14)",
            tmpdir > merge,
        )
    }
}
