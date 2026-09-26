package com.rikkaminis.app.network

import kotlinx.coroutines.delay

/**
 * [P0-2-offline-retry-hold] Wait helper for auto-retry loops: pauses the
 * countdown while the device is offline so attempts are NOT burned into a
 * dead network. Ported from Filterrr/RikkaMinis @ 887a7c6 (network pack),
 * package rename only — the semantics are unchanged.
 *
 * The problem it fixes: AgentLoopEngine's retry ladder kept ticking
 * while Wi-Fi→cellular swapped or the user walked into an elevator — all
 * attempts fired into the void, and by the time connectivity returned
 * the user was staring at a hard failure instead of a recovering stream.
 *
 * Semantics (decided here so tests can drive it with a fake network state):
 *   - Each countdown second first waits for connectivity (when
 *     [isOffline] says the network is down — polling up to
 *     [OFFLINE_MAX_HOLD_MS]) BEFORE consuming the second.
 *   - While held, the caller's visible countdown does not advance (the
 *     caller simply doesn't get control back; its countdown state keeps
 *     showing the current second).
 *   - If connectivity never returns inside the max hold, the hold expires
 *     anyway so a wedged NetworkMonitor state can't wedge a stream forever
 *     — the retry proceeds and fails through the normal path (which keeps
 *     fallback providers reachable).
 *
 * Pure-decision core ([shouldHold]) is JVM-testable; [awaitConnected] is the
 * thin suspend wrapper around it.
 */
object OfflineRetryHold {

    /** Upper bound on one offline hold, millis. 90s: covers most Wi-Fi↔cell
     *  swaps and tunnel re-keys; beyond that a manual retry is healthier. */
    const val OFFLINE_MAX_HOLD_MS = 90_000L

    /** Poll cadence while waiting for connectivity. */
    const val POLL_INTERVAL_MS = 500L

    /**
     * Pure decision: should the countdown hold this tick?
     * @param offlineNow current connectivity (true = no network)
     * @param heldMs     how long THIS hold has already waited
     */
    fun shouldHold(offlineNow: Boolean, heldMs: Long): Boolean =
        offlineNow && heldMs < OFFLINE_MAX_HOLD_MS

    /**
     * Suspend until the network is back or [OFFLINE_MAX_HOLD_MS] elapsed.
     * @param isOffline suspend-free probe of current connectivity
     */
    suspend fun awaitConnected(isOffline: () -> Boolean) {
        var heldMs = 0L
        while (shouldHold(isOffline(), heldMs)) {
            delay(POLL_INTERVAL_MS)
            heldMs += POLL_INTERVAL_MS
        }
    }
}
