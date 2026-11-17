package com.rikkaminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [W1118-T6 D9 F-06 / R6 F-03] The hang counter's two settle paths.
 *
 * F-06 — `markHealthyTick` re-checked only `hang_count` under
 * `hangCounterLock`. `recordHang` writes `hang_count` AND `last_hang_at_ms`
 * under the same lock, so this interleaving lost a hang that had just been
 * recorded:
 *
 *   1. main: count = 1, last hang 20 s ago → both OUTER checks pass;
 *   2. watchdog: recordHang takes the lock, count = 2, last_hang_at = now,
 *      render breaker tripped;
 *   3. main: takes the lock, sees count != 0, zeroes it and clears the breaker.
 *
 * R6 F-03 — the heal gate was `if (!hangCountedThisProcess.get()) return`. That
 * protected the cold-start breakers from a reset before launch finished, but it
 * had no exit at all: a count inherited from the previous process (≥ 3 hangs →
 * forced home + degraded markdown) could only ever be cleared by hanging AGAIN
 * in this process. The gate is now "inherited counts may settle once this
 * process has been alive for a full quiet window".
 *
 * Expectations are literals, never derived from the production constants, so a
 * changed threshold drags the evidence along with it only if the change is
 * deliberate.
 */
class QuietHealPolicyTest {

    private val hangSource = "src/main/java/com/rikkaminis/app/diagnostics/HangDetector.kt"

    // ─── F-06: the in-lock quiet-window re-check ────────────────────────────

    @Test
    fun `a count of zero is never worth a reset`() {
        assertFalse(
            QuietHealPolicy.mayResetOnHealthyTick(
                hangCount = 0,
                lastHangAtMs = 0L,
                nowMs = 1_000_000L,
                quietMs = 10_000L,
            ),
        )
    }

    @Test
    fun `a hang recorded after the outer check blocks the reset`() {
        // The exact F-06 interleaving: the timestamp is 0 ms old.
        assertFalse(
            QuietHealPolicy.mayResetOnHealthyTick(
                hangCount = 2,
                lastHangAtMs = 1_000_000L,
                nowMs = 1_000_000L,
                quietMs = 10_000L,
            ),
        )
        // 9 999 ms is still inside the window (boundary below).
        assertFalse(
            QuietHealPolicy.mayResetOnHealthyTick(
                hangCount = 2,
                lastHangAtMs = 990_001L,
                nowMs = 1_000_000L,
                quietMs = 10_000L,
            ),
        )
    }

    @Test
    fun `a hang older than the quiet window may be reset`() {
        assertTrue(
            QuietHealPolicy.mayResetOnHealthyTick(
                hangCount = 1,
                lastHangAtMs = 980_000L,
                nowMs = 1_000_000L,
                quietMs = 10_000L,
            ),
        )
        // Exact boundary: ">= quietMs" matches markHealthyTick's own `< quiet`.
        assertTrue(
            QuietHealPolicy.mayResetOnHealthyTick(
                hangCount = 1,
                lastHangAtMs = 990_000L,
                nowMs = 1_000_000L,
                quietMs = 10_000L,
            ),
        )
    }

    @Test
    fun `a counter with no timestamp is a legacy record and may be reset`() {
        assertTrue(
            QuietHealPolicy.mayResetOnHealthyTick(
                hangCount = 3,
                lastHangAtMs = 0L,
                nowMs = 1_000_000L,
                quietMs = 10_000L,
            ),
        )
    }

    @Test
    fun `markHealthyTick re-checks the quiet window inside the counter lock`() {
        assertLockedRecheck(source())
    }

    /**
     * The reset decision must be evaluated where `recordHang` cannot interleave:
     * inside `synchronized(hangCounterLock)`, after the count read and before the
     * write.
     */
    private fun assertLockedRecheck(body: String) {
        val mark = body.substringAfter("fun markHealthyTick()")
        val lock = mark.indexOf("synchronized(hangCounterLock) {")
        val decision = mark.indexOf("QuietHealPolicy.mayResetOnHealthyTick(")
        val write = mark.indexOf("prefs.edit()")
        assertTrue("markHealthyTick must keep its critical section", lock >= 0)
        assertTrue("the reset decision must go through the pure policy", decision >= 0)
        assertTrue("the decision must run INSIDE the lock (lock@$lock, decision@$decision)", decision > lock)
        assertTrue("and before the write it authorises (decision@$decision, write@$write)", decision < write)
        assertTrue(
            "the decision must be fed the CURRENT timestamp and the CURRENT last-hang stamp",
            mark.substring(decision, write).contains("lastHangAtMs = prefs.getLong(KEY_LAST_HANG_AT, 0L)"),
        )
    }

    // ─── R6 F-03: inherited counts may settle ──────────────────────────────

    @Test
    fun `a hang counted by this process may be healed right away`() {
        assertTrue(
            QuietHealPolicy.mayAttemptHeal(
                countedThisProcess = true,
                carriedOverCount = true,
                processAliveMs = 0L,
                quietMs = 10_000L,
            ),
        )
    }

    @Test
    fun `an inherited count stays cached through the launch window`() {
        // shouldForceHomeOnLaunch / the launch resolver read the count during
        // launch; the first 10 s of the process must not clear it.
        assertFalse(
            QuietHealPolicy.mayAttemptHeal(
                countedThisProcess = false,
                carriedOverCount = true,
                processAliveMs = 0L,
                quietMs = 10_000L,
            ),
        )
        assertFalse(
            QuietHealPolicy.mayAttemptHeal(
                countedThisProcess = false,
                carriedOverCount = true,
                processAliveMs = 9_999L,
                quietMs = 10_000L,
            ),
        )
    }

    @Test
    fun `an inherited count settles once the process has been up for a quiet window`() {
        assertTrue(
            QuietHealPolicy.mayAttemptHeal(
                countedThisProcess = false,
                carriedOverCount = true,
                processAliveMs = 10_000L,
                quietMs = 10_000L,
            ),
        )
    }

    @Test
    fun `healthy ticks with nothing to settle stay free of prefs reads`() {
        assertFalse(
            QuietHealPolicy.mayAttemptHeal(
                countedThisProcess = false,
                carriedOverCount = false,
                processAliveMs = 600_000L,
                quietMs = 10_000L,
            ),
        )
    }

    @Test
    fun `start seeds the inherited-count heal and healQuietHang consumes the gate`() {
        assertCarriedOverHealWired(source())
    }

    private fun assertCarriedOverHealWired(body: String) {
        val start = body.substringAfter("fun start(context: Context)")
        assertTrue(
            "start must remember when this process began (the grace gate)",
            start.contains("startedAtMs.set(System.currentTimeMillis())"),
        )
        assertTrue(
            "start must arm the heal for a count inherited from the previous process",
            start.contains("if (currentHangCount(context) > 0) healPending.set(true)"),
        )
        val heal = body.substringAfter("private fun healQuietHang()")
        assertTrue(
            "the heal must consult the pure policy instead of the old one-flag gate",
            heal.contains("QuietHealPolicy.mayAttemptHeal("),
        )
        assertFalse(
            "the old unconditional 'only what we counted' gate must be gone",
            heal.contains("if (!hangCountedThisProcess.get()) return"),
        )
        assertTrue(
            "the flag must be cleared once the count is back to 0 (no prefs churn afterwards)",
            heal.contains("healPending.set(false)"),
        )
        // recordHang must still arm the same flag under the counter lock.
        val record = body.substringAfter("private fun recordHang(durationMs: Long)")
        assertTrue(
            "a hang counted here must arm the heal",
            record.contains("healPending.set(true)"),
        )
    }

    // ─── negative controls ──────────────────────────────────────────────────

    @Test
    fun `the wire probes reject the pre-fix sources`() {
        val real = source()

        // Pre-fix shape: the inner check was the count alone.
        val decisionStart = real.indexOf("            if (!QuietHealPolicy.mayResetOnHealthyTick(")
        val decisionEnd = real.indexOf("                return\n            }", decisionStart)
        assertTrue("the decision block must be locatable", decisionStart >= 0 && decisionEnd > decisionStart)
        val tail = "                return\n            }".length
        val unlockedRead = real.substring(0, decisionStart) +
            "            if (prefs.getInt(KEY_HANG_COUNT, 0) == 0) return\n" +
            real.substring(decisionEnd + tail)
        assertFalse("the mutation must really remove the decision", unlockedRead == real)
        assertFalse(
            "the mutation must really remove the policy call",
            unlockedRead.contains("mayResetOnHealthyTick("),
        )

        val oldGate = real.replace(
            "        if (!QuietHealPolicy.mayAttemptHeal(",
            "        if (!hangCountedThisProcess.get()) return\n        if (!QuietHealPolicy.mayAttemptHeal(",
        )
        assertFalse("the mutation must really change the source", oldGate == real)

        val noSeed = real.replace(
            "        if (currentHangCount(context) > 0) healPending.set(true)\n",
            "",
        )
        assertFalse("the mutation must really remove the seed", noSeed == real)

        val rejected = mutableListOf<String>()
        runCatching { assertLockedRecheck(unlockedRead) }.onFailure { rejected += "locked-recheck" }
        runCatching { assertCarriedOverHealWired(oldGate) }.onFailure { rejected += "heal-gate" }
        runCatching { assertCarriedOverHealWired(noSeed) }.onFailure { rejected += "heal-seed" }
        assertEquals(
            "each mutation must be rejected by its own probe",
            "locked-recheck, heal-gate, heal-seed",
            rejected.joinToString(", "),
        )
    }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun source(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, hangSource)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$hangSource not found from user.dir")
    }
}
