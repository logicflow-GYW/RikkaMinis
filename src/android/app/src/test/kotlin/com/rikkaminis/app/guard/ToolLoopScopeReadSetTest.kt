package com.rikkaminis.app.guard

import com.rikkaminis.app.tools.ToolConcurrencyPolicy
import com.rikkaminis.app.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv1101-review F-02 / F-05] The guard's read set and its refusal shape, pinned so the two
 * defects this review found cannot come back:
 *
 *  - the read set used to be a hand-kept copy of `ToolConcurrencyPolicy`'s list, and it drifted
 *    (`get_cookies` missing) — a legitimate repeated read was reminded at the 3rd call and
 *    refused at the 12th;
 *  - a refusal rendered as an untitled failed tool block because it carried no `toolTitle`.
 */
class ToolLoopScopeReadSetTest {

    @Test
    fun guardReadSetIsTheConcurrencyPolicyReadSet() {
        // One table, two readers. If someone re-copies the list instead of referencing it,
        // this fails the moment the two disagree.
        assertEquals(
            ToolConcurrencyPolicy.BROWSER_READ_ACTIONS,
            ToolLoopScope.BROWSER_READ_ACTIONS,
        )
        assertTrue("get_cookies" in ToolLoopScope.BROWSER_READ_ACTIONS)
    }

    @Test
    fun cookieReadsAreManagedReadsNotActions() {
        val cookies = ToolLoopScope.decide("browser_use", """{"action":"get_cookies"}""")
        assertTrue(cookies.managed)
        assertTrue(cookies.read)
        assertFalse(cookies.outward)
    }

    @Test
    fun repeatedCookieReadsNeverReachTheRepeatLadder() {
        // Behaviour, not shape: 20 identical reads inside one turn — the window that used to
        // fire the Repeat ladder at the 3rd call (notice) and the 12th (refusal).
        val args = """{"action":"get_cookies"}"""
        repeat(20) { index ->
            val verdict = ToolLoopScope.beforeCall("s-cookies", "browser_use", args)
            assertTrue("call #${index + 1} must not be refused", verdict !is GuardVerdict.Refuse)
            val out = ToolLoopScope.afterCall(
                "s-cookies", "browser_use", args, ToolExecutionResult("ok", true), verdict,
            ).output
            assertEquals("call #${index + 1} must stay undecorated: $out", "ok", out)
        }
    }

    @Test
    fun refusalCarriesTheToolTitle() {
        val refused = ToolLoopScope.refusal("browser_use", "stopped")
        assertFalse(refused.success)
        assertEquals("browser_use", refused.toolTitle)
    }

    @Test
    fun unmanagedToolsAreNotTouchedByAfterCall() {
        // [dv1101-review F-05] The name-only short-circuit must not change what an unmanaged
        // call's result looks like — and must not consult the args at all: a payload that is
        // not even JSON still passes through byte-for-byte.
        val result = ToolExecutionResult("payload", true)
        val out = ToolLoopScope.afterCall(
            "s", "file_write", "not json at all {{{", result, GuardVerdict.Proceed,
        )
        assertEquals(result, out)
        assertEquals("payload", out.output)
    }
}
