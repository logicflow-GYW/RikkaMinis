package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-worker-key-intent-handoff] Locks the two decisions this fix rests on:
 *
 *  1. a key carried on the dispatch Intent wins over the worker's cached
 *     prefs value (the cached map is frozen at process birth, so it can hold
 *     the quota-exhausted key the user just replaced);
 *  2. "this request carries a key" is exactly the condition that suppresses
 *     the legacy freshness abort — if that predicate drifted, a carried
 *     request would still poison the worker and its siblings would pay the
 *     process handoff, i.e. the multi-session failure returns.
 *
 * Expectations are literals on purpose (a test that derives them from the
 * implementation cannot catch a mutated implementation).
 */
class WorkerKeyHandoffTest {

    @Test
    fun `carried key wins over the cached prefs value`() {
        assertEquals("sk-new", WorkerKeyHandoff.pickKey("sk-new") { "sk-old" })
    }

    @Test
    fun `the prefs reader is not even consulted when a key is carried`() {
        var consulted = 0
        val picked = WorkerKeyHandoff.pickKey("sk-new") {
            consulted += 1
            "sk-old"
        }
        assertEquals("sk-new", picked)
        assertEquals(0, consulted)
    }

    @Test
    fun `blank or absent carried key falls back to prefs`() {
        assertEquals("sk-old", WorkerKeyHandoff.pickKey("") { "sk-old" })
        assertEquals("sk-old", WorkerKeyHandoff.pickKey(null) { "sk-old" })
        assertEquals("sk-old", WorkerKeyHandoff.pickKey("   ") { "sk-old" })
    }

    @Test
    fun `no key anywhere resolves to empty not null`() {
        assertEquals("", WorkerKeyHandoff.pickKey("") { null })
        assertEquals("", WorkerKeyHandoff.pickKey(null) { null })
        assertEquals("", WorkerKeyHandoff.pickKey(null) { "" })
    }

    /**
     * Byte-for-byte passthrough: KeyRoulette splits multi-key strings itself
     * and the missing-key check is `isEmpty()`, so the carried value must be
     * exactly what the prefs read would have returned — no trimming, no
     * splitting here.
     */
    @Test
    fun `carried value is passed through verbatim`() {
        assertEquals("k1,k2,k3", WorkerKeyHandoff.pickKey("k1,k2,k3") { "sk-old" })
        assertEquals("  sk-padded  ", WorkerKeyHandoff.pickKey("  sk-padded  ") { "sk-old" })
    }

    @Test
    fun `carriesKey is the stale-abort suppressor`() {
        assertTrue(WorkerKeyHandoff.carriesKey("sk-new"))
        assertTrue(WorkerKeyHandoff.carriesKey("k1,k2"))
        assertFalse(WorkerKeyHandoff.carriesKey(""))
        assertFalse(WorkerKeyHandoff.carriesKey("   "))
        assertFalse(WorkerKeyHandoff.carriesKey(null))
    }
}
