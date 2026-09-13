package com.rikkaminis.app.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [AppMemoryGovernor] — the policy that turns the 1s RSS sampler
 * into action when the APP is fat but the SYSTEM is not under pressure (which
 * is why `onTrimMemory` never fired during the 2026-09-13 16:54 spike:
 * 232MB → 1762MB with ~6GB free).
 */
class AppMemoryGovernorTest {

    @Before
    fun setUp() {
        AppMemoryGovernor.resetForTest()
    }

    @After
    fun tearDown() {
        AppMemoryGovernor.dropCachesHook = {}
        AppMemoryGovernor.gcHook = {}
        AppMemoryGovernor.observer = { _, _ -> }
        AppMemoryGovernor.resetForTest()
    }

    // ── the pure decision ─────────────────────────────────────────────

    @Test
    fun `a healthy reading never acts and resets the counter`() {
        val d = internalGovernorTick(
            anonMb = 300, consecutiveHigh = 4, lastReclaimAtMs = 0,
            nowMs = 1_000, toolRunning = false,
        )
        assertEquals(0, d.consecutiveHigh)
        assertEquals(AppMemoryGovernor.Action.NONE, d.action)
    }

    @Test
    fun `acts only after the sustained window`() {
        var counter = 0
        var actions = 0
        for (tick in 1..4) {
            val d = internalGovernorTick(
                anonMb = 900, consecutiveHigh = counter, lastReclaimAtMs = 0,
                nowMs = tick * 1_000L, toolRunning = false,
            )
            counter = d.consecutiveHigh
            if (d.action != AppMemoryGovernor.Action.NONE) actions++
        }
        // 4 consecutive high ticks < 5 → still nothing (one transient read must
        // never shed caches).
        assertEquals(4, counter)
        assertEquals(0, actions)
        val fifth = internalGovernorTick(
            anonMb = 900, consecutiveHigh = counter, lastReclaimAtMs = 0,
            nowMs = 5_000, toolRunning = false,
        )
        assertEquals(AppMemoryGovernor.Action.DROP_CACHES_AND_GC, fifth.action)
        assertEquals(0, fifth.consecutiveHigh) // counter restarts after acting
    }

    @Test
    fun `a dip below the threshold breaks the streak`() {
        var counter = 0
        for (tick in 1..4) {
            counter = internalGovernorTick(
                anonMb = 900, consecutiveHigh = counter, lastReclaimAtMs = 0,
                nowMs = tick * 1_000L, toolRunning = false,
            ).consecutiveHigh
        }
        counter = internalGovernorTick(
            anonMb = 400, consecutiveHigh = counter, lastReclaimAtMs = 0,
            nowMs = 5_000, toolRunning = false,
        ).consecutiveHigh
        assertEquals(0, counter)
    }

    @Test
    fun `an in-flight tool drops caches but skips the synchronous gc`() {
        val d = internalGovernorTick(
            anonMb = 900, consecutiveHigh = AppMemoryGovernor.SUSTAINED_TICKS - 1, lastReclaimAtMs = 0,
            nowMs = 90_000, toolRunning = true,
        )
        assertEquals(AppMemoryGovernor.Action.DROP_CACHES, d.action)
    }

    @Test
    fun `the cooldown blocks a second action`() {
        val d = internalGovernorTick(
            anonMb = 900, consecutiveHigh = AppMemoryGovernor.SUSTAINED_TICKS - 1,
            lastReclaimAtMs = 100_000, nowMs = 100_000 + AppMemoryGovernor.COOLDOWN_MS - 1,
            toolRunning = false,
        )
        assertEquals(AppMemoryGovernor.Action.NONE, d.action)
    }

    @Test
    fun `past the cooldown it acts again`() {
        val d = internalGovernorTick(
            anonMb = 900, consecutiveHigh = AppMemoryGovernor.SUSTAINED_TICKS - 1,
            lastReclaimAtMs = 100_000, nowMs = 100_000 + AppMemoryGovernor.COOLDOWN_MS,
            toolRunning = false,
        )
        assertEquals(AppMemoryGovernor.Action.DROP_CACHES_AND_GC, d.action)
    }

    // ── the stateful wrapper ──────────────────────────────────────────

    @Test
    fun `tick fires the hooks once the pressure is sustained`() {
        var drops = 0
        var gcs = 0
        val seen = mutableListOf<AppMemoryGovernor.Action>()
        AppMemoryGovernor.dropCachesHook = { drops++ }
        AppMemoryGovernor.gcHook = { gcs++ }
        AppMemoryGovernor.observer = { action, _ -> seen.add(action) }

        var now = 0L
        repeat(AppMemoryGovernor.SUSTAINED_TICKS.toInt() - 1) {
            now += 1_000
            AppMemoryGovernor.tick(anonMb = 1_200, nowMs = now, toolRunning = false)
        }
        assertEquals(0, drops)
        assertEquals(0, gcs)

        now += 1_000
        AppMemoryGovernor.tick(anonMb = 1_200, nowMs = now, toolRunning = false)
        assertEquals(1, drops)
        assertEquals(1, gcs)
        assertEquals(listOf(AppMemoryGovernor.Action.DROP_CACHES_AND_GC), seen)

        // Immediately re-firing is blocked by the cooldown even though the
        // reading is still high.
        AppMemoryGovernor.tick(anonMb = 1_200, nowMs = now, toolRunning = false)
        assertEquals(1, drops)
    }

    @Test
    fun `tick stays silent while the app is healthy`() {
        var drops = 0
        AppMemoryGovernor.dropCachesHook = { drops++ }
        var now = 0L
        repeat(600) { // ten minutes of healthy 1s ticks
            now += 1_000
            AppMemoryGovernor.tick(anonMb = 260, nowMs = now, toolRunning = false)
        }
        assertEquals(0, drops)
    }

    @Test
    fun `a throwing hook does not break the loop`() {
        AppMemoryGovernor.dropCachesHook = { throw IllegalStateException("boom") }
        AppMemoryGovernor.gcHook = { throw IllegalStateException("boom") }
        var now = 0L
        repeat(AppMemoryGovernor.SUSTAINED_TICKS.toInt()) {
            now += 1_000
            AppMemoryGovernor.tick(anonMb = 1_500, nowMs = now, toolRunning = false)
        }
        assertTrue(true) // reached without throwing
    }
}
