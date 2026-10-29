package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [SYMPTOM-A F-A2b] The only log evidence that a shell result is untrustworthy.
 *
 * `internalDecideShellRetryVerdict` returns `outcomeKnown = false` on two exits
 * (memory-recycled shell / must-verify-or-unknown result). Until this branch
 * those two exits were silent: the only downstream trace was the T7-D shadow
 * reducer's "declined" line, which names no command and reads like a failure.
 *
 * Two invariants this file pins, both about PLACEMENT rather than wording:
 *  1. the log lives at the CONSUMPTION point (`executeWithShellRetry`, where
 *     the verdict becomes a CommandResult) — never inside the pure function,
 *     which is Android-free and JVM-tested, and which runs on every retry
 *     evaluation;
 *  2. it carries metadata only — no command text, no output fragment
 *     (credentials leak through tool output; that rule already cost us once).
 */
class UnknownOutcomeLogShapeTest {

    private val src: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt")
    }

    private val marker = "outcome unknown (reason="

    @Test
    fun `the unknown-outcome log sits at the consumption point, not in the pure function`() {
        val consume = src.indexOf(marker)
        assertTrue("unknown-outcome log line is missing", consume >= 0)
        val pureFn = src.indexOf("internal fun internalDecideShellRetryVerdict(")
        assertTrue("pure verdict function is missing", pureFn >= 0)
        assertTrue(
            "the log must be emitted where the verdict is consumed (before the pure function " +
                "in this file), not inside it",
            consume < pureFn,
        )
        val pureBody = src.substring(pureFn, src.indexOf("\n}\n", pureFn))
        assertFalse(
            "the pure function must stay Android-free / JVM-testable — no Log call inside it",
            pureBody.contains("Log."),
        )
    }

    @Test
    fun `the log fires only when the verdict says the outcome is unknown`() {
        val consume = src.indexOf(marker)
        val gate = src.lastIndexOf("if (!verdict.outcomeKnown) {", consume)
        assertTrue("the log must be gated on !verdict.outcomeKnown", gate >= 0)
        assertTrue("the gate must wrap the log itself", consume - gate < 400)
        // 位置：消费点判定之后、重试分支之前 —— 两条分支（重试 / 不重试）都已覆盖。
        val retryBranch = src.indexOf("if (!verdict.retry) {", consume)
        assertTrue("the log must sit before the retry/no-retry split", retryBranch > consume)
    }

    @Test
    fun `the log carries metadata only`() {
        val consume = src.indexOf(marker)
        val window = src.substring(consume, (consume + 700).coerceAtMost(src.length))
        assertFalse("no command content in the log:\n$window", window.contains("\$" + "{command}"))
        assertFalse("no output fragment in the log:\n$window", window.contains("result.output"))
        assertFalse("no output fragment in the log:\n$window", window.contains("command.substring"))
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException(relative)
    }
}
