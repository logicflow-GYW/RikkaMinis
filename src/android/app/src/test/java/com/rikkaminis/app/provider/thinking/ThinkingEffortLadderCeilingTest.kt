package com.rikkaminis.app.provider.thinking

import com.rikkaminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [fix/thinking-level-ceiling-1118] The DISPLAY side of the learned effort ceiling.
 *
 * Before this, a gateway that refused `xhigh` left no trace in the UI: the clamp lived
 * in the provider, so the composer kept offering XHigh while the request went out as
 * High — a silent downgrade. These tests pin (a) the pure fold that lowers a catalog
 * ceiling to what was actually accepted, and (b) the wiring that carries it from the
 * learner into the picker (`ChatViewModel.withLearnedCeiling`) and takes the host from
 * the provider itself (`LLMProvider.requestHost`), so the two can never disagree about
 * which scope they are reading.
 *
 * The wiring half is source-shape based on purpose: every assertion below is written so
 * the PRE-FIX tree fails it, comments are stripped before matching, and each one anchors
 * on the decision (which value is read where) rather than on an identifier existing.
 */
class ThinkingEffortLadderCeilingTest {

    // ── highestLevelFor ──────────────────────────────────────────────────────────

    @Test
    fun `the tier learned from a rejected xhigh maps back to HIGH`() {
        // Exactly what ceilingFromText stores for THINKING_LEVEL_XHIGH.
        assertEquals(
            ThinkingLevel.HIGH,
            ThinkingEffortLadder.highestLevelFor(listOf("none", "minimal", "low", "medium", "high")),
        )
    }

    @Test
    fun `a set that still names max maps to MAX`() {
        assertEquals(ThinkingLevel.MAX, ThinkingEffortLadder.highestLevelFor(listOf("low", "max")))
        assertEquals(ThinkingLevel.XHIGH, ThinkingEffortLadder.highestLevelFor(listOf("xhigh")))
        assertEquals(ThinkingLevel.MEDIUM, ThinkingEffortLadder.highestLevelFor(listOf("minimal", "medium")))
    }

    @Test
    fun `off-tier-only sets and empty sets carry no ceiling`() {
        // "none"/"minimal" have no client counterpart; returning OFF here would empty
        // the picker (availableThinkingLevels excludes OFF), so the caller must keep
        // the catalog ceiling instead.
        assertNull(ThinkingEffortLadder.highestLevelFor(listOf("none")))
        assertNull(ThinkingEffortLadder.highestLevelFor(listOf("none", "minimal")))
        assertNull(ThinkingEffortLadder.highestLevelFor(emptyList()))
        assertNull(ThinkingEffortLadder.highestLevelFor(null))
    }

    @Test
    fun `unknown vocabulary is ignored instead of defaulting to a tier`() {
        assertNull(ThinkingEffortLadder.highestLevelFor(listOf("turbo", "zzz")))
        assertEquals(ThinkingLevel.HIGH, ThinkingEffortLadder.highestLevelFor(listOf("turbo", "high")))
    }

    @Test
    fun `tier matching is case insensitive`() {
        assertEquals(ThinkingLevel.HIGH, ThinkingEffortLadder.highestLevelFor(listOf("HIGH")))
    }

    // ── lowerCeiling ─────────────────────────────────────────────────────────────

    @Test
    fun `a learned ceiling lowers the catalog ceiling`() {
        assertEquals(
            ThinkingLevel.HIGH,
            ThinkingEffortLadder.lowerCeiling(ThinkingLevel.XHIGH, listOf("none", "low", "medium", "high")),
        )
    }

    @Test
    fun `learning never raises a ceiling`() {
        // The gateway's enum can name tiers the catalog does not; MAX must not lift a
        // HIGH-ceilinged model just because some model on that host accepted "max".
        assertEquals(
            ThinkingLevel.HIGH,
            ThinkingEffortLadder.lowerCeiling(ThinkingLevel.HIGH, listOf("low", "max")),
        )
    }

    @Test
    fun `nothing learned leaves the catalog ceiling untouched`() {
        assertEquals(ThinkingLevel.XHIGH, ThinkingEffortLadder.lowerCeiling(ThinkingLevel.XHIGH, null))
        assertEquals(ThinkingLevel.MAX, ThinkingEffortLadder.lowerCeiling(ThinkingLevel.MAX, emptyList()))
        // Off-tier-only sets also leave it untouched (see highestLevelFor).
        assertEquals(ThinkingLevel.MEDIUM, ThinkingEffortLadder.lowerCeiling(ThinkingLevel.MEDIUM, listOf("none")))
    }

    @Test
    fun `AUTO is exempt from the learned ceiling`() {
        // AUTO carries no intensity; the picker already exempts it from the ceiling,
        // and lowering it here would make the row disagree with availableThinkingLevels.
        assertEquals(
            ThinkingLevel.AUTO,
            ThinkingEffortLadder.lowerCeiling(ThinkingLevel.AUTO, listOf("low")),
        )
    }

    @Test
    fun `ULTRA folds down to the highest learned tier that has a client counterpart`() {
        // ULTRA is client-side only (wire = "max"); a learned "max" therefore caps it
        // at MAX, not at ULTRA.
        assertEquals(
            ThinkingLevel.MAX,
            ThinkingEffortLadder.lowerCeiling(ThinkingLevel.ULTRA, listOf("low", "max")),
        )
    }

    // ── wiring: learner -> picker, and one shared host ───────────────────────────

    @Test
    fun `the composer ceiling is folded through the learned set`() {
        val code = code(CHAT_VM)
        val at = code.indexOf("private fun withLearnedCeiling(")
        assertTrue("withLearnedCeiling not found in $CHAT_VM", at >= 0)
        val block = code.substring(at, minOf(code.length, at + 1600))
        assertTrue(
            "the picker ceiling no longer consults the gateway's own answer — a learned " +
                "downgrade would again be invisible in the composer while the wire clamps",
            "EffortTierLearner.learned(" in block,
        )
        assertTrue(
            "the learned set must be folded into the declared ceiling, not replace it",
            "ThinkingEffortLadder.lowerCeiling(" in block,
        )
        // Both ceiling sources (active entry and the no-entry fallback) go through the
        // fold; a single call site means one of the two paths still reports the catalog.
        val getterAt = code.indexOf("internal val currentModelMaxThinkingLevel")
        assertTrue("currentModelMaxThinkingLevel not found", getterAt >= 0)
        val getter = code.substring(getterAt, minOf(code.length, getterAt + 1600))
        assertTrue(
            "the active-entry ceiling must be folded",
            "withLearnedCeiling(entry.effectiveMaxThinkingLevel" in getter,
        )
        assertTrue(
            "the no-entry fallback ceiling must be folded too",
            "withLearnedCeiling(model.catalogMaxThinkingLevel" in getter,
        )
    }

    @Test
    fun `the composable list is derived from that ceiling`() {
        val code = code(CHAT_VM)
        val at = code.indexOf("val availableThinkingLevels")
        assertTrue("availableThinkingLevels not found", at >= 0)
        val block = code.substring(at, minOf(code.length, at + 500))
        assertTrue(
            "the picker list must keep reading the folded ceiling, otherwise the fold " +
                "only affects what is sent",
            "currentModelMaxThinkingLevel" in block,
        )
    }

    @Test
    fun `the learned scope host comes from the provider, not from customBaseURL`() {
        // Reading instance.customBaseURL would return null for every built-in endpoint
        // (it is the OVERRIDE, not the effective base), silently disabling the fold.
        val iface = code(LLM_PROVIDER)
        assertTrue(
            "LLMProvider must expose the host it actually sends to",
            "val requestHost: String? get() = null" in iface,
        )
        val openai = code(OPENAI)
        assertTrue(
            "OpenAIProvider must answer requestHost from its own basePath",
            "override val requestHost: String? get() = basePath.toHttpUrlOrNull()?.host" in openai,
        )
        val vm = code(CHAT_VM)
        assertTrue(
            "the ceiling fold must take the host from the provider",
            "provider.requestHost" in vm,
        )
        assertTrue(
            "the ceiling fold must key on the model the provider will send, not the snapshot",
            "provider.model.id" in vm,
        )
    }

    @Test
    fun `the level flow refreshes at turn boundaries`() {
        // The badge reads this StateFlow, not the getter. Learning happens in the
        // worker after a failed request, so without a source that emits when the turn
        // ends the cached value survives the whole session: the picker listed High
        // while the navbar kept rendering XHigh.
        val code = code(CHAT_VM)
        val at = code.indexOf("val thinkingLevel: StateFlow<ThinkingLevel> = combine(")
        assertTrue("thinkingLevel StateFlow not found", at >= 0)
        val block = code.substring(at, minOf(code.length, at + 700))
        assertTrue(
            "the cached level flow must re-derive after a turn — otherwise a learned " +
                "ceiling updates the picker but not the badge",
            "isStreaming" in block,
        )
    }

    @Test
    fun `the level flow re-derives when the provider is swapped`() {
        // The switch paths assign `_activeEntryId` and then `currentProvider`. On
        // Dispatchers.Main.immediate the entry emission can recompute the flow while the
        // provider is still the previous member — and because `currentProvider` was a
        // plain var, the second assignment emitted nothing, so the badge kept the OLD
        // member's learned ceiling for the rest of the session. That is the reported
        // "one model downgraded, every other member followed".
        val code = code(CHAT_VM)
        val flowAt = code.indexOf("val thinkingLevel: StateFlow<ThinkingLevel> = combine(")
        assertTrue("thinkingLevel StateFlow not found", flowAt >= 0)
        val flowBlock = code.substring(flowAt, minOf(code.length, flowAt + 900))
        assertTrue(
            "the level flow must observe the provider's own generation, otherwise the " +
                "second half of a model switch never reaches it",
            "_providerGeneration" in flowBlock,
        )
        val genAt = code.indexOf("private val _providerGeneration")
        assertTrue("_providerGeneration not found", genAt >= 0)
        val setter = code.substring(genAt, minOf(code.length, genAt + 900))
        assertTrue(
            "currentProvider must bump the generation on every assignment",
            "set(value)" in setter && "_providerGeneration.value = _providerGeneration.value + 1" in setter,
        )
    }

    @Test
    fun `a provider that has not caught up never lends its ceiling`() {
        val code = code(CHAT_VM)
        val at = code.indexOf("private fun withLearnedCeiling(")
        assertTrue("withLearnedCeiling not found", at >= 0)
        val block = code.substring(at, minOf(code.length, at + 1600))
        assertTrue(
            "while the provider still fronts the previous member, its learned ceiling " +
                "must not be applied to the newly selected model",
            "provider.model.id != expectedModelId" in block,
        )
    }

    private fun code(relative: String): String = stripComments(readRepoFile(relative))

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** Same stripper as MetaR3B08WiringTest: a KDoc mention must not satisfy a match. */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inBlock = false
        var inLine = false
        var inString = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                inLine -> if (c == '\n') {
                    inLine = false
                    sb.append(c)
                }
                inBlock -> if (c == '*' && next == '/') {
                    inBlock = false
                    i += 1
                }
                inString -> {
                    sb.append(c)
                    if (c == '\\') {
                        sb.append(next)
                        i += 1
                    } else if (c == '"') {
                        inString = false
                    }
                }
                else -> when {
                    c == '"' -> {
                        inString = true
                        sb.append(c)
                    }
                    c == '/' && next == '/' -> {
                        inLine = true
                        i += 1
                    }
                    c == '/' && next == '*' -> {
                        inBlock = true
                        i += 1
                    }
                    else -> sb.append(c)
                }
            }
            i += 1
        }
        return sb.toString()
    }

    private companion object {
        const val BASE = "src/android/app/src/main/java/com/rikkaminis/app/"
        const val CHAT_VM = BASE + "ui/chat/ChatViewModel.kt"
        const val LLM_PROVIDER = BASE + "provider/LLMProvider.kt"
        const val OPENAI = BASE + "provider/openai/OpenAIProvider.kt"
    }
}
