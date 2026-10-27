package com.rikkaminis.app.conversation

import com.rikkaminis.app.data.ContextPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/compact-exhausted-rescue-1005] JVM tests for the EXHAUSTED-state
 * once-only rescue gate (task-brief-1005-F §3.1/3.2/3.3).
 *
 * Decision table pinned with LITERAL expectations (JVM 自指陷阱纪律 — no
 * expectation may be derived from the code under test):
 *
 *  | estimatedTokens | rescueAttempted | →         |
 *  |-----------------|-----------------|-----------|
 *  | >= window       | false           | RESCUE    |
 *  | >= window       | true (default)  | EXHAUSTED |
 *
 * decide() early-returns on the EXHAUSTED branch BEFORE the tail/debounce
 * gates: those exist for below-ceiling auto-compacts ("just compacted, don't
 * re-compact"), while the rescue is once-only per user-action cycle and
 * already gated by the storm gate itself — stacking the gates would eat the
 * single rescue chance (field check: all 7 EXHAUSTED skips on 2026-10-05 had
 * tail 8.6k–17.6k, above the 8k tail gate, so behaviour is unaffected).
 *
 * The re-fire sequence asserts that after the caller flips the flag
 * (rescue attempted), the SAME state degrades to EXHAUSTED — the storm gate
 * is exactly the flag, nothing else.
 *
 * The default-parameter contract is pinned too: `rescueAttempted` defaults
 * to TRUE so pre-existing call sites (and these legacy tests) keep the
 * "always skip at the ceiling" behaviour without opt-in.
 */
class CompactExhaustedRescueTest {

    private val window = 128_000

    private fun decide(
        tokens: Int,
        rescueAttempted: Boolean,
        tail: Long = 40_000,
        lastAuto: Long = Long.MIN_VALUE,
        now: Long = 1_000_000L,
    ) = ContextCompactor.decide(
        estimatedTokens = tokens,
        contextWindow = window,
        policy = ContextPolicy.forContextWindow(window),
        tailTokens = tail,
        isCompacting = false,
        lastAutoCompactAtMs = lastAuto,
        nowMs = now,
        rescueAttempted = rescueAttempted,
    )

    // ── §3.1 the rescue verdict table ───────────────────────────────────

    @Test
    fun `EXHAUSTED with gate open becomes RESCUE`() {
        assertEquals(
            ContextCompactor.Decision.RESCUE,
            decide(tokens = window, rescueAttempted = false),
        )
        // Strictly over the window (the 2026-10-04 field shape: 444k / 250k):
        assertEquals(
            ContextCompactor.Decision.RESCUE,
            decide(tokens = window * 2, rescueAttempted = false),
        )
    }

    @Test
    fun `EXHAUSTED with gate closed stays EXHAUSTED - stalled fallback`() {
        assertEquals(
            ContextCompactor.Decision.EXHAUSTED,
            decide(tokens = window, rescueAttempted = true),
        )
        assertEquals(
            ContextCompactor.Decision.EXHAUSTED,
            decide(tokens = window * 2, rescueAttempted = true),
        )
    }

    @Test
    fun `rescue is not gated by the below-ceiling gates`() {
        // decide() early-returns RESCUE/EXHAUSTED before the tail/debounce
        // gates: they exist for below-ceiling auto-compacts ("just compacted,
        // don't re-compact"), the rescue is once-only per user-action cycle
        // and already gated by the storm gate itself. Both gates failing at
        // once must not eat the single rescue chance.
        val lastAuto = 500_000L
        val now = lastAuto + ContextCompactor.DEFAULT_AUTO_COMPACT_MIN_INTERVAL_MS - 1
        assertEquals(
            ContextCompactor.Decision.RESCUE,
            decide(tokens = window, rescueAttempted = false, tail = 1_000, lastAuto = lastAuto, now = now),
        )
        // Gate closed + the same small tail / fresh timestamp → still stalls.
        assertEquals(
            ContextCompactor.Decision.EXHAUSTED,
            decide(tokens = window, rescueAttempted = true, tail = 1_000, lastAuto = lastAuto, now = now),
        )
    }

    @Test
    fun `after a rescue attempt the same state degrades to EXHAUSTED`() {
        // The storm gate IS the flag: caller attempted a rescue (closed the
        // flag) → the identical state now stalls instead of re-firing.
        val before = decide(tokens = window, rescueAttempted = false)
        val after = decide(tokens = window, rescueAttempted = true)
        assertEquals(ContextCompactor.Decision.RESCUE, before)
        assertEquals(ContextCompactor.Decision.EXHAUSTED, after)
    }

    @Test
    fun `sub-window behaviour is untouched by the gate value`() {
        // Below the ceiling the gate must not change anything (the flag is
        // only consulted on the EXHAUSTED branch).
        assertEquals(
            ContextCompactor.Decision.AUTO_COMPACT,
            decide(tokens = 120_000, rescueAttempted = false),
        )
        assertEquals(
            ContextCompactor.Decision.AUTO_COMPACT,
            decide(tokens = 120_000, rescueAttempted = true),
        )
    }

    // ── default-parameter contract (§3.2 storm-gate safety) ────────────

    @Test
    fun `default rescueAttempted is TRUE - legacy call sites keep skipping`() {
        // The legacy EXHAUSTED assertion (AutoCompactPolicyTest) passes NO
        // gate argument; the default must keep it EXHAUSTED, i.e. callers
        // that don't opt in never open the rescue gate.
        val d = ContextCompactor.decide(
            estimatedTokens = window,
            contextWindow = window,
            policy = ContextPolicy.forContextWindow(window),
            tailTokens = 40_000,
            isCompacting = false,
        )
        assertEquals(ContextCompactor.Decision.EXHAUSTED, d)
    }

    // ── §3.3 the C3 skip-line rescue segment (wiring, fragment-pinned) ──

    @Test
    fun `EXHAUSTED skip line carries a single-line rescue segment`() {
        // The skip line must stay single-line grep-able with the rescue
        // segment inline (task-brief-1005-D telemetry-suffix pattern). The
        // only reachable EXHAUSTED skip has the gate CLOSED (decide()
        // early-returns EXHAUSTED/RESCUE before the tail/debounce gates),
        // so the segment is always "blocked" there.
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatContextWindowExt.kt")
        val region = src
            .substringAfter("internal suspend fun ChatViewModel.maybeAutoCompactInLoop(")
            .substringBefore("internal suspend fun ChatViewModel.awaitAutoCompactIfNeeded()")
        val atExhBranch = region.indexOf("if (decision == ContextCompactor.Decision.EXHAUSTED)")
        val atBlocked = region.indexOf("rescue=blocked")
        assertTrue("EXHAUSTED skip branch missing", atExhBranch >= 0)
        assertTrue("rescue=blocked segment missing", atBlocked >= 0)
        assertTrue("segment must follow the EXHAUSTED branch", atExhBranch < atBlocked)
        // Exactly one rescue segment in the whole function region.
        assertEquals(1, Regex("rescue=blocked").findAll(region).count())
        // Same string literal as the skip line (single-line concatenation).
        val atSkip = region.indexOf("[AutoCompactLoop] skipped")
        assertTrue(atSkip >= 0 && atSkip < atBlocked)
    }

    // ── wiring: the gate rides the loop path, resets on user actions ────
    //
    // maybeAutoCompactInLoop lives on ChatViewModel (uninstantiable in JVM),
    // so the integration is pinned on source fragments — order of named
    // fragments only (2026-10-01 lesson). Fragment strings are kept unique
    // within their asserted region.

    @Test
    fun `loop path feeds the gate into decide and closes it on RESCUE`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatContextWindowExt.kt")
        val region = src
            .substringAfter("internal suspend fun ChatViewModel.maybeAutoCompactInLoop(")
            .substringBefore("internal suspend fun ChatViewModel.awaitAutoCompactIfNeeded()")
        val atParam = region.indexOf("rescueAttempted = rescueAttemptedForCompact,")
        val atClose = region.indexOf("rescueAttemptedForCompact = true")
        val atExh = region.indexOf("if (decision == ContextCompactor.Decision.EXHAUSTED)")
        for ((name, idx) in listOf(
            "decide param" to atParam,
            "gate close on RESCUE" to atClose,
            "EXHAUSTED skip branch" to atExh,
        )) {
            assertTrue("loop path missing $name", idx >= 0)
        }
        // Sequence: the EXHAUSTED branch (log + return) precedes the RESCUE
        // branch that closes the gate.
        assertTrue(atExh < atClose)
        // The gate-closing branch only runs for RESCUE — the EXHAUSTED branch
        // must have returned before it.
        val atRescueBranch = region.indexOf("if (decision == ContextCompactor.Decision.RESCUE)")
        assertTrue("RESCUE branch missing", atRescueBranch > atExh)
    }

    @Test
    fun `rescue result rides the folded-nothing warning as a single line`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatContextWindowExt.kt")
        val region = src
            .substringAfter("internal suspend fun ChatViewModel.maybeAutoCompactInLoop(")
            .substringBefore("internal suspend fun ChatViewModel.awaitAutoCompactIfNeeded()")
        val atFoldedCheck = region.indexOf("if (!folded)")
        val atRescueFailed = region.indexOf("rescue=failed")
        val atRescueOk = region.indexOf("rescue=succeeded")
        assertTrue("rescue=failed log missing", atRescueFailed >= 0)
        assertTrue("rescue=succeeded log missing", atRescueOk >= 0)
        // failed only fires inside the folded-nothing branch; succeeded sits
        // after it (the else branch).
        assertTrue(atFoldedCheck >= 0 && atFoldedCheck < atRescueFailed)
        assertTrue(atRescueFailed < atRescueOk)
    }

    @Test
    fun `all three user-action resets re-arm the gate`() {
        // Reset #1 — a real user send re-arms AFTER the auto-compact trigger
        // (guards already returned), inside sendMessage.
        val vm = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
        val send = vm.substringAfter("fun sendMessage(rawText: String)")
            .substringBefore("fun runCompactNow()")
        val atTrigger = send.indexOf("maybeTriggerAutoCompact()")
        val atReset = send.indexOf("rescueAttemptedForCompact = false")
        assertTrue("sendMessage reset missing", atReset >= 0)
        assertTrue("reset must come after the auto-compact trigger", atTrigger < atReset)
        // Reset #2 — both manual-compact entries.
        assertTrue(
            "runCompactNow reset missing",
            vm.indexOf("rescueAttemptedForCompact = false", vm.indexOf("fun runCompactNow")) >= 0,
        )
        val slash = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatSlashTokenExt.kt")
        val compactBranch = slash.substringAfter("if (name == \"compact\")")
            .substringBefore("if (name == \"thinking\")")
        assertTrue("slash /compact reset missing", compactBranch.contains("rescueAttemptedForCompact = false"))
        // Reset #3 — session open.
        val lifecycle = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatSessionLifecycle.kt")
        val load = lifecycle.substringAfter("internal fun ChatViewModel.loadSession()")
        val atLoadReset = load.indexOf("rescueAttemptedForCompact = false")
        val atSafeMode = load.indexOf("if (com.rikkaminis.app.crash.CrashFrequencyDetector.isSafeMode())")
        assertTrue("loadSession reset missing", atLoadReset >= 0)
        // Placement: AFTER the safe-mode early return — that path skips
        // session restore (the user never entered a session), so it must
        // not re-arm the gate.
        assertTrue("safe-mode block missing", atSafeMode >= 0)
        assertTrue("reset must come after the safe-mode early return", atLoadReset > atSafeMode)
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
}
