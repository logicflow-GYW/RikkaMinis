package com.rikkaminis.app.guard

import com.rikkaminis.app.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM-pure tests for the tool-loop guard: the scope decisions (which calls may be judged, and
 * how) and the adapter contract the agent loop depends on.
 *
 * Nothing here needs Android: [ActionGuard], [LoopGuard], [OutwardPacing] and [ToolLoopScope] are
 * plain Kotlin, and the clock / sleeper / random source are the injectable function values the
 * ported facade already exposes — so "a wait happened" is measured, never waited for.
 */
class ToolLoopScopeTest {

    private var now = 0L
    private var slept = 0L
    private var sleeps = 0

    @Before
    fun setUp() {
        now = 0L
        slept = 0L
        sleeps = 0
        ActionGuard.clock = { now }
        ActionGuard.sleeper = { ms -> now += ms; slept += ms; sleeps++ }
        // Deterministic: always the upper bound of the pacing window.
        ActionGuard.randomSource = { _, hi -> hi }
        ToolLoopScope.reset()
    }

    // ────────────────────────────── scope ──────────────────────────────

    @Test
    fun browserReadsAreManagedAsReads() {
        val read = ToolLoopScope.decide("browser_use", """{"action":"get_text"}""")
        assertTrue(read.managed)
        assertTrue(read.read)
        assertFalse(read.outward)
    }

    @Test
    fun browserActionsAreManagedButNotReads() {
        val click = ToolLoopScope.decide("browser_use", """{"action":"click","selector":"#ok"}""")
        assertTrue(click.managed)
        assertFalse(click.read)
        assertFalse(click.outward)
    }

    @Test
    fun ordinaryShellCommandsAreUnmanaged() {
        assertFalse(ToolLoopScope.decide("shell_execute", """{"command":"git log --oneline -3"}""").managed)
        assertFalse(ToolLoopScope.decide("shell_execute", """{"command":"rg -n TODO src/"}""").managed)
    }

    @Test
    fun screenPokingShellCommandsAreManaged() {
        assertTrue(ToolLoopScope.decide("shell_execute", """{"command":"android-a11y-cli tap 100 200"}""").managed)
        assertTrue(ToolLoopScope.decide("shell_execute", """{"command":"android-shizuku-cli exec id"}""").managed)
        assertTrue(ToolLoopScope.decide("shell_execute", """{"command":"am start -n com.x/.Main"}""").managed)
    }

    @Test
    fun fileToolsAndUnknownToolAreUnmanaged() {
        assertFalse(ToolLoopScope.decide("file_edit", """{"path":"a.kt"}""").managed)
        assertFalse(ToolLoopScope.decide("file_write", """{"path":"a.kt"}""").managed)
        assertFalse(ToolLoopScope.decide("memory_get", """{"keywords":"x"}""").managed)
    }

    @Test
    fun outwardKeywordsDrivePacingEligibility() {
        assertTrue(ToolLoopScope.decide("browser_use", """{"action":"type","text":"发送这条消息"}""").outward)
        assertTrue(ToolLoopScope.decide("browser_use", """{"action":"type","text":"Please submit"}""").outward)
        assertTrue(
            ToolLoopScope.decide(
                "browser_use",
                """{"action":"click","selector":"button:has-text(\"publish\")"}""",
            ).outward,
        )
        assertFalse(ToolLoopScope.decide("browser_use", """{"action":"type","text":"hello"}""").outward)
        // only click/type are outward candidates, whatever the URL says
        assertFalse(
            ToolLoopScope.decide(
                "browser_use",
                """{"action":"navigate","url":"https://send.example.com"}""",
            ).outward,
        )
    }

    // ────────────────────────────── adapter ──────────────────────────────

    @Test
    fun repeatingAnUnmanagedCommandIsNeverRefused() {
        val args = """{"command":"git log --oneline -3"}"""
        repeat(20) {
            val verdict = ToolLoopScope.beforeCall("s-log", "shell_execute", args)
            assertTrue(verdict is GuardVerdict.Proceed)
            ToolLoopScope.afterCall("s-log", "shell_execute", args, ToolExecutionResult("out", true), verdict)
        }
    }

    @Test
    fun repeatingAPageReadNeverReachesTheRepeatLadder() {
        val args = """{"action":"get_text"}"""
        var output = ""
        repeat(20) {
            val verdict = ToolLoopScope.beforeCall("s-read", "browser_use", args)
            assertTrue("a read must never be refused", verdict !is GuardVerdict.Refuse)
            output = ToolLoopScope.afterCall(
                "s-read", "browser_use", args, ToolExecutionResult("page", true), verdict,
            ).output
        }
        assertFalse(
            "reads feed the reread ladder, not the repeat window: $output",
            output.contains("same action on the same target"),
        )
    }

    @Test
    fun twelfthIdenticalClickIsRefusedBeforeItRuns() {
        val args = """{"action":"click","selector":"#next"}"""
        var refusedAt = -1
        for (i in 1..14) {
            val verdict = ToolLoopScope.beforeCall("s-click", "browser_use", args)
            if (verdict is GuardVerdict.Refuse) {
                refusedAt = i
                break
            }
            ToolLoopScope.afterCall("s-click", "browser_use", args, ToolExecutionResult("ok", true), verdict)
        }
        assertEquals("the 12th identical action must never execute", 12, refusedAt)
    }

    @Test
    fun refusalBecomesAFailedLoopGuardResult() {
        val args = """{"action":"click","selector":"#next"}"""
        for (i in 1..11) {
            val verdict = ToolLoopScope.beforeCall("s-click", "browser_use", args)
            ToolLoopScope.afterCall("s-click", "browser_use", args, ToolExecutionResult("ok", true), verdict)
        }
        val refused = ToolLoopScope.beforeCall("s-click", "browser_use", args) as GuardVerdict.Refuse
        val result = ToolLoopScope.refusal("browser_use", refused.reason)
        assertFalse(result.success)
        assertTrue(result.output.startsWith("[loop_guard] browser_use was not run."))
        assertTrue(result.output.contains("Refused"))
    }

    @Test
    fun aStoppedSessionStillAllowsReadsUntilTheUserSpeaks() {
        val click = """{"action":"click","selector":"#next"}"""
        for (i in 1..12) {
            val verdict = ToolLoopScope.beforeCall("s-stop", "browser_use", click)
            if (verdict !is GuardVerdict.Refuse) {
                ToolLoopScope.afterCall("s-stop", "browser_use", click, ToolExecutionResult("ok", true), verdict)
            }
        }
        assertTrue(ToolLoopScope.beforeCall("s-stop", "browser_use", click) is GuardVerdict.Refuse)
        assertTrue(
            ToolLoopScope.beforeCall("s-stop", "browser_use", """{"action":"get_text"}""")
                is GuardVerdict.ProceedWithNotice,
        )
        ToolLoopScope.onUserTurn("s-stop")
        assertTrue(ToolLoopScope.beforeCall("s-stop", "browser_use", click) is GuardVerdict.Proceed)
    }

    @Test
    fun outwardActionWaitsOnceAndTheNoticeLeadsTheResult() {
        val args = """{"action":"type","text":"发送这条消息"}"""

        val first = ToolLoopScope.beforeCall("s-pace", "browser_use", args)
        assertTrue(first is GuardVerdict.Proceed)
        ToolLoopScope.afterCall("s-pace", "browser_use", args, ToolExecutionResult("typed", true), first)

        val second = ToolLoopScope.beforeCall("s-pace", "browser_use", args)
        assertTrue(second is GuardVerdict.Wait)
        second as GuardVerdict.Wait
        assertTrue("wait must sit in the 8-20s window: ${second.millis}", second.millis in 8_000..20_000)
        assertEquals("pacing must happen exactly once per outward action", 1, sleeps)
        assertEquals(second.millis, slept)

        val paced = ToolLoopScope.afterCall(
            "s-pace", "browser_use", args, ToolExecutionResult("typed again", true), second,
        )
        assertTrue(paced.output.startsWith("[Outward pacing] Waited"))
        assertTrue(paced.output.endsWith("typed again"))
    }

    @Test
    fun ladderTextTrailsTheToolResult() {
        val args = """{"command":"android-a11y-cli tap 1 1"}"""
        var result: ToolExecutionResult? = null
        repeat(2) {
            val verdict = ToolLoopScope.beforeCall("s-fail", "shell_execute", args)
            result = ToolLoopScope.afterCall(
                "s-fail", "shell_execute", args, ToolExecutionResult("boom", false), verdict,
            )
        }
        val output = result!!.output
        assertTrue("result stays first: $output", output.startsWith("boom"))
        assertTrue("2nd consecutive failure must remind: $output", output.contains("2 calls in a row failed"))
        assertTrue(output.trimEnd().endsWith("report what is blocking you."))
    }
}
