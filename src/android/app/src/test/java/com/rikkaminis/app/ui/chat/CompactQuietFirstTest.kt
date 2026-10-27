package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.LLMProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [fix/compact-quiet-first-1001]:
 * [ordersCompactionCandidates] ordering (thinking-uncontrollable members go
 * last) and the three wall budgets on the ChatViewModel companion.
 *
 * Expected values are literal — never derived from the tested constants, so a
 * silent change of a budget or the partition rule fails these instead of
 * following the mutation (JVM 装置自指陷阱, 2026-09-26).
 */
class CompactQuietFirstTest {

    private fun quietModel(id: String = "quiet") = LLMModel(
        id = id, displayName = id, provider = "p", contextWindow = 8192,
    )

    private fun noisyModel(id: String) = LLMModel(
        id = id, displayName = id, provider = "p",
        contextWindow = 8192, declaresNoEffortTiers = true,
    )

    private fun unknownModel(id: String) = LLMModel(
        id = id, displayName = id, provider = "p",
        contextWindow = 8192, declaresNoEffortTiers = null,
    )

    private fun provider(model: LLMModel) = StubProvider(model)

    private fun fb(id: String, model: LLMModel) =
        FallbackCandidate(provider(model), "entry-$id")

    // ── ordering ──────────────────────────────────────────────────────

    @Test
    fun `all quiet keeps router order with active first`() {
        val active = provider(quietModel("active"))
        val chain = ordersCompactionCandidates(
            active,
            listOf(fb("f1", quietModel("f1")), fb("f2", quietModel("f2"))),
        )
        assertEquals(listOf("active", "f1", "f2"), chain.map { it.first.model.displayName })
        assertSame(active, chain[0].first)
        assertEquals(listOf(null, "entry-f1", "entry-f2"), chain.map { it.second })
    }

    @Test
    fun `noisy active is demoted behind quiet fallbacks`() {
        val active = provider(noisyModel("active-noisy"))
        val chain = ordersCompactionCandidates(
            active,
            listOf(fb("f1", quietModel("f1")), fb("f2", quietModel("f2"))),
        )
        // 2026-10-01 incident shape: active burns 71s on a thinking relay —
        // quiet members must be tried first, active stays last-resort.
        assertEquals(listOf("f1", "f2", "active-noisy"), chain.map { it.first.model.displayName })
        assertSame(active, chain[2].first)
    }

    @Test
    fun `quiet flag null means quiet and never demotes`() {
        // Absence of catalog evidence must not change today's behaviour —
        // only declaresNoEffortTiers==true demotes.
        val active = provider(unknownModel("active-unknown"))
        val chain = ordersCompactionCandidates(active, listOf(fb("f1", noisyModel("f1-noisy"))))
        assertEquals(listOf("active-unknown", "f1-noisy"), chain.map { it.first.model.displayName })
    }

    @Test
    fun `mixed partitions stay stable and active leads its own partition`() {
        val active = provider(noisyModel("a-noisy"))
        val chain = ordersCompactionCandidates(
            active,
            listOf(
                fb("n1", noisyModel("n1")),
                fb("q1", quietModel("q1")),
                fb("n2", noisyModel("n2")),
            ),
        )
        // quiet group first (router order preserved); noisy group after,
        // stable — active came before n1/n2 in the source list so it leads.
        assertEquals(listOf("q1", "a-noisy", "n1", "n2"), chain.map { it.first.model.displayName })
    }

    @Test
    fun `chain is capped at one plus the fallback limit`() {
        val active = provider(quietModel("active"))
        val many = (1..10).map { fb("f$it", quietModel("f$it")) }
        val chain = ordersCompactionCandidates(active, many)
        assertEquals(4, chain.size) // literal cap: 1 + COMPACT_SUMMARY_FALLBACK_LIMIT(3)
        assertSame(active, chain[0].first)
    }

    @Test
    fun `active only when no fallbacks exist`() {
        val active = provider(noisyModel("a"))
        val chain = ordersCompactionCandidates(active, emptyList())
        assertEquals(1, chain.size)
        assertSame(active, chain[0].first)
    }

    // ── budgets (literal, not derived) ────────────────────────────────

    @Test
    fun `summary wall budgets are the measured values`() {
        assertEquals(30_000L, ChatViewModel.COMPACT_SUMMARY_CANDIDATE_BUDGET_MS)
        assertEquals(3, ChatViewModel.COMPACT_SUMMARY_FALLBACK_LIMIT)
        assertEquals(120_000L, ChatViewModel.COMPACT_SUMMARY_TOTAL_BUDGET_MS)
    }

    // ── wiring: the call site actually consumes the chain + budgets ───
    //
    // generateCompactSummary lives on ChatViewModel (uninstantiable in JVM),
    // so the integration is pinned on source fragments — order of named
    // fragments only, per the source-text-assertion lesson (2026-10-01:
    // judge sequence, never "A appears before B" across constructs).

    @Test
    fun `compaction call site wires the quiet-first chain and budgets`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt")
        val atBudgetCall = src.indexOf("withTimeoutOrNull(budgetMs) { sendVia(candidate) }")
        val atChainBuild = src.indexOf(
            "ordersCompactionCandidates(provider, buildFallbackProviders(provider))",
        )
        val atDeadline = src.indexOf("COMPACT_SUMMARY_TOTAL_BUDGET_MS")
        val atPerCandidate = src.indexOf("COMPACT_SUMMARY_CANDIDATE_BUDGET_MS")
        val atThrow = src.indexOf("throw lastFailure")
        for ((name, idx) in listOf(
            "chain build" to atChainBuild, "deadline" to atDeadline,
            "per-candidate budget" to atPerCandidate, "budget-wrapped send" to atBudgetCall,
            "throw" to atThrow,
        )) {
            assertTrue("call site missing $name", idx >= 0)
        }
        // Sequence: chain built before any budget is consumed; budgets before
        // the wrapped send; send loop before the terminal throw.
        assertTrue(atChainBuild < atBudgetCall)
        assertTrue(atDeadline < atBudgetCall)
        assertTrue(atBudgetCall < atThrow)
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

/** Minimal LLMProvider carrying only what the ordering reads. */
private class StubProvider(
    override var model: LLMModel,
) : LLMProvider {
    override val name: String = "stub"
    override var instanceContext: com.rikkaminis.app.data.model.ProviderInstance? = null

    override suspend fun sendMessageClamped(
        messages: List<com.rikkaminis.app.data.model.LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<com.rikkaminis.app.data.model.LLMMessage.ImagePart>,
        tools: List<com.rikkaminis.app.data.model.AgentToolDefinition>,
        thinkingLevel: com.rikkaminis.app.data.model.ThinkingLevel,
    ): com.rikkaminis.app.data.model.LLMResponse =
        com.rikkaminis.app.data.model.LLMResponse(text = "", stopReason = null, usage = null)

    override fun streamMessageClamped(
        messages: List<com.rikkaminis.app.data.model.LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<com.rikkaminis.app.data.model.LLMMessage.ImagePart>,
        tools: List<com.rikkaminis.app.data.model.AgentToolDefinition>,
        thinkingLevel: com.rikkaminis.app.data.model.ThinkingLevel,
    ) = kotlinx.coroutines.flow.emptyFlow<com.rikkaminis.app.data.model.LLMStreamChunk>()
}
