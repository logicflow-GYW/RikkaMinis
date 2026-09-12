package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.AgentRuntimeLimitsPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fix/tuning-shell-timeout] Pins [resolveShellTimeoutSec]:
 *  - a call carrying its own `timeout` keeps the long-standing model-facing
 *    cap (1..900 s) — unchanged;
 *  - a call that omits it uses the user-tunable default from Runtime Limits,
 *    whose shipped default must stay at the effective pre-panel value
 *    (900 s) so an untouched install behaves exactly as before.
 *
 * Before this fix the knob fed only PersistentShell.executeCommand's default
 * parameter, which its single caller never relies on — the setting was inert.
 */
class ShellTimeoutResolutionTest {

    @Test
    fun `explicit timeouts keep their own value up to the 900 second cap`() {
        assertEquals(900, resolveShellTimeoutSec(hasExplicit = true, explicitSec = 900))
        assertEquals(900, resolveShellTimeoutSec(hasExplicit = true, explicitSec = 5000))
        assertEquals(600, resolveShellTimeoutSec(hasExplicit = true, explicitSec = 600))
    }

    @Test
    fun `explicit timeouts clamp to at least one second`() {
        assertEquals(1, resolveShellTimeoutSec(hasExplicit = true, explicitSec = 0))
        assertEquals(1, resolveShellTimeoutSec(hasExplicit = true, explicitSec = -5))
    }

    @Test
    fun `omitted timeout uses the tunable default`() {
        assertEquals(1200, resolveShellTimeoutSec(hasExplicit = false, explicitSec = 0, defaultSec = 1200))
        assertEquals(60, resolveShellTimeoutSec(hasExplicit = false, explicitSec = 999, defaultSec = 60))
        // The explicit value must be ignored entirely on this branch.
        assertEquals(120, resolveShellTimeoutSec(hasExplicit = false, explicitSec = 900, defaultSec = 120))
    }

    @Test
    fun `unprimed default equals the pre-panel effective default`() {
        // JVM tests have no Context: the prefs cache serves its shipped
        // default, which must match what ChatShellExecution's old
        // optInt("timeout", 900) did for calls that omit their own timeout.
        assertEquals(900, AgentRuntimeLimitsPrefs.SHELL_TIMEOUT_DEFAULT_SEC)
        assertEquals(900, resolveShellTimeoutSec(hasExplicit = false, explicitSec = 0))
    }
}
