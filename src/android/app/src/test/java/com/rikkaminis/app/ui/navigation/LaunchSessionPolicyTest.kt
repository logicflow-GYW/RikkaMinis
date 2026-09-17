package com.rikkaminis.app.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/coldstart-restore-vs-launch-mode] Pins the cold-start restore gate.
 *
 * Background: MainActivity synthesises an `OpenSession` deep link from the
 * process-level last-session id, and AppNavigation's launch-mode dispatcher
 * returns early whenever a deep link is present — so an unconditional restore
 * silently overrode the user's Launch Session preference (user report: "New
 * Chat" set, app still re-opened the previous conversation after a full exit).
 * The gate keys off the preference because a real device cannot distinguish
 * "user closed the app" from "system killed it" (every launch-beacon verdict is
 * silent_kill — onTerminate never runs).
 */
class LaunchSessionPolicyTest {

    @Test
    fun autoKeepsTheRestore() {
        assertTrue(coldStartRestoreAllowed(LaunchSessionMode.AUTO))
    }

    @Test
    fun lastSessionKeepsTheRestore() {
        assertTrue(coldStartRestoreAllowed(LaunchSessionMode.LAST_SESSION))
    }

    @Test
    fun newChatRefusesTheRestore() {
        // The reported regression: mode 2 must land on a fresh draft chat.
        assertFalse(coldStartRestoreAllowed(LaunchSessionMode.NEW_CHAT))
    }

    @Test
    fun safeStartRefusesTheRestore() {
        // Safe Start exists to avoid re-entering a chat that may have killed
        // the previous cycle; restoring that very chat defeats its purpose.
        assertFalse(coldStartRestoreAllowed(LaunchSessionMode.SAFE_START))
    }

    @Test
    fun unknownModesRefuseTheRestore() {
        // Fail closed: an unrecognised value (future mode, corrupt prefs) must
        // not decide navigation — the dispatcher's own fallback handles it.
        assertFalse(coldStartRestoreAllowed(4))
        assertFalse(coldStartRestoreAllowed(-1))
        assertFalse(coldStartRestoreAllowed(Int.MIN_VALUE))
    }

    @Test
    fun modeValuesMatchTheAppearancePreferenceContract() {
        // KEY_LAUNCH_SESSION stores the tapped row's index; these numbers are
        // the wire format between the settings screen and this gate. If a row
        // is inserted, both sides move together or the gate silently shifts.
        assertEquals(0, LaunchSessionMode.AUTO)
        assertEquals(1, LaunchSessionMode.LAST_SESSION)
        assertEquals(2, LaunchSessionMode.NEW_CHAT)
        assertEquals(3, LaunchSessionMode.SAFE_START)
    }
}
