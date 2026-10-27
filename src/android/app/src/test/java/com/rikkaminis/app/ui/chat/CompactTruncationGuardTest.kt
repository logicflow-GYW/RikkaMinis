package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rikkaminis.app.conversation.compactionCandidateBudgetMs
import com.rikkaminis.app.conversation.compactionCandidateKey
import com.rikkaminis.app.conversation.compactSummaryIsTruncated
import com.rikkaminis.app.conversation.compactSummaryTruncatedFailure

/**
 * JVM tests for [fix/compact-truncation-guard-1005]:
 *
 *  A. the compaction summary truncation guard — a SendResult.Success whose
 *     stopReason says the output was cut at the ceiling ("length") must NOT be
 *     adopted; it feeds `lastFailure` and the chain falls through to the next
 *     candidate (A-1), while normal completion spellings (null / "end_turn" /
 *     "stop") pass untouched (A-2). An all-truncated chain therefore throws a
 *     failure whose message names the truncation (A-3).
 *  B. the noisy-member budget adaptation — `declaresNoEffortTiers == true`
 *     candidates get 60s instead of the 30s quiet floor (B-1), still clamped
 *     by the 120s chain deadline.
 *  B2. [fix/compact-timeout-budget-1022] the material-size adaptation of the
 *     same budget — a quiet member folding a large summary material takes the
 *     60s budget (B2-1), the strictly-greater threshold keeps the 30s floor
 *     for the common small folds (B2-2), the chain deadline still clamps
 *     (B2-3), and the threshold constant is pinned as a literal (B2-4).
 *  C. [fix/compact-timeout-dedup-1022] the route identity used to skip
 *     siblings after a timeout (C-1 same host+model collapses across
 *     instances, C-2 model separates, C-3 host separates, C-4 no host falls
 *     back to the instance id) plus the source-order wiring of the skip in
 *     the chain loop.
 *
 * Expected values are literal — never derived from the tested constants, so a
 * silent change of a budget or a verdict fails these instead of following the
 * mutation (JVM 装置自指陷阱, 2026-09-26). Wiring is pinned on source
 * fragments — order of named fragments only, per the source-text-assertion
 * lesson (2026-10-01). The end-to-end "all candidates truncated → throw"
 * shape (A-3) is pinned as the composition: the guard assigns `lastFailure`
 * and `continue`s (wiring pin below), and the loop's terminal `throw
 * lastFailure` is the pre-existing line pinned by CompactQuietFirstTest.
 */
class CompactTruncationGuardTest {

    // ── A: truncation verdict ───────────────────────────────────────────

    @Test
    fun `stopReason length is the truncated spelling that fires`() {
        assertTrue(compactSummaryIsTruncated("length"))
    }

    @Test
    fun `normal completion spellings pass the guard untouched`() {
        // A-2:防误伤 — these are the completion markers the observed channels
        // emit for a genuinely finished summary; none may be discarded.
        assertFalse(compactSummaryIsTruncated(null))
        assertFalse(compactSummaryIsTruncated("end_turn"))
        assertFalse(compactSummaryIsTruncated("stop"))
        assertFalse(compactSummaryIsTruncated(""))
    }

    @Test
    fun `cosmetic case and padding of length still fires`() {
        // Relays occasionally echo the spelling with padding or odd casing;
        // the guard normalises those without widening the trigger set.
        assertTrue(compactSummaryIsTruncated(" Length "))
    }

    @Test
    fun `truncation failure message names the model and the stop reason`() {
        val msg = compactSummaryTruncatedFailure("GLM-5.3-Flash").message.orEmpty()
        assertTrue(msg.contains("truncated"))
        assertTrue(msg.contains("stopReason=length"))
        assertTrue(msg.contains("GLM-5.3-Flash"))
    }

    // ── B: budget adaptation (literal values, not derived) ──────────────

    @Test
    fun `noisy candidate gets the 60s budget`() {
        assertEquals(
            60_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = true, materialTokens = 0,
                nowMs = 0L, deadlineAt = 120_000L,
            ),
        )
    }

    @Test
    fun `quiet and unknown candidates keep the 30s budget`() {
        assertEquals(
            30_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = false, materialTokens = 0,
                nowMs = 0L, deadlineAt = 120_000L,
            ),
        )
        assertEquals(
            30_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = null, materialTokens = 0,
                nowMs = 0L, deadlineAt = 120_000L,
            ),
        )
    }

    @Test
    fun `chain deadline still clamps either budget`() {
        assertEquals(
            20_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = true, materialTokens = 0,
                nowMs = 100_000L, deadlineAt = 120_000L,
            ),
        )
        assertEquals(
            15_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = false, materialTokens = 0,
                nowMs = 105_000L, deadlineAt = 120_000L,
            ),
        )
    }

    @Test
    fun `spent deadline yields a non-positive budget so the chain stops`() {
        assertTrue(
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = true, materialTokens = 0,
                nowMs = 120_000L, deadlineAt = 120_000L,
            ) <= 0L,
        )
        assertTrue(
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = false, materialTokens = 0,
                nowMs = 130_000L, deadlineAt = 120_000L,
            ) <= 0L,
        )
    }

    @Test
    fun `noisy candidate budget constant is the measured 60s`() {
        assertEquals(60_000L, ChatViewModel.COMPACT_SUMMARY_NOISY_CANDIDATE_BUDGET_MS)
    }

    // ── B2 [fix/compact-timeout-budget-1022]: material-size adaptation ──
    // 2026-10-22 device log: on a long agent run (no new user turn) the budget
    // anchor hands the WHOLE agent history to one summary call, so the members
    // that had just succeeded at 25.0s / 28.2s / 29.1s drew the 30s quiet
    // floor and died at exactly 30000ms — 25 timeouts that day, all of them on
    // material ≥ ~2 100 estimated tokens, while every ≤ ~700-token fold
    // finished (6.8–25.1s). Those members never declare declaresNoEffortTiers:
    // a user-configured relay entry cannot.

    @Test
    fun `quiet candidate on large material takes the 60s budget`() {
        assertEquals(
            60_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = false, materialTokens = 1_001,
                nowMs = 0L, deadlineAt = 120_000L,
            ),
        )
        assertEquals(
            60_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = null, materialTokens = 30_000,
                nowMs = 0L, deadlineAt = 120_000L,
            ),
        )
    }

    @Test
    fun `material exactly at the threshold keeps the 30s floor`() {
        // Strictly-greater boundary: the small folds sit at or under the
        // threshold and must keep their short wall (they are the common case).
        assertEquals(
            30_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = false, materialTokens = 1_000,
                nowMs = 0L, deadlineAt = 120_000L,
            ),
        )
    }

    @Test
    fun `large material budget still clamps to the chain deadline`() {
        assertEquals(
            20_000L,
            compactionCandidateBudgetMs(
                declaresNoEffortTiers = false, materialTokens = 50_000,
                nowMs = 100_000L, deadlineAt = 120_000L,
            ),
        )
    }

    @Test
    fun `material threshold constant is the measured thousand estimated tokens`() {
        assertEquals(1_000, ChatViewModel.COMPACT_SUMMARY_LARGE_MATERIAL_TOKENS)
    }

    // ── C [fix/compact-timeout-dedup-1022]: route identity ─────────────
    // A timeout is a verdict on the ROUTE (host + model), not on the account:
    // 2026-10-22, three tierflow entries sharing https://tierflow.cn + model
    // `tierflow` each died at 30000ms inside one chain.

    @Test
    fun `same host and model collapse across different instances`() {
        assertEquals(
            compactionCandidateKey(
                customBaseUrl = "https://tierflow.cn/v1", instanceId = "inst-a", modelId = "tierflow",
            ),
            compactionCandidateKey(
                customBaseUrl = "https://tierflow.cn", instanceId = "inst-b", modelId = "tierflow",
            ),
        )
    }

    @Test
    fun `same host with a different model stays distinct`() {
        assertTrue(
            compactionCandidateKey(
                customBaseUrl = "https://tierflow.cn", instanceId = "inst-a", modelId = "tierflow",
            ) != compactionCandidateKey(
                customBaseUrl = "https://tierflow.cn", instanceId = "inst-b", modelId = "tierflow-pro",
            ),
        )
    }

    @Test
    fun `different hosts stay distinct`() {
        assertTrue(
            compactionCandidateKey(
                customBaseUrl = "https://a.example/v1", instanceId = "i", modelId = "m",
            ) != compactionCandidateKey(
                customBaseUrl = "https://b.example/v1", instanceId = "i", modelId = "m",
            ),
        )
    }

    @Test
    fun `missing or blank base url falls back to the instance id`() {
        assertEquals(
            compactionCandidateKey(customBaseUrl = null, instanceId = "inst-a", modelId = "m"),
            compactionCandidateKey(customBaseUrl = "   ", instanceId = "inst-a", modelId = "m"),
        )
        // …and keeps distinct instances distinct when no host is available.
        assertTrue(
            compactionCandidateKey(customBaseUrl = null, instanceId = "inst-a", modelId = "m")
                != compactionCandidateKey(customBaseUrl = null, instanceId = "inst-b", modelId = "m"),
        )
    }

    // ── wiring: the call site consumes the guards ───────────────────────
    //
    // generateCompactSummary lives on ChatViewModel (uninstantiable in JVM),
    // so the integration is pinned on source fragments — order of named
    // fragments only, per the source-text-assertion lesson (2026-10-01).

    @Test
    fun `success branch guards truncated summaries before adopting the text`() {
        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt",
        )
        val successRegion = lifecycle
            .substringAfter("is ProviderExecutionGateway.SendResult.Success -> {")
            .substringBefore("is ProviderExecutionGateway.SendResult.RemoteFailure ->")
        val atGuard = successRegion.indexOf("compactSummaryIsTruncated(r.response.stopReason)")
        val atTruncatedLog = successRegion.indexOf("summary TRUNCATED")
        val atFailure = successRegion.indexOf("lastFailure = compactSummaryTruncatedFailure(")
        val atContinue = successRegion.indexOf("continue")
        val atSuccessLog = successRegion.indexOf("SUCCESS")
        val atReturn = successRegion.indexOf("return r.response.text")
        for ((name, idx) in listOf(
            "truncation guard" to atGuard, "TRUNCATED log" to atTruncatedLog,
            "failure assignment" to atFailure, "continue" to atContinue,
            "SUCCESS log" to atSuccessLog, "text adoption" to atReturn,
        )) {
            assertTrue("success branch missing $name", idx >= 0)
        }
        // The guard must run BEFORE everything it gates: the failure is
        // recorded and the loop continues (next candidate) ahead of the
        // success log and the text adoption (A-1).
        assertTrue(atGuard < atTruncatedLog)
        assertTrue(atTruncatedLog < atFailure)
        assertTrue(atFailure < atContinue)
        assertTrue(atContinue < atSuccessLog)
        assertTrue(atSuccessLog < atReturn)
    }

    @Test
    fun `budget call site resolves the candidate budget through the helper`() {
        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt",
        )
        val atCall = lifecycle.indexOf("val budgetMs = compactionCandidateBudgetMs(")
        assertTrue("budget calc not wired through compactionCandidateBudgetMs", atCall >= 0)
        // The noisy flag must be one of the arguments (B-1), not merely a
        // later unrelated reference: keep the match inside the call window.
        val atFlagArg = lifecycle.indexOf("candidate.model.declaresNoEffortTiers", atCall)
        assertTrue(
            "noisy flag not passed into the budget helper call",
            atFlagArg > atCall && atFlagArg - atCall < 200,
        )
        // And the old quiet-only inline shape must be gone — re-inlining it
        // would silently drop the noisy adaptation.
        assertFalse(
            "budget must not be inlined again",
            lifecycle.contains("val budgetMs = minOf("),
        )
        // [fix/compact-timeout-budget-1022] The material estimate must reach
        // the helper too: the call site has to feed the real summary size, not
        // a literal, or the large-material tier never opens.
        val atMaterialArg = lifecycle.indexOf("estimatedInput,", atCall)
        assertTrue(
            "material size not passed into the budget helper call",
            atMaterialArg > atCall && atMaterialArg - atCall < 200,
        )
    }

    @Test
    fun `timeout path records the route and the loop skips recorded routes`() {
        val lifecycle = readRepoFile(
            "app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt",
        )
        val region = lifecycle
            .substringAfter("val timedOutRoutes = mutableSetOf<String>()")
            .substringBefore("is ProviderExecutionGateway.SendResult.Success -> {")
        val atSkip = region.indexOf("if (route in timedOutRoutes)")
        val atSkipLog = region.indexOf("summary skip")
        val atBudget = region.indexOf("val budgetMs = compactionCandidateBudgetMs(")
        val atSend = region.indexOf("withTimeoutOrNull(budgetMs) { sendVia(candidate) }")
        val atRecord = region.indexOf("timedOutRoutes.add(route)")
        for ((name, idx) in listOf(
            "skip check" to atSkip, "skip log" to atSkipLog, "budget calc" to atBudget,
            "candidate send" to atSend, "route recording" to atRecord,
        )) {
            assertTrue("timeout dedup missing $name", idx >= 0)
        }
        // Order is the contract: a candidate whose route already timed out is
        // skipped before it is budgeted or sent (that is the whole point — the
        // chain must not pay the same wall twice), and the recording happens on
        // the timeout that proved it.
        assertTrue(atSkip < atSkipLog)
        assertTrue(atSkipLog < atBudget)
        assertTrue(atBudget < atSend)
        assertTrue(atSend < atRecord)
        // The route must be derived from the candidate's host + model, not
        // from the entry id: sibling entries on one relay share a route.
        val atKey = region.indexOf("val route = compactionCandidateKey(")
        assertTrue("route not derived through compactionCandidateKey", atKey in 0 until atSkip)
        assertTrue(
            "route key must carry the model id",
            region.indexOf("modelId = candidate.model.id", atKey) in atKey until atSkip,
        )
    }

    /**
     * Walk up from the test working directory to the repo root containing
     * [relative]. Gradle sets user.dir to the module dir (`.../src/android/app`),
     * so a single-level parent lookup is not enough — CI caught this shape.
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
