package com.rikkaminis.app.guard

import com.rikkaminis.app.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [R8-1114 A-F05] A read-only shell command must not be judged as a Repeat
 * loop. `android-a11y-cli ui dump` is a screen read: polling it used to feed the
 * Repeat ladder (notice at 3, refusal at 12) even though the class KDoc promises
 * reads are exempt. Screen-moving subcommands (`tap` / `input` / `scroll`) and
 * opaque ones (`android-shizuku-cli exec`, `am start`) must stay on the write
 * side.
 */
class ToolLoopScopeReadTest {

    private var now = 0L

    @Before
    fun setUp() {
        now = 0L
        ActionGuard.clock = { now }
        ActionGuard.sleeper = { ms -> now += ms }
        ActionGuard.randomSource = { _, hi -> hi }
        ToolLoopScope.reset()
    }

    @Test
    fun a11yReadSubcommandsAreManagedReads() {
        for (command in listOf(
            "android-a11y-cli ui dump",
            "android-a11y-cli ui dump --depth 3",
            "android-a11y-cli   ui    dump",
            "android-a11y-cli ui screenshot",
            "android-a11y-cli ui info",
            "android-a11y-cli extract text",
            "android-a11y-cli wait stable",
            "android-a11y-cli event once",
            "android-a11y-cli dialog detect",
            "android-a11y-cli service status",
        )) {
            val decision = ToolLoopScope.decide("shell_execute", """{"command":"$command"}""")
            assertTrue("$command must stay managed", decision.managed)
            assertTrue("$command must be a read", decision.read)
        }
    }

    @Test
    fun screenMovingSubcommandsStayOnTheWriteSide() {
        for (command in listOf(
            "android-a11y-cli tap 100 200",
            "android-a11y-cli tap text Login",
            "android-a11y-cli input text hello",
            "android-a11y-cli scroll to-text Settings",
            "android-a11y-cli gesture swipe",
            "android-a11y-cli dialog dismiss",
            "android-a11y-cli service restart",
            "android-shizuku-cli exec id",
            "am start -n com.x/.Main",
        )) {
            val decision = ToolLoopScope.decide("shell_execute", """{"command":"$command"}""")
            assertTrue("$command must stay managed", decision.managed)
            assertFalse("$command must NOT be a read", decision.read)
        }
    }

    @Test
    fun aCommandSharingTheRawPrefixIsNotARead() {
        // Token matching, not `startsWith`: `ui dumper` is not `ui dump`.
        val decision = ToolLoopScope.decide("shell_execute", """{"command":"android-a11y-cli ui dumper"}""")
        assertTrue(decision.managed)
        assertFalse(decision.read)
    }

    @Test
    fun repeatedScreenReadsNeverReachTheRepeatLadder() {
        val args = """{"command":"android-a11y-cli ui dump"}"""
        repeat(20) { index ->
            val verdict = ToolLoopScope.beforeCall("s-a11y-read", "shell_execute", args)
            assertTrue("call #${index + 1} must not be refused", verdict !is GuardVerdict.Refuse)
            val out = ToolLoopScope.afterCall(
                "s-a11y-read", "shell_execute", args, ToolExecutionResult("tree", true), verdict,
            ).output
            assertEquals("call #${index + 1} must stay undecorated: $out", "tree", out)
        }
    }

    @Test
    fun repeatedScreenMovesStillRefuseAtTwelve() {
        val args = """{"command":"android-a11y-cli tap 100 200"}"""
        var refusedAt = -1
        for (i in 1..14) {
            val verdict = ToolLoopScope.beforeCall("s-a11y-tap", "shell_execute", args)
            if (verdict is GuardVerdict.Refuse) {
                refusedAt = i
                break
            }
            ToolLoopScope.afterCall(
                "s-a11y-tap", "shell_execute", args, ToolExecutionResult("ok", true), verdict,
            )
        }
        assertEquals("the 12th identical tap must never execute", 12, refusedAt)
    }
}
