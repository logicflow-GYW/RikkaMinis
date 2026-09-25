package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the pure decision half of [ForceHomeTrace]
 * (reasons text + any-breaker gate). The Android-side logging methods
 * (resolverMode / guardBlocked) are exercised by CI's full suite +
 * on-device verification; these tests pin the pure inputs→outputs
 * contract that both call sites share.
 */
class ForceHomeTraceTest {

    @Test
    fun forced_returnsTrue_whenAnyBreakerFires() {
        assertTrue(ForceHomeTrace.forced(hang = true, crash = false, beacon = false))
        assertTrue(ForceHomeTrace.forced(hang = false, crash = true, beacon = false))
        assertTrue(ForceHomeTrace.forced(hang = false, crash = false, beacon = true))
        assertTrue(ForceHomeTrace.forced(hang = true, crash = true, beacon = true))
    }

    @Test
    fun forced_returnsFalse_whenNoBreakerFires() {
        assertFalse(ForceHomeTrace.forced(hang = false, crash = false, beacon = false))
    }

    @Test
    fun reasons_empty_whenNoBreakerFires() {
        assertEquals("", ForceHomeTrace.reasons(hang = false, crash = false, beacon = false, hangCount = 0, restartCount = 0))
    }

    @Test
    fun reasons_hangCarriesLiveCount() {
        val r = ForceHomeTrace.reasons(hang = true, crash = false, beacon = false, hangCount = 3, restartCount = 0)
        assertEquals("hangBreaker=yes hangCount=3", r)
    }

    @Test
    fun reasons_crashCarriesWindowMarker() {
        val r = ForceHomeTrace.reasons(hang = false, crash = true, beacon = false, hangCount = 0, restartCount = 0)
        assertEquals("crashWindow=yes", r)
    }

    @Test
    fun reasons_beaconCarriesRestartCount() {
        val r = ForceHomeTrace.reasons(hang = false, crash = false, beacon = true, hangCount = 0, restartCount = 4)
        assertEquals("beaconBreaker=yes restartCount=4", r)
    }

    @Test
    fun reasons_allFired_joinsAllThree() {
        val r = ForceHomeTrace.reasons(hang = true, crash = true, beacon = true, hangCount = 3, restartCount = 4)
        assertEquals("hangBreaker=yes hangCount=3 crashWindow=yes beaconBreaker=yes restartCount=4", r)
    }

    @Test
    fun reasons_twoFired_noTrailingWhitespace() {
        val r = ForceHomeTrace.reasons(hang = true, crash = false, beacon = true, hangCount = 1, restartCount = 2)
        assertEquals("hangBreaker=yes hangCount=1 beaconBreaker=yes restartCount=2", r)
        assertFalse("no trailing space", r.endsWith(" "))
    }
}
