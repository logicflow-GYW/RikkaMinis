package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.provider.LLMProvider
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [feat/compact-model-pin-1005]: [prependCompactionPin]
 * semantics — pin-first, both selection modes, dedup, active-is-pin
 * override, stale-pin no-op — plus the chain-build wiring in
 * CompactOrchestration.kt (source-text pin, same pattern as
 * [CompactQuietFirstTest]).
 *
 * Expected values are literal — never derived from the tested code.
 */
class CompactModelPinTest {

    private fun model(id: String, noisy: Boolean = false) = LLMModel(
        id = id, displayName = id, provider = "p", contextWindow = 8192,
        declaresNoEffortTiers = if (noisy) true else null,
    )

    private fun provider(id: String, noisy: Boolean = false) =
        CompactPinStubProvider(model(id, noisy))

    private fun fb(id: String, noisy: Boolean = false) =
        FallbackCandidate(provider(id), "entry-$id")

    // ── empty pin = byte-identical legacy chain ──────────────────────

    @Test
    fun `blank pin yields plain quiet-first chain`() {
        val active = provider("active")
        val fallbacks = listOf(fb("f1"))
        val chain = prependCompactionPin("", "entry-active", active, fallbacks)
        assertEquals(ordersCompactionCandidates(active, fallbacks), chain)
    }

    @Test
    fun `null pin yields plain quiet-first chain`() {
        val active = provider("active")
        val chain = prependCompactionPin(null, null, active, emptyList())
        assertEquals(1, chain.size)
        assertSame(active, chain[0].first)
        assertEquals(null, chain[0].second)
    }

    // ── pin inside the group: prepend + dedup ────────────────────────

    @Test
    fun `pinned group member moves to head and is deduped`() {
        val active = provider("active")
        val chain = prependCompactionPin(
            "entry-f2", "entry-active", active,
            listOf(fb("f1"), fb("f2"), fb("f3")),
        )
        assertEquals(listOf("f2", "active", "f1", "f3"), chain.map { it.first.model.displayName })
        assertEquals(listOf("entry-f2", null, "entry-f1", "entry-f3"), chain.map { it.second })
    }

    @Test
    fun `pinned noisy member still leads the chain`() {
        // User's explicit pick outranks the catalog quiet-first demotion.
        val active = provider("active")
        val chain = prependCompactionPin(
            "entry-noisy", "entry-active", active,
            listOf(fb("quiet"), fb("noisy", noisy = true)),
        )
        assertEquals("noisy", chain[0].first.model.displayName)
        assertEquals("entry-noisy", chain[0].second)
        assertEquals("active", chain[1].first.model.displayName)
        assertEquals("quiet", chain[2].first.model.displayName)
    }

    @Test
    fun `pin equal to a fallback removes the duplicate`() {
        val active = provider("active")
        val chain = prependCompactionPin(
            "entry-f1", "entry-active", active,
            listOf(fb("f1"), fb("f1")),
        )
        val ids = chain.map { it.second }
        assertEquals(1, ids.count { it == "entry-f1" })
        assertEquals("entry-f1", ids[0])
    }

    // ── pin IS the active member: pin outranks quiet-first demotion ──

    @Test
    fun `pin equal to noisy active leads the chain anyway`() {
        val active = provider("active-noisy", noisy = true)
        val chain = prependCompactionPin(
            "entry-active", "entry-active", active,
            listOf(fb("q1"), fb("q2")),
        )
        // Without the pin, quiet-first would put active last; the pin is an
        // explicit user choice, so the active slot leads.
        assertEquals("active-noisy", chain[0].first.model.displayName)
        assertEquals(null, chain[0].second)
        assertEquals(listOf("q1", "q2"), chain.drop(1).map { it.first.model.displayName })
    }

    @Test
    fun `pin equal to quiet active keeps chain unchanged`() {
        val active = provider("active")
        val fallbacks = listOf(fb("f1"))
        val chain = prependCompactionPin("entry-active", "entry-active", active, fallbacks)
        assertEquals(ordersCompactionCandidates(active, fallbacks), chain)
    }

    // ── stale pin: silent no-op ──────────────────────────────────────

    @Test
    fun `stale pin without resolver yields unchanged chain`() {
        val active = provider("active")
        val fallbacks = listOf(fb("f1"))
        val chain = prependCompactionPin("entry-gone", "entry-active", active, fallbacks)
        assertEquals(ordersCompactionCandidates(active, fallbacks), chain)
    }

    // ── global resolver branch (direct-entry mode / cross-group pin) ──

    @Test
    fun `resolver hit prepends external provider ahead of chain`() {
        val active = provider("active")
        val external = provider("pinned-external")
        val chain = prependCompactionPin(
            "entry-ext", "entry-active", active,
            listOf(fb("f1")),
            resolvePin = { id -> if (id == "entry-ext") external else null },
        )
        assertEquals(3, chain.size) // pin + full session chain (active, f1)
        assertSame(external, chain[0].first)
        assertEquals("entry-ext", chain[0].second)
        assertSame(active, chain[1].first)
        assertEquals("entry-f1", chain[2].second)
    }

    @Test
    fun `resolver miss yields unchanged chain`() {
        val active = provider("active")
        val fallbacks = listOf(fb("f1"))
        val chain = prependCompactionPin(
            "entry-ext", "entry-active", active,
            fallbacks,
            resolvePin = { null },
        )
        assertEquals(ordersCompactionCandidates(active, fallbacks), chain)
    }

    @Test
    fun `resolver result wins over same-entryId fallback duplicate`() {
        // entryId match in the group list takes priority; resolver is only
        // consulted when the pin is NOT among the fallbacks.
        val active = provider("active")
        var resolverCalled = false
        val chain = prependCompactionPin(
            "entry-f1", "entry-active", active,
            listOf(fb("f1")),
            resolvePin = { resolverCalled = true; provider("wrong") },
        )
        assertEquals("f1", chain[0].first.model.displayName)
        assertEquals(false, resolverCalled)
    }

    // ── budgets untouched (literal) ──────────────────────────────────

    @Test
    fun `pin does not change budget constants`() {
        assertEquals(30_000L, ChatViewModel.COMPACT_SUMMARY_CANDIDATE_BUDGET_MS)
        assertEquals(60_000L, ChatViewModel.COMPACT_SUMMARY_NOISY_CANDIDATE_BUDGET_MS)
        assertEquals(120_000L, ChatViewModel.COMPACT_SUMMARY_TOTAL_BUDGET_MS)
        assertEquals(3, ChatViewModel.COMPACT_SUMMARY_FALLBACK_LIMIT)
    }

    // ── wiring: the chain build actually consumes the pin ────────────

    @Test
    fun `compaction chain build wires the pin with resolver and fallback log`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt")
        val atPinRead = src.indexOf("AgentRuntimeLimitsPrefs.compactModelEntryId()")
        val atPrepend = src.indexOf("prependCompactionPin(")
        val atResolver = src.indexOf("resolveCompactionPinProvider(entryId)")
        val atFallbackLog = src.indexOf("not reachable — following session chain")
        for ((name, idx) in listOf(
            "pin read" to atPinRead, "prepend call" to atPrepend,
            "global resolver" to atResolver, "stale-pin log" to atFallbackLog,
        )) {
            assertTrue("call site missing $name", idx >= 0)
        }
        assertTrue(atPinRead < atPrepend)
        assertTrue(atPrepend < atResolver)
    }

    // ── follow-up review fixes: health gate + reachability guard ─────

    @Test
    fun `resolver applies the group-router health gate`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt")
        val atHealthGate = src.indexOf("groupRouter.isUsable(entryId)")
        val atResolverDecl = src.indexOf("fun ChatViewModel.resolveCompactionPinProvider(entryId: String)")
        assertTrue("health gate missing in resolver", atHealthGate >= 0)
        assertTrue("health gate must sit inside the resolver", atResolverDecl >= 0 && atResolverDecl < atHealthGate)
    }

    @Test
    fun `reachability guard counts the active-pin case`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/conversation/CompactOrchestration.kt")
        assertTrue("active-pin reachability guard missing", src.indexOf("pinId == _activeEntryId.value") >= 0)
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

/** Minimal LLMProvider carrying only what the ordering reads. */
private class CompactPinStubProvider(
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
    ) = emptyFlow<com.rikkaminis.app.data.model.LLMStreamChunk>()
}
