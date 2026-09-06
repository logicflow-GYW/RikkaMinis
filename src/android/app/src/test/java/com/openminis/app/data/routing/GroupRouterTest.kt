package com.openminis.app.data.routing

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for GroupRouter (Phase 1) — behavior snapshot of the
 * selection / fallback-ordering decisions extracted from ChatViewModel, plus
 * the pure MemberHealth semantics. Zero Android dependencies; time driven by
 * an injectable clock.
 */
class GroupRouterTest {

    // ─── helpers ───────────────────────────────────────────────────────────

    private fun member(id: String, modelId: String = "model-$id", costTier: Int? = null) = ModelEntry(
        providerInstanceId = "inst-$id",
        baseModel = LLMModel(modelId, "Model $id", "Test"),
        // ModelEntry.id is derived from uuid — must pin it so select() returns
        // the id the tests assert on (default uuid would be a random UUID).
        uuid = id,
        costTier = costTier,
    )

    private fun group(vararg ids: String, strategy: RoutingStrategy = RoutingStrategy.fallback) = ModelGroup(
        id = "g1",
        name = "Test Group",
        memberEntryIds = ids.toMutableList(),
        strategy = strategy,
    )

    private fun routerWithClock(nowMs: () -> Long = { 1000L }) = GroupRouter(clock = nowMs)

    // ─── select: fallback strategy ─────────────────────────────────────────

    @Test fun fallbackStrategy_returnsFirstMember() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        assertEquals("a", router.select(g, listOf(member("a"), member("b"), member("c"))))
    }

    @Test fun fallbackStrategy_preferredEntry_isHonoredWhenPresent() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        val members = listOf(member("a"), member("b"), member("c"))
        assertEquals("b", router.select(g, members, preferredEntryId = "b"))
    }

    @Test fun fallbackStrategy_preferredEntry_absent_fallsBackToFirst() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        // preferred id no longer in the (enabled) members — session proceeds on first
        assertEquals("a", router.select(g, listOf(member("a"), member("b")), preferredEntryId = "z"))
    }

    @Test fun select_emptyMembers_returnsNull() {
        val router = routerWithClock()
        assertNull(router.select(group("a"), emptyList()))
    }

    // ─── select: loadBalance rotation ──────────────────────────────────────

    @Test fun loadBalance_rotatesOneStepPastStickyAnchor() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        val members = listOf(member("a"), member("b"), member("c"))
        // anchor b -> next is c
        assertEquals("c", router.select(g, members, stickyEntryId = "b"))
        // anchor c -> next is a (wraps)
        assertEquals("a", router.select(g, members, stickyEntryId = "c"))
    }

    @Test fun loadBalance_noAnchor_startsAtFirst() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        // stickyEntryId null -> indexOfFirst = -1 -> (-1 + 1) % 3 = 0
        assertEquals("a", router.select(g, listOf(member("a"), member("b"), member("c"))))
    }

    @Test fun loadBalance_anchorNotInMembers_startsAtFirst() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        val members = listOf(member("a"), member("b"), member("c"))
        assertEquals("a", router.select(g, members, stickyEntryId = "ghost"))
    }

    @Test fun loadBalance_preferredEntry_takesPrecedence() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        val members = listOf(member("a"), member("b"), member("c"))
        // explicit pick wins over rotation
        assertEquals("b", router.select(g, members, preferredEntryId = "b", stickyEntryId = "a"))
    }

    // ─── fallbackOrder ─────────────────────────────────────────────────────

    @Test fun fallbackOrder_startsAfterActiveEntry_cycles() {
        val router = routerWithClock()
        val g = group("a", "b", "c", "d")
        assertEquals(
            listOf("c", "d", "a"),
            router.fallbackOrder(g, activeEntryId = "b", primaryModelId = "m", modelIdOf = { null }),
        )
    }

    @Test fun fallbackOrder_activeAtEnd_wrapsToStart() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        assertEquals(
            listOf("a", "b"),
            router.fallbackOrder(g, activeEntryId = "c", primaryModelId = "m", modelIdOf = { null }),
        )
    }

    @Test fun fallbackOrder_activeNotInGroup_modelIdMatchAnchors() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        // active entry "x" not in group; modelId "model-b" matches entry b
        val modelIdOf: (String) -> String? = { id -> "model-$id" }
        assertEquals(
            listOf("c", "a"),
            router.fallbackOrder(g, activeEntryId = "x", primaryModelId = "model-b", modelIdOf = modelIdOf),
        )
    }

    @Test fun fallbackOrder_noAnchorNoMatch_startsFromIndexOne() {
        val router = routerWithClock()
        val g = group("a", "b", "c")
        assertEquals(
            listOf("b", "c"),
            router.fallbackOrder(g, activeEntryId = null, primaryModelId = "unknown", modelIdOf = { null }),
        )
    }

    @Test fun fallbackOrder_singleMemberGroup_empty() {
        val router = routerWithClock()
        val g = group("a")
        assertTrue(router.fallbackOrder(g, activeEntryId = "a", primaryModelId = "m", modelIdOf = { null }).isEmpty())
    }

    // ─── health transitions (Phase 2: recordResult demotes/promotes) ──────

    @Test fun noHealthRecorded_alwaysUsable() {
        val router = routerWithClock()
        assertTrue(router.isUsable("any-entry"))
        assertEquals(MemberHealth.Healthy, router.healthOf("any-entry"))
    }

    @Test fun rateLimited_recorded_becomesCoolingWithRetryAfter() {
        var now = 1000L
        val router = routerWithClock { now }
        router.recordResult("a", RouteOutcome.RateLimited(retryAfterMs = 300_000))
        assertEquals(MemberHealth.Cooling(untilMs = 301_000L), router.healthOf("a"))
        assertFalse(router.isUsable("a"))
        now = 301_000L
        assertTrue(router.isUsable("a"))          // cooldown expired -> auto recovery
    }

    @Test fun rateLimited_withoutRetryAfter_usesDefaultCooldown() {
        var now = 1000L
        val router = routerWithClock { now }
        router.recordResult("a", RouteOutcome.RateLimited(retryAfterMs = null))
        assertEquals(
            MemberHealth.Cooling(untilMs = 1000L + GroupRouter.RATE_LIMIT_COOLDOWN_DEFAULT_MS),
            router.healthOf("a"),
        )
    }

    @Test fun serverErrors_countTowardCircuit_thenOpen() {
        var now = 1000L
        val router = routerWithClock { now }
        router.recordResult("a", RouteOutcome.ServerError)   // 1
        assertTrue(router.isUsable("a"))                     // below threshold, still usable
        router.recordResult("a", RouteOutcome.ServerError)   // 2
        assertTrue(router.isUsable("a"))
        router.recordResult("a", RouteOutcome.ServerError)   // 3 -> circuit opens
        assertFalse(router.isUsable("a"))
        assertEquals(
            MemberHealth.OpenCircuit(untilMs = 1000L + GroupRouter.CIRCUIT_OPEN_MS, failures = 3),
            router.healthOf("a"),
        )
        now += GroupRouter.CIRCUIT_OPEN_MS
        assertTrue(router.isUsable("a"))                     // circuit closed -> half-open probe eligible
    }

    @Test fun authError_isDeadUntilCleared() {
        var now = 1000L
        val router = routerWithClock { now }
        router.recordResult("a", RouteOutcome.AuthError)
        assertEquals(MemberHealth.Dead, router.healthOf("a"))
        assertFalse(router.isUsable("a"))
        now = Long.MAX_VALUE / 2
        assertFalse(router.isUsable("a"))                    // Dead never expires on its own
        router.clearHealth()
        assertTrue(router.isUsable("a"))                     // explicit selection / re-auth clears it
    }

    @Test fun success_clearsDemotion() {
        var now = 1000L
        val router = routerWithClock { now }
        router.recordResult("a", RouteOutcome.AuthError)
        assertFalse(router.isUsable("a"))
        router.recordResult("a", RouteOutcome.Success)       // half-open probe succeeded
        assertTrue(router.isUsable("a"))
        assertEquals(MemberHealth.Healthy, router.healthOf("a"))
    }

    @Test fun success_resetsCircuitCounter() {
        var now = 1000L
        val router = routerWithClock { now }
        router.recordResult("a", RouteOutcome.ServerError)   // 1
        router.recordResult("a", RouteOutcome.ServerError)   // 2
        router.recordResult("a", RouteOutcome.Success)       // recovery resets the count
        router.recordResult("a", RouteOutcome.ServerError)   // back to 1, not 3
        assertTrue(router.isUsable("a"))
    }

    @Test fun select_skipsUnhealthyMembers() {
        var now = 1000L
        val router = routerWithClock { now }
        val g = group("a", "b", "c")
        val members = listOf(member("a"), member("b"), member("c"))
        router.recordResult("a", RouteOutcome.RateLimited(retryAfterMs = 5000))
        // preferred "a" is cooling -> falls through to the first healthy member
        assertEquals("b", router.select(g, members, preferredEntryId = "a"))
        now += 5000L
        // cooldown expired -> "a" usable again, preferred honored once more
        assertEquals("a", router.select(g, members, preferredEntryId = "a"))
    }

    @Test fun select_allMembersUnhealthy_returnsNull() {
        var now = 1000L
        val router = routerWithClock { now }
        val g = group("a", "b")
        val members = listOf(member("a"), member("b"))
        router.recordResult("a", RouteOutcome.AuthError)
        router.recordResult("b", RouteOutcome.RateLimited(retryAfterMs = 5000))
        assertNull(router.select(g, members, preferredEntryId = "a"))
    }

    @Test fun loadBalance_rotationSkipsCoolingMember() {
        var now = 1000L
        val router = routerWithClock { now }
        val g = group("a", "b", "c", strategy = RoutingStrategy.loadBalance)
        val members = listOf(member("a"), member("b"), member("c"))
        router.recordResult("b", RouteOutcome.RateLimited(retryAfterMs = 5000))
        // sticky "a" -> rotation would land on "b", but "b" is cooling ->
        // rotates among the usable pool [a, c] instead, landing on "c"
        assertEquals("c", router.select(g, members, stickyEntryId = "a"))
    }

    @Test fun clearHealth_forgetsAllDemotions() {
        var now = 1000L
        val router = routerWithClock { now }
        router.recordResult("a", RouteOutcome.AuthError)
        router.recordResult("b", RouteOutcome.ServerError)
        router.recordResult("c", RouteOutcome.RateLimited(retryAfterMs = 5000))
        router.clearHealth()
        assertTrue(router.isUsable("a"))
        assertTrue(router.isUsable("b"))
        assertTrue(router.isUsable("c"))
        assertEquals(MemberHealth.Healthy, router.healthOf("a"))
    }

    // ─── MemberHealth.isUsable pure semantics ──────────────────────────────

    @Test fun healthy_alwaysUsable() {
        assertTrue(MemberHealth.Healthy.isUsable(0L))
        assertTrue(MemberHealth.Healthy.isUsable(Long.MAX_VALUE))
    }

    @Test fun cooling_expired_usable_notExpired_not() {
        val now = 1000L
        val cooling = MemberHealth.Cooling(untilMs = 1500L)
        assertFalse(cooling.isUsable(now))
        assertTrue(cooling.isUsable(1500L))        // boundary inclusive
        assertTrue(cooling.isUsable(2000L))        // expired -> auto recovery
    }

    @Test fun openCircuit_expired_usable() {
        val circuit = MemberHealth.OpenCircuit(untilMs = 2000L, failures = 3)
        assertFalse(circuit.isUsable(1000L))
        assertTrue(circuit.isUsable(2000L))
    }

    @Test fun dead_neverUsable() {
        assertFalse(MemberHealth.Dead.isUsable(0L))
        assertFalse(MemberHealth.Dead.isUsable(Long.MAX_VALUE))
    }

    // ─── cheapestFirst strategy (cost tier routing) ────────────────────────

    @Test fun cheapestFirst_selectsLowestCostTierFirst() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.cheapestFirst)
        val members = listOf(
            member("a", costTier = 3),   // expensive
            member("b", costTier = 0),   // free
            member("c", costTier = 1),   // cheap
        )
        assertEquals("b", router.select(g, members))
    }

    @Test fun cheapestFirst_unannotatedSortsLast() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.cheapestFirst)
        val members = listOf(
            member("a"),                 // unannotated → most expensive
            member("b", costTier = 1),
            member("c", costTier = 2),
        )
        assertEquals("b", router.select(g, members))
        // All unannotated: keeps group order.
        val allUnannotated = listOf(member("x"), member("y"))
        assertEquals("x", router.select(group("x", "y", strategy = RoutingStrategy.cheapestFirst), allUnannotated))
    }

    @Test fun cheapestFirst_preferredEntryWinsOverCost() {
        val router = routerWithClock()
        val g = group("a", "b", strategy = RoutingStrategy.cheapestFirst)
        val members = listOf(member("a", costTier = 3), member("b", costTier = 0))
        assertEquals("a", router.select(g, members, preferredEntryId = "a"))
    }

    @Test fun cheapestFirst_skipsUnhealthyMembers() {
        val router = routerWithClock()
        router.recordResult("a", RouteOutcome.AuthError) // Dead (clock = 1000L)
        val g = group("a", "b", strategy = RoutingStrategy.cheapestFirst)
        val members = listOf(member("a", costTier = 0), member("b", costTier = 2))
        assertEquals("b", router.select(g, members))
    }

    @Test fun cheapestFirst_fallbackOrderAscendsByCostAndSkipsActive() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.cheapestFirst)
        val members = listOf(
            member("a", costTier = 2),
            member("b", costTier = 0),
            member("c", costTier = 1),
        )
        val costTierOf: (String) -> Int? = { id -> members.find { it.id == id }?.costTier }
        // Active member "a" failed → fallback should try cheapest non-active first.
        val order = router.fallbackOrder(g, activeEntryId = "a", primaryModelId = "model-a", modelIdOf = { it }, costTierOf = costTierOf)
        assertEquals(listOf("b", "c"), order)
        // Active member "b" failed → try "c" (1) then "a" (2).
        val order2 = router.fallbackOrder(g, activeEntryId = "b", primaryModelId = "model-b", modelIdOf = { it }, costTierOf = costTierOf)
        assertEquals(listOf("c", "a"), order2)
    }

    @Test fun cheapestFirst_fallbackOrderUnannotatedLast() {
        val router = routerWithClock()
        val g = group("a", "b", "c", strategy = RoutingStrategy.cheapestFirst)
        val members = listOf(member("a", costTier = 1), member("b"), member("c", costTier = 0))
        val costTierOf: (String) -> Int? = { id -> members.find { it.id == id }?.costTier }
        val order = router.fallbackOrder(g, activeEntryId = "b", primaryModelId = "model-b", modelIdOf = { it }, costTierOf = costTierOf)
        assertEquals(listOf("c", "a"), order)
    }

    // ─── nextLoadBalanceMember: per-message rotation ──────────────────────

    private fun lbGroup(vararg ids: String) = group(*ids, strategy = RoutingStrategy.loadBalance)

    @Test fun perMessage_advancesOneStepPastCurrent() {
        val router = routerWithClock()
        val g = lbGroup("a", "b", "c")
        val members = listOf(member("a"), member("b"), member("c"))
        assertEquals("b", router.nextLoadBalanceMember(g, "a", null, members))
        assertEquals("c", router.nextLoadBalanceMember(g, "b", null, members))
    }

    @Test fun perMessage_wrapsAroundAtEnd() {
        val router = routerWithClock()
        val g = lbGroup("a", "b", "c")
        assertEquals("a", router.nextLoadBalanceMember(g, "c", null, listOf(member("a"), member("b"), member("c"))))
    }

    @Test fun perMessage_notLoadBalance_returnsNull() {
        val router = routerWithClock()
        val g = group("a", "b") // fallback strategy
        assertNull(router.nextLoadBalanceMember(g, "a", null, listOf(member("a"), member("b"))))
    }

    @Test fun perMessage_singleMember_returnsNull() {
        val router = routerWithClock()
        val g = lbGroup("a")
        assertNull(router.nextLoadBalanceMember(g, "a", null, listOf(member("a"))))
    }

    @Test fun perMessage_currentNotInGroup_jumpsToFirst() {
        val router = routerWithClock()
        val g = lbGroup("a", "b")
        // Restored old session anchored outside the group (or null) → the
        // rotation can't advance from an unknown anchor; land on the first
        // usable member rather than staying put on a possibly-stale entry.
        assertEquals("a", router.nextLoadBalanceMember(g, "z", null, listOf(member("a"), member("b"))))
        assertEquals("a", router.nextLoadBalanceMember(g, null, null, listOf(member("a"), member("b"))))
    }

    @Test fun perMessage_pendingPickWins() {
        val router = routerWithClock()
        val g = lbGroup("a", "b", "c")
        // User hand-picked "c" for the next turn — it wins over rotation.
        assertEquals("c", router.nextLoadBalanceMember(g, "a", "c", listOf(member("a"), member("b"), member("c"))))
    }

    @Test fun perMessage_pendingPickIgnoredWhenUnusable() {
        val router = routerWithClock()
        val g = lbGroup("a", "b", "c")
        // Pick's provider went cooling — fall back to normal rotation.
        router.recordResult("c", com.openminis.app.data.routing.RouteOutcome.RateLimited(retryAfterMs = 60_000L))
        assertEquals("b", router.nextLoadBalanceMember(g, "a", "c", listOf(member("a"), member("b"), member("c"))))
    }

    @Test fun perMessage_skipsCoolingMember() {
        val router = routerWithClock()
        val g = lbGroup("a", "b", "c")
        router.recordResult("b", com.openminis.app.data.routing.RouteOutcome.RateLimited(retryAfterMs = 60_000L))
        // a → next would be b, but b is cooling → skip to c.
        assertEquals("c", router.nextLoadBalanceMember(g, "a", null, listOf(member("a"), member("b"), member("c"))))
    }

    @Test fun perMessage_allOthersCooling_staysOnCurrent() {
        val router = routerWithClock()
        val g = lbGroup("a", "b", "c")
        router.recordResult("b", com.openminis.app.data.routing.RouteOutcome.RateLimited(retryAfterMs = 60_000L))
        router.recordResult("c", com.openminis.app.data.routing.RouteOutcome.RateLimited(retryAfterMs = 60_000L))
        // b and c are cooling → only a usable → rotation lands back on a
        // (wrap-around within the usable set, no change).
        assertEquals("a", router.nextLoadBalanceMember(g, "a", null, listOf(member("a"), member("b"), member("c"))))
    }

    @Test fun perMessage_currentCooling_jumpsToFirstUsable() {
        val router = routerWithClock()
        val g = lbGroup("a", "b", "c")
        router.recordResult("a", com.openminis.app.data.routing.RouteOutcome.RateLimited(retryAfterMs = 60_000L))
        // Current member demoted → don't resend into it; lead with the first usable.
        assertEquals("b", router.nextLoadBalanceMember(g, "a", null, listOf(member("a"), member("b"), member("c"))))
    }
}
