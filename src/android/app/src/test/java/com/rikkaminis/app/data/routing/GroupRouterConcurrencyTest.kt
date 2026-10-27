package com.rikkaminis.app.data.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * [audit-1018 ISSUE-10] GroupRouter health-map concurrency probes.
 *
 * The router instance is app-wide (ChatViewModel declares it inside its
 * companion object), so `recordResult` (agent-loop error classification, worker
 * coroutines) runs concurrently with `isUsable` / `healthOf` reads (selection,
 * diagnostics) and with `clearHealth` (main thread, explicit user pick).
 *
 * Pre-fix the map was a bare LinkedHashMap with no synchronization at all:
 *  - the ServerError counter was an unguarded read-modify-write, so concurrent
 *    5xx reports for one member lost counts (under-counting the failures that
 *    are supposed to open the circuit);
 *  - `clearHealth` / the FIFO eviction iteration could run concurrently with an
 *    insert (structural corruption);
 *  - reads shared the map with writers (no happens-before).
 *
 * The behavioral cases below are timing-shaped by design; the deterministic
 * half of the same contract is `everyHealthAccessHappensUnderTheRouterLock`
 * (and the standalone gate scripts/scan/router_lock_check.py).
 */
class GroupRouterConcurrencyTest {

    private fun router(nowMs: Long = 1_000_000L) = GroupRouter(clock = { nowMs })

    // ── behavioral: lost updates ───────────────────────────────────────────

    /**
     * 8 threads x 500 barrier-aligned ServerError reports for ONE member.
     * The failure counter is a read-modify-write, so a dropped update shows up
     * as `failures < 4000`. The barrier lines every thread up on the same
     * window instead of hoping for a coincidental overlap.
     */
    @Test
    fun `concurrent server-error counting never loses an update`() {
        val router = router(nowMs = 1_000_000L)
        val threads = 8
        val rounds = 500
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val barrier = CyclicBarrier(threads)
        val errors = CopyOnWriteArrayList<Throwable>()

        repeat(threads) {
            Thread {
                try {
                    start.await()
                    repeat(rounds) {
                        barrier.await()
                        router.recordResult("only-member", RouteOutcome.ServerError)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue("producers did not finish", done.await(60, TimeUnit.SECONDS))
        assertTrue("workers threw: $errors", errors.isEmpty())

        // Literal expectation: 8 x 500 reports, not one of them lost.
        val final = router.healthOf("only-member")
        assertTrue("expected OpenCircuit, got $final", final is MemberHealth.OpenCircuit)
        assertEquals(4_000, (final as MemberHealth.OpenCircuit).failures)
        // Circuit actually opened: now(1_000_000) + CIRCUIT_OPEN_MS(5 min).
        assertEquals(1_300_000L, final.untilMs)
    }

    /**
     * Writers mixing all four outcomes + readers + a `clearHealth` hammer on the
     * same map. Before the fix `health.clear()` / the eviction's key iteration
     * could hit a concurrent insert and throw, and readers raced writers.
     */
    @Test
    fun `clearHealth racing writers and readers never corrupts the map`() {
        val router = router(nowMs = 500_000L)
        val errors = CopyOnWriteArrayList<Throwable>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(4 + 2 + 1)

        repeat(4) { w ->
            Thread {
                try {
                    start.await()
                    repeat(2_000) { i ->
                        val id = "m-$w-${i % 64}"
                        when (i % 4) {
                            0 -> router.recordResult(id, RouteOutcome.ServerError)
                            1 -> router.recordResult(id, RouteOutcome.RateLimited(60_000L))
                            2 -> router.recordResult(id, RouteOutcome.AuthError)
                            else -> router.recordResult(id, RouteOutcome.Success)
                        }
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        repeat(2) { r ->
            Thread {
                try {
                    start.await()
                    repeat(2_000) { i ->
                        router.isUsable("m-$r-${i % 64}")
                        router.healthOf("m-0-${i % 64}")
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        Thread {
            try {
                start.await()
                repeat(200) { router.clearHealth() }
            } catch (t: Throwable) {
                errors.add(t)
            } finally {
                done.countDown()
            }
        }.start()

        start.countDown()
        assertTrue("storm did not finish", done.await(60, TimeUnit.SECONDS))
        assertTrue("unexpected exceptions: $errors", errors.isEmpty())

        // clearHealth 是"清空全部状态"的幂等操作：清完不留残留（无 entry = healthy）。
        router.clearHealth()
        repeat(64) { i -> assertTrue("member m-0-$i must be clean", router.isUsable("m-0-$i")) }
    }

    // ── deterministic: cap / FIFO semantics survive the lock ───────────────

    /**
     * Single-threaded on purpose: the cap and its insertion-order eviction are
     * part of the routing algorithm (audit hard constraint: 不改判定算法), so
     * the fix must not silently swap FIFO for hash-order. 300 rate-limited
     * members against a 256 cap evicts exactly the oldest 44.
     */
    @Test
    fun `health cap evicts in FIFO order when over capacity`() {
        val router = router(nowMs = 10_000L)
        repeat(300) { i -> router.recordResult("member-$i", RouteOutcome.RateLimited(60_000L)) }

        repeat(44) { i ->
            assertTrue("member-$i was the oldest and must be evicted", router.isUsable("member-$i"))
        }
        for (i in 44 until 300) {
            assertFalse("member-$i must still be cooling", router.isUsable("member-$i"))
        }
        // 冷却窗口本身未被加锁改动：now(10_000) + retryAfter(60_000) = 70_000。
        assertTrue("retryAfter honoured", router.healthOf("member-299") == MemberHealth.Cooling(70_000L))
    }

    // ── deterministic: structural (near-twin of router_lock_check.py) ───────

    /**
     * Every `health` access lives inside a synchronized span. This is the
     * shared-execution half of the contract that a timing-dependent stress test
     * can only sample: it is red the moment the lock wrappers are removed.
     */
    @Test
    fun `every health access happens under the router lock`() {
        val src = codeOnly(
            readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/data/routing/GroupRouter.kt"),
        )
        assertTrue("healthLock must be declared", src.contains("healthLock = Any()"))
        val spans = synchronizedSpans(src)
        assertTrue("expected at least one synchronized block", spans.isNotEmpty())

        // NOTE: check the MATCH position, not the line start — a one-liner
        // `synchronized(healthLock) { health.clear() }` has its line start
        // OUTSIDE the span, so a line-start offset would false-positive.
        val access = Regex("\\bhealth[.\\[]")
        var accesses = 0
        src.lines().forEachIndexed { idx, line ->
            access.findAll(line).forEach { m ->
                accesses++
                val offset = offsetOf(src, idx) + m.range.first
                assertTrue(
                    "health access outside the router lock at line ${idx + 1}: ${line.trim()}",
                    spans.any { offset in it },
                )
            }
        }
        assertTrue("expected several health accesses, saw $accesses", accesses >= 6)
    }

    // ── helpers (same shape as the repo's other source-shape tests) ─────────

    private fun codeOnly(body: String): String = body.lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
            " ".repeat(line.length)
        } else {
            line
        }
    }

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

    /** Char ranges of every `synchronized(...) { ... }` block (comments already stripped). */
    private fun synchronizedSpans(code: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var i = 0
        while (true) {
            val at = code.indexOf("synchronized(", i)
            if (at < 0) break
            // skip the lock expression's argument list
            var depth = 0
            var j = at + "synchronized".length
            while (j < code.length) {
                when (code[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            var k = j
            while (k < code.length && code[k] != '{') k++
            if (k >= code.length) break
            var braces = 0
            var m = k
            while (m < code.length) {
                when (code[m]) {
                    '{' -> braces++
                    '}' -> {
                        braces--
                        if (braces == 0) break
                    }
                }
                m++
            }
            spans.add(at..m)
            i = m + 1
        }
        return spans
    }

    private fun offsetOf(src: String, lineIdx: Int): Int {
        var off = 0
        var idx = 0
        for (line in src.lines()) {
            if (idx == lineIdx) return off
            off += line.length + 1
            idx++
        }
        return off
    }
}
