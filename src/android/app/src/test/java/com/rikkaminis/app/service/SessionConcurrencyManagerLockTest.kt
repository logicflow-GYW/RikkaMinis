package com.rikkaminis.app.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [audit-1018 ISSUE-10] resume-must-happen-outside-the-monitor contract for
 * [SessionConcurrencyManager].
 *
 * Pre-fix the promotion ran inside the callers' monitors: both `releaseSlot`
 * and `releasePromotedAfterCancel` were `@Synchronized` methods that called
 * `promote()` — whose `toResume?.resume(Unit)` therefore executed while the
 * manager monitor was still held (its own `synchronized(this)` block had
 * already been left, but the OUTER lock was still held — the "锁外 resume"
 * comment was false). A resumed waiter whose post-resume body immediately
 * re-enters the manager would queue behind the very lock that was just
 * released.
 *
 * The behavioral probe uses `Dispatchers.Unconfined`: `resume` then executes
 * the continuation body INLINE on the resuming thread, so `Thread.holdsLock(
 * SessionConcurrencyManager)` inside the body is a direct, deterministic
 * detector of "resume ran on a thread holding the manager monitor". The
 * deterministic structural half of the same contract lives in
 * `resumesStayOutsideSynchronizedSpans` (and the standalone gate
 * scripts/scan/router_lock_check.py).
 */
class SessionConcurrencyManagerLockTest {

    @Before
    fun resetManager() {
        SessionConcurrencyManager.resetForTesting()
        // [memory-pressure-gate] Pin the gate to NORMAL so the probes are
        // unaffected by the real RSS of the CI runner (real delays would
        // change which thread observes what).
        MemoryPressureGate.metricsReader = { MemoryPressureGate.Metrics(0L, 147L) }
        MemoryPressureGate.reclaimHook = {}
        MemoryPressureGate.pressureListener = { _, _ -> }
    }

    /**
     * Fill the cap (2), park a waiter with an Unconfined dispatcher, release a
     * slot from THIS thread, and check that the inline-resumed body does NOT
     * see the manager monitor held by this thread. Red on the pre-fix code.
     */
    @Test
    fun `promoted waiter resumes outside the manager monitor`() = runBlocking {
        SessionConcurrencyManager.acquireSlot("holder-a")
        SessionConcurrencyManager.acquireSlot("holder-b")

        val sawMonitorWhileResumed = AtomicBoolean(false)
        val waiter = launch(Dispatchers.Unconfined) {
            SessionConcurrencyManager.acquireSlot("waiter")
            // [行为判据] Unconfined 让 resume 在 resume 调用线程上内联执行：
            // 若 resume 发生在持锁线程上，这里必看到监视器仍被本线程持有。
            if (Thread.holdsLock(SessionConcurrencyManager)) sawMonitorWhileResumed.set(true)
        }
        // Unconfined 下 launch 内联执行到挂起点：waiter 已在队列里。
        assertTrue("waiter should be parked", SessionConcurrencyManager.isSuspended("waiter"))

        // 本线程释放 → promote waiter → resume（Unconfined 内联在本线程执行）。
        SessionConcurrencyManager.releaseSlot("holder-a")
        assertTrue("waiter must have been resumed inline", waiter.isCompleted)
        assertFalse(
            "resume ran on a thread holding the manager monitor",
            sawMonitorWhileResumed.get(),
        )

        SessionConcurrencyManager.releaseSlot("waiter")
        SessionConcurrencyManager.releaseSlot("holder-b")
    }

    /**
     * The state migration (PROMOTED / runningSessions / activeRunIdsBySession)
     * must happen BEFORE the resume — the waiter, running inline at the resume
     * point, must already observe itself in the running set. This is a
     * regression guard for "resume before state update" reorderings.
     */
    @Test
    fun `waiter observes fully promoted state at the resume point`() = runBlocking {
        SessionConcurrencyManager.acquireSlot("holder-a")
        SessionConcurrencyManager.acquireSlot("holder-b")

        val stateAtResume = AtomicBoolean(false)
        val waiter = launch(Dispatchers.Unconfined) {
            SessionConcurrencyManager.acquireSlot("waiter")
            stateAtResume.set("waiter" in SessionConcurrencyManager.runningSessions.value)
        }
        assertTrue(SessionConcurrencyManager.isSuspended("waiter"))

        SessionConcurrencyManager.releaseSlot("holder-a")
        assertTrue(waiter.isCompleted)
        assertTrue(
            "resume must happen after the running-set migration, saw $stateAtResume",
            stateAtResume.get(),
        )

        SessionConcurrencyManager.releaseSlot("waiter")
        SessionConcurrencyManager.releaseSlot("holder-b")
    }

    /**
     * A waiter whose post-resume body immediately re-enters the manager (calls
     * `releaseSlot` on itself) must complete without deadlock and leave the
     * occupancy consistent — the scenario the "锁外 resume" contract exists for.
     */
    @Test
    fun `promoted waiter can re-enter the manager from its resume body`() = runBlocking {
        SessionConcurrencyManager.acquireSlot("holder-a")
        SessionConcurrencyManager.acquireSlot("holder-b")

        val reentered = AtomicBoolean(false)
        val waiter = launch(Dispatchers.Unconfined) {
            SessionConcurrencyManager.acquireSlot("waiter")
            SessionConcurrencyManager.releaseSlot("waiter")
            reentered.set(true)
        }
        assertTrue(SessionConcurrencyManager.isSuspended("waiter"))

        SessionConcurrencyManager.releaseSlot("holder-a")
        assertTrue("re-entry from the resume body must complete", waiter.isCompleted)
        assertTrue("resume body must have run to completion", reentered.get())
        // waiter 已自释放：只剩 holder-b 占槽。
        assertEquals(setOf("holder-b"), SessionConcurrencyManager.runningSessions.value)

        SessionConcurrencyManager.releaseSlot("holder-b")
    }

    // ── deterministic: structural (near-twin of router_lock_check.py) ───────

    /**
     * This object manages resumes, so it must not rely on method-level
     * `@Synchronized` locking (which is exactly how the resume-inside-lock bug
     * was hiding), and no `resume(` may live inside a `synchronized` span.
     */
    @Test
    fun `resumes stay outside synchronized spans and no method-level locking`() {
        val src = codeOnly(
            readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/service/SessionConcurrencyManager.kt"),
        )
        assertFalse(
            "method-level @Synchronized must not be used in this object (resume must stay outside the monitor)",
            src.contains("@Synchronized"),
        )
        val spans = synchronizedSpans(src)
        assertTrue("expected at least one synchronized block", spans.isNotEmpty())

        val resumeCall = Regex("\\.resume\\(")
        var resumeSites = 0
        src.lines().forEachIndexed { idx, line ->
            resumeCall.findAll(line).forEach { m ->
                resumeSites++
                // MATCH position, not line start (same one-liner trap as the
                // router lock test — a resume on a `synchronized { … }` line's
                // continuation must be judged by where the call sits).
                val offset = offsetOf(src, idx) + m.range.first
                assertFalse(
                    "resume inside a synchronized span at line ${idx + 1}: ${line.trim()}",
                    spans.any { offset in it },
                )
            }
        }
        // 两处真实 resume 站点必须在场（防判据空转）。
        assertTrue("expected at least 2 resume sites, saw $resumeSites", resumeSites >= 2)
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
