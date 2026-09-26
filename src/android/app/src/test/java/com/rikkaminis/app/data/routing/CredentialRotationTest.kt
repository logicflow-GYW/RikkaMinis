package com.rikkaminis.app.data.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [GroupRouter]'s credential rotation (T-multi-api-key) —
 * behavior snapshot of the rung that spends the next key before it gives up on
 * the instance. Zero Android dependencies; time driven by an injectable clock.
 *
 * The assertions pin the decisions that make the feature a pure superset of
 * single-key routing:
 *
 *  1. keyCount <= 1 → rotation is a no-op (never returns an index, never
 *     records memory, never changes the composite route id).
 *  2. A spent key is parked ALONE (composite route id), never with its
 *     siblings — the exact bug that made "one dead key" mean "dead provider".
 *  3. Rotation skips demoted slots rather than picking them round-robin.
 *  4. Sticky memory is re-validated at use time (stale index after deletion
 *     degrades to the first usable slot, never addresses a missing slot).
 *  5. Terminal states (Exhausted) never auto-recover, unlike Cooling.
 */
class CredentialRotationTest {

    private var nowMs: Long = 1_000_000L
    private fun router() = GroupRouter(clock = { nowMs })

    // ─── 1. single-key instances are untouched ─────────────────────────────

    @Test
    fun singleKeyRotationIsNoop() {
        val r = router()
        assertNull("keyCount 1 must never rotate", r.rotateCredential("e", 0, 1))
        assertNull("keyCount 0 must never rotate", r.rotateCredential("e", 0, 0))
        assertEquals(
            "single-key preferredCredential is always 0",
            0, r.preferredCredential("e", 1),
        )
        assertEquals(
            "single-key credentialOrder is always [0]",
            listOf(0), r.credentialOrder("e", 1),
        )
        assertTrue("single-key isEntryUsable with no health record", r.isEntryUsable("e", 1))
    }

    @Test
    fun compositeRouteIdDegeneratesForSlotZero() {
        val r = router()
        assertEquals("slot 0 keeps the bare entry id", "entry", r.routeId("entry", 0))
        assertEquals("slot 2 gets the composite id", "entry#2", r.routeId("entry", 2))
    }

    // ─── 2. a spent key parks ALONE ────────────────────────────────────────

    @Test
    fun quotaExhaustedParksOnlyThatSlot() {
        val r = router()
        r.recordResult(r.routeId("e", 1), RouteOutcome.QuotaExhausted)
        assertTrue("slot 0 still usable", r.isEntryUsable("e", 2))
        assertFalse("slot 1 parked (exhausted)", r.isUsable(r.routeId("e", 1)))
        assertTrue(
            "the entry as a whole is NOT parked by one spent slot",
            r.isEntryUsable("e", 2),
        )
    }

    @Test
    fun rateLimitedParksSlotWithCooldown_notWholeEntry() {
        val r = router()
        r.recordResult(r.routeId("e", 2), RouteOutcome.RateLimited(retryAfterMs = 5_000))
        // slot 0 and 1 are still usable, so the entry as a whole is usable.
        assertTrue("entry usable while one slot cools", r.isEntryUsable("e", 3))
        assertFalse("cooled slot not usable yet", r.isUsable(r.routeId("e", 2)))
        nowMs += 6_000
        assertTrue("cooldown expired → slot usable again", r.isUsable(r.routeId("e", 2)))
    }

    @Test
    fun quotaExhaustedNeverAutoRecovers_unlikeCooling() {
        val r = router()
        r.recordResult(r.routeId("e", 1), RouteOutcome.QuotaExhausted)
        r.recordResult(r.routeId("f", 1), RouteOutcome.RateLimited(retryAfterMs = 1_000))
        nowMs += 60 * 60 * 1000L // an hour later
        assertTrue("cooling slot recovered", r.isUsable(r.routeId("f", 1)))
        assertFalse("exhausted slot did NOT recover", r.isUsable(r.routeId("e", 1)))
    }

    // ─── 3. rotation skips demoted slots ───────────────────────────────────

    @Test
    fun rotatePicksNextUsableSlot_skippingDemoted() {
        val r = router()
        r.recordResult(r.routeId("e", 1), RouteOutcome.QuotaExhausted)
        assertEquals(
            "slot 1 spent → rotation picks 2, not the round-robin 1",
            2, r.rotateCredential("e", 0, 3),
        )
    }

    @Test
    fun rotateReturnsNullWhenLaterSlotsAlsoSpent() {
        val r = router()
        r.recordResult(r.routeId("e", 1), RouteOutcome.QuotaExhausted)
        r.recordResult(r.routeId("e", 2), RouteOutcome.QuotaExhausted)
        // Slot 0 just failed; the only siblings (1, 2) are spent → rotation is
        // exhausted. Retrying slot 0 is the engine's AUTO_RETRY rung, not
        // rotation's, so rotation hands the failure back instead of looping.
        assertNull("no usable sibling → rotation exhausted", r.rotateCredential("e", 0, 3))
    }

    @Test
    fun rotateReturnsNullWhenEverySlotIsDemoted() {
        val r = router()
        for (i in 0 until 3) r.recordResult(r.routeId("e", i), RouteOutcome.QuotaExhausted)
        assertNull("no usable sibling → exhausted", r.rotateCredential("e", 0, 3))
    }

    @Test
    fun rotateFromMiddleSlot_startsAfterIt() {
        val r = router()
        assertEquals(
            "rotation from slot 1 starts at 2",
            2, r.rotateCredential("e", 1, 3),
        )
    }

    // ─── 4. sticky memory is re-validated at use time ──────────────────────

    @Test
    fun stickyMemorySurvivesRestart_andIsHonoured() {
        val r = router()
        r.rememberCredential("e", 2, 3)
        val persisted = r.stickyKeysSnapshot()
        // A fresh router (simulated app restart) adopts the persisted map.
        val r2 = router().also { it.restoreStickyKeys(persisted) }
        assertEquals(
            "remembered index wins over the default",
            2, r2.preferredCredential("e", 3),
        )
    }

    @Test
    fun stickyMemoryDegradesWhenIndexOutOfRange() {
        val r = router()
        r.rememberCredential("e", 2, 3)
        val r2 = router().also { it.restoreStickyKeys(r.stickyKeysSnapshot()) }
        // The user deleted key #2 after the memory was written.
        assertEquals(
            "out-of-range memory degrades to slot 0, never addresses a missing slot",
            0, r2.preferredCredential("e", 2),
        )
    }

    @Test
    fun stickyMemoryIsSkippedWhenRememberedSlotIsCooling() {
        val r = router()
        r.rememberCredential("e", 1, 3)
        r.recordResult(r.routeId("e", 1), RouteOutcome.RateLimited(retryAfterMs = 5_000))
        assertEquals(
            "remembered slot cooling → first usable sibling wins",
            0, r.preferredCredential("e", 3),
        )
    }

    @Test
    fun stickyMemoryMigration_honouredAfterCooldownExpires() {
        val r = router()
        r.rememberCredential("e", 1, 3)
        r.recordResult(r.routeId("e", 1), RouteOutcome.RateLimited(retryAfterMs = 5_000))
        nowMs += 6_000
        assertEquals(
            "cooling expired → memory honoured again",
            1, r.preferredCredential("e", 3),
        )
    }

    @Test
    fun singleKeyRecordsNoMemory() {
        val r = router()
        r.rememberCredential("e", 0, 1)
        assertTrue(
            "single-key memory is a no-op (index 0 is the only choice)",
            r.stickyKeysSnapshot().isEmpty(),
        )
    }

    // ─── 5. credentialOrder: usable first, demoted as last-resort probes ───

    @Test
    fun credentialOrderPutsUsableFirst() {
        val r = router()
        r.recordResult(r.routeId("e", 1), RouteOutcome.QuotaExhausted)
        val order = r.credentialOrder("e", 3)
        assertEquals("3 slots, all present (demoted kept as probes)", 3, order.size)
        assertEquals("usable slot first", 0, order.first())
        assertTrue("demoted slot kept as last resort", order.contains(1))
    }

    @Test
    fun credentialOrderRotatesStart() {
        val r = router()
        r.rememberCredential("e", 2, 3)
        assertEquals(
            "order starts at the remembered slot when it is usable",
            2, r.credentialOrder("e", 3, fromIndex = 2).first(),
        )
    }

    // ─── 6. health-map capacity under multi-key ────────────────────────────

    @Test
    fun capacityEvictsExpiredFirst_neverLegitimatelyCooling() {
        val r = router()
        // Fill to the cap, then park one slot with a short cooldown and one
        // with an exhausted (terminal) state; the rest stay live.
        for (i in 0 until GroupRouter.MAX_HEALTH_ENTRIES - 2) {
            r.recordResult("filler-$i", RouteOutcome.ServerError)
        }
        r.recordResult("cooling-slot", RouteOutcome.RateLimited(retryAfterMs = 60_000))
        r.recordResult("dead-slot", RouteOutcome.AuthError)
        // One more record pushes over the cap.
        r.recordResult("one-more", RouteOutcome.ServerError)
        assertTrue(
            "legitimately cooling slot must survive eviction",
            r.healthContains("cooling-slot"),
        )
        assertTrue(
            "terminal record must survive eviction",
            r.healthContains("dead-slot"),
        )
    }

    @Test
    fun expiredCooldownIsEvictable() {
        val r = router()
        for (i in 0 until GroupRouter.MAX_HEALTH_ENTRIES) {
            r.recordResult("filler-$i", RouteOutcome.ServerError)
        }
        r.recordResult("old-cooling", RouteOutcome.RateLimited(retryAfterMs = 1_000))
        nowMs += 2_000 // cooldown lapsed — the record carries no information
        // One more record pushes over the cap again; pass 1 (expired demotions)
        // must pick the lapsed cooldown, not a live filler.
        r.recordResult("new-0", RouteOutcome.ServerError)
        assertFalse(
            "expired cooldown may be evicted (fresh request re-discovers it)",
            r.healthContains("old-cooling"),
        )
        assertTrue(
            "live filler must survive pass 1",
            r.healthContains("filler-1"),
        )
    }
}
