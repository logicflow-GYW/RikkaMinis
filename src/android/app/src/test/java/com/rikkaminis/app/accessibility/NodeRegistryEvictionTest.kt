package com.rikkaminis.app.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audit3-F-12] Pins the eviction policy that replaced `put()`'s unconditional
 * full-map `evictExpired()`.
 *
 * ## What the old shape cost
 *
 * `walkNode` registers EVERY node of the accessibility tree, so a dump of N
 * nodes ran N sweeps of a map that grew to N — sum(1..N) = O(N^2) iterator
 * steps, and repeated dumps inside one 60 s TTL window stacked M live entries
 * under every one of them. There was no ceiling at all, and every retained
 * `AccessibilityNodeInfo` pins the framework node cache behind it.
 *
 * ## What is pinned here
 *
 * 1. [selectEvictions] — the pure policy, with literal expectations. Expired
 *    entries go (boundary: exactly `ttlMs` old is still LIVE, because the
 *    comparison is `>`); over-capacity survivors go oldest-first by INSERTION
 *    ORDINAL, not by timestamp (a 500-node tree shares one millisecond, and a
 *    timestamp sort would evict an arbitrary subset of a tree instead of whole
 *    trees).
 * 2. [shouldSweep] — the amortising gate.
 * 3. The source shape of `put()`: the sweep must sit behind [shouldSweep], and
 *    the [audit-0917] id-wrap guard must survive. Those are asserted on the
 *    production text (the pre-fix regression was a *shape* change: a bare call
 *    where a guarded one belongs), each with an in-test negative control.
 *
 * `AccessibilityNodeInfo` cannot be instantiated on the JVM, so the registry
 * class itself is exercised through the two pure functions it delegates to —
 * which is exactly the boundary the fix introduced.
 */
class NodeRegistryEvictionTest {

    private companion object {
        const val TTL = 60_000L

        /** Far above any test list, so only the TTL rule can fire. */
        const val NO_CAP = 1_000_000

        fun meta(id: String, createdAt: Long, ordinal: Long) = RegistryEntryMeta(id, createdAt, ordinal)
    }

    // ── TTL ──────────────────────────────────────────────────────────────

    @Test
    fun `expired entries are dropped and live ones kept, in input order`() {
        val now = 100_000L
        val entries = listOf(
            meta("a", 0L, 0L),          // 100 000 ms old -> expired
            meta("b", 40_001L, 1L),     // 59 999 ms      -> live
            meta("c", 39_999L, 2L),     // 60 001 ms      -> expired
            meta("d", 100_000L, 3L),    // 0 ms           -> live
        )
        assertEquals(listOf("a", "c"), selectEvictions(entries, now, TTL, NO_CAP))
    }

    @Test
    fun `exactly ttl-old is still live, one millisecond more is not`() {
        val entries = listOf(
            meta("boundary", 0L, 0L),     // 60 000 - 0   = 60 000 -> live
            meta("justOver", -1L, 1L),    // 60 001       -> expired
        )
        assertEquals(listOf("justOver"), selectEvictions(entries, 60_000L, TTL, NO_CAP))
    }

    @Test
    fun `an empty registry evicts nothing`() {
        assertEquals(emptyList<String>(), selectEvictions(emptyList(), 123L, TTL, 4_096))
    }

    @Test
    fun `all entries expired means all are dropped, cap notwithstanding`() {
        val entries = listOf(
            meta("a", 0L, 0L),
            meta("b", 1L, 1L),
            meta("c", 2L, 2L),
        )
        // now - createdAt = 100 000 > 60 000 for every entry.
        assertEquals(listOf("a", "b", "c"), selectEvictions(entries, 100_000L, TTL, 1))
    }

    // ── capacity ─────────────────────────────────────────────────────────

    @Test
    fun `a live set at or under the cap evicts nothing`() {
        val entries = listOf(meta("a", 0L, 0L), meta("b", 0L, 1L), meta("c", 0L, 2L))
        assertEquals(emptyList<String>(), selectEvictions(entries, 0L, TTL, 3))
        assertEquals(emptyList<String>(), selectEvictions(entries, 0L, TTL, 4))
    }

    @Test
    fun `over the cap, the oldest insertions go first`() {
        val now = 0L
        // One millisecond for all five: the timestamp cannot order them at all,
        // which is the case a whole `walkNode` pass produces.
        val entries = listOf(
            meta("e5", now, 5L),
            meta("e1", now, 1L),
            meta("e4", now, 4L),
            meta("e2", now, 2L),
            meta("e3", now, 3L),
        )
        assertEquals(listOf("e1", "e2"), selectEvictions(entries, now, TTL, 3))
        assertEquals(listOf("e1", "e2", "e3", "e4"), selectEvictions(entries, now, TTL, 1))
    }

    @Test
    fun `capacity is applied to the survivors, not to the raw entry list`() {
        val now = 100_000L
        val entries = listOf(
            meta("old1", 0L, 0L),        // expired
            meta("old2", 1_000L, 1L),    // expired
            meta("live1", 90_000L, 2L),
            meta("live2", 91_000L, 3L),
            meta("live3", 92_000L, 4L),
            meta("live4", 93_000L, 5L),
            meta("live5", 94_000L, 6L),
        )
        // Expired first, then the oldest of the remainder: 5 live - cap 3 = 2.
        assertEquals(listOf("old1", "old2", "live1", "live2"), selectEvictions(entries, now, TTL, 3))
        // A cap that fits every survivor adds nothing to the expired set.
        assertEquals(listOf("old1", "old2"), selectEvictions(entries, now, TTL, 5))
    }

    @Test
    fun `a degenerate cap drops every survivor instead of throwing`() {
        val entries = listOf(meta("a", 0L, 0L), meta("b", 0L, 1L))
        assertEquals(listOf("a", "b"), selectEvictions(entries, 0L, TTL, 0))
        // Negative caps must behave the same rather than blow up the slice
        // arithmetic (`live.size - cap` would exceed the live list).
        assertEquals(listOf("a", "b"), selectEvictions(entries, 0L, TTL, -5))
    }

    @Test
    fun `no entry is ever reported twice`() {
        val now = 100_000L
        val entries = listOf(
            meta("a", 0L, 0L),
            meta("b", 0L, 1L),
            meta("c", 99_000L, 2L),
            meta("d", 99_500L, 3L),
        )
        val doomed = selectEvictions(entries, now, TTL, 1)
        assertEquals(doomed.size, doomed.toSet().size)
        assertEquals(listOf("a", "b", "c"), doomed)
    }

    // ── the amortising gate ──────────────────────────────────────────────

    @Test
    fun `sweep is due every sweepEveryPuts inserts`() {
        assertFalse(shouldSweep(511, 10, 512, 4_096))
        assertTrue(shouldSweep(512, 10, 512, 4_096))
        assertTrue(shouldSweep(3_000, 10, 512, 4_096))
    }

    @Test
    fun `sweep is due immediately once the map reaches the ceiling`() {
        // The counter alone would let a single tree larger than the cap sit
        // there until the next checkpoint, so size is checked every insert.
        assertTrue(shouldSweep(1, 4_096, 512, 4_096))
        assertTrue(shouldSweep(1, 5_000, 512, 4_096))
        assertFalse(shouldSweep(1, 4_095, 512, 4_096))
    }

    // ── the ceiling trigger itself ───────────────────────────────────────

    @Test
    fun `the ceiling trigger stays amortised (model of the put loop, literals)`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/accessibility/NodeRegistry.kt")
        // The model below hard-codes the three constants, so pin them in the
        // production text: if one is retuned, this test must be revisited
        // rather than silently measuring a configuration that no longer ships.
        assertTrue("SWEEP_EVERY_PUTS changed", src.contains("const val SWEEP_EVERY_PUTS = 512"))
        assertTrue("MAX_ENTRIES changed", src.contains("const val MAX_ENTRIES = 4_096"))
        assertTrue(
            "KEEP_AFTER_SWEEP changed",
            src.contains("const val KEEP_AFTER_SWEEP = MAX_ENTRIES - SWEEP_EVERY_PUTS"),
        )
        assertTrue(
            "sweep() must trim to the low-water mark, not to the ceiling",
            src.contains("selectEvictions(metas, now, TTL_MS, KEEP_AFTER_SWEEP)"),
        )

        // 12 dumps of 800 nodes, clock frozen: only the capacity path can fire.
        val shipped = putLoop(inserts = 9_600, trimTo = 3_584)
        val ceilingTrim = putLoop(inserts = 9_600, trimTo = 4_096)

        assertEquals(9_600, shipped.inserts)
        assertTrue("the ceiling must hold, peak was ${shipped.peak}", shipped.peak <= 4_096)
        assertTrue(
            "steady state left the [low-water, ceiling] band: ${shipped.finalSize}",
            shipped.finalSize in 3_584..4_096,
        )
        // One sweep per ~512 inserts of ~4k entries each: ~6 entry visits per
        // insert. Trimming to the ceiling instead re-triggers on every insert
        // and measures 22,568,311 visits for the same 9,600 — the O(N^2) this
        // fix removes, which is why the two arms are asserted to differ.
        assertTrue("amortisation lost: ${shipped.visits} visits", shipped.visits <= 100_000)
        assertTrue(
            "the ceiling-trim cut should be far worse (${ceilingTrim.visits} vs ${shipped.visits})",
            ceilingTrim.visits > 50 * shipped.visits,
        )
    }

    private data class LoopRun(val inserts: Int, val visits: Int, val peak: Int, val finalSize: Int)

    /**
     * Model of `put()`'s bookkeeping — same gate, same policy call, same order
     * (gate, then insert) — because the registry itself needs an
     * `AccessibilityNodeInfo` that cannot be built off-device. `visits` counts
     * the entries the sweep had to look at, which is the O(N^2) term.
     */
    private fun putLoop(
        inserts: Int,
        trimTo: Int,
        sweepEvery: Int = 512,
        cap: Int = 4_096,
    ): LoopRun {
        val live = ArrayList<RegistryEntryMeta>()
        var counter = 0
        var visits = 0
        var peak = 0
        var ordinal = 0L
        repeat(inserts) {
            counter++
            if (shouldSweep(counter, live.size, sweepEvery, cap)) {
                counter = 0
                visits += live.size
                val doomed = selectEvictions(live.toList(), 0L, TTL, trimTo).toHashSet()
                live.removeAll { it.id in doomed }
            }
            ordinal++
            live.add(meta("e$ordinal", 0L, ordinal))
            if (live.size > peak) peak = live.size
        }
        return LoopRun(inserts, visits, peak, live.size)
    }

    // ── production shape ─────────────────────────────────────────────────

    @Test
    fun `put() sweeps behind the amortised gate and keeps the id-wrap guard`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/accessibility/NodeRegistry.kt")
        assertPutShape(src)

        // Negative control 1: drop the gate, leaving the sweep unconditional —
        // this is the pre-fix regression, in its actual textual form.
        val ungated = src.replace(
            "if (shouldSweep(putsSinceSweep.incrementAndGet(), map.size, SWEEP_EVERY_PUTS, MAX_ENTRIES)) {",
            "",
        )
        assertProbeRejects(ungated)

        // Negative control 2: keep the gate but let the old body through.
        val unguarded = src.replace(
            "if (shouldSweep(putsSinceSweep.incrementAndGet(), map.size, SWEEP_EVERY_PUTS, MAX_ENTRIES)) {\n" +
                "            putsSinceSweep.set(0)\n" +
                "            sweep(now)\n" +
                "        }",
            "sweep(now)",
        )
        assertProbeRejects(unguarded)

        // Negative control 3: drop the [audit-0917] id-wrap probe.
        val noWrapGuard = src.replace("while (map.containsKey(id) && guard++ < 64)", "if (false) {")
        assertProbeRejects(noWrapGuard)

        // Negative control 4: stop recording the insertion ordinal, which is
        // what makes the capacity eviction oldest-first.
        val noOrdinal = src.replace("Entry(node, now, inserts.incrementAndGet())", "Entry(node, now, 0L)")
        assertProbeRejects(noOrdinal)
    }

    private fun assertPutShape(src: String) {
        val body = putBody(src)
        val gate = body.indexOf("if (shouldSweep(")
        val sweep = body.indexOf("sweep(")
        assertTrue("put() must gate its sweep behind shouldSweep", gate >= 0)
        assertTrue("put() must call sweep exactly once", sweep >= 0)
        assertEquals("exactly one sweep call in put()", 1, countOccurrences(body, "sweep("))
        assertTrue("the gate must precede the sweep", gate < sweep)
        assertTrue(
            "put() must stamp the insertion ordinal used for oldest-first eviction",
            body.contains("inserts.incrementAndGet()"),
        )
        assertTrue(
            "[audit-0917] the 2^20 id-wrap probe must survive",
            body.contains("while (map.containsKey(id) && guard++ < 64)"),
        )
    }

    private fun putBody(src: String): String {
        val start = src.indexOf("fun put(node: AccessibilityNodeInfo): String {")
        assertTrue("put() not found in NodeRegistry.kt", start >= 0)
        val end = src.indexOf("fun get(id: String)", start)
        assertTrue("get() must follow put()", end > start)
        return src.substring(start, end)
    }

    private fun assertProbeRejects(mutated: String) {
        var rejected = false
        try {
            assertPutShape(mutated)
        } catch (_: AssertionError) {
            rejected = true
        }
        assertTrue("negative control: the probe still accepts mutated source", rejected)
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            i = haystack.indexOf(needle, i + needle.length)
        }
        return count
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir; a sandbox JVM run can
     * start anywhere under the repo — no fixed depth works.
     */
    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
