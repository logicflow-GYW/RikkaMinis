package com.rikkaminis.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
* JVM unit tests for [OfflineRetryHold] (absorb-network-pack).
 */
class OfflineRetryHoldTest {

    @Test
    fun shouldHold_offlineWithinWindow_holds() {
        assertTrue(OfflineRetryHold.shouldHold(offlineNow = true, heldMs = 0L))
        assertTrue(OfflineRetryHold.shouldHold(offlineNow = true, heldMs = 89_999L))
    }

    @Test
    fun shouldHold_offlineBeyondMaxHold_releases() {
        // The 90s ceiling: a wedged network state must not wedge the loop forever.
        assertFalse(OfflineRetryHold.shouldHold(offlineNow = true, heldMs = OfflineRetryHold.OFFLINE_MAX_HOLD_MS))
        assertFalse(OfflineRetryHold.shouldHold(offlineNow = true, heldMs = 91_000L))
    }

    @Test
    fun shouldHold_online_neverHolds() {
        assertFalse(OfflineRetryHold.shouldHold(offlineNow = false, heldMs = 0L))
        assertFalse(OfflineRetryHold.shouldHold(offlineNow = false, heldMs = 500_000L))
    }

    @Test
    fun awaitConnected_probeRecovers_exitsBeforeMaxHold() = runBlocking {
        // Probe reports offline for the first 2 polls, then online — the
        // helper must exit right after recovery (~1s, far below the 90s cap).
        var polls = 0
        OfflineRetryHold.awaitConnected { polls++ < 2 }
        // First call (poll 0) decides hold, second (poll 1) decides hold,
        // third (poll 2) exits — exactly 3 probe calls.
        assertEquals(3, polls)
    }

    @Test
    fun awaitConnected_neverOffline_returnsImmediately() = runBlocking {
        var polls = 0
        OfflineRetryHold.awaitConnected { polls++; false }
        assertEquals(1, polls)
    }
}
