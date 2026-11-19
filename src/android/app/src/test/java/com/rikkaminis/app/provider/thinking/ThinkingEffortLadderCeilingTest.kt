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
 * the provider itself (`LLMProvider.requestScope`), so the two can never disagree about
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
            "EffortTierLearner.learnedWithinOrNull(" in block,
        )
        assertTrue(
            // [fix/effort-truth-1119b] 4 F-01: the raw `learned()` read lifted the ceiling
            // to tiers the model never declared. The narrowed read is only narrowed if the
            // declaration is actually passed in — the call and its third argument are one
            // decision, so they are pinned together.
            "the learned set must be read NARROWED by the model's declaration, " +
                "otherwise the picker advertises a tier the wire will downgrade",
            "declaredEffortValues," in block && "declaredEffortValues: List<String>?" in block,
        )
        assertTrue(
            "the learned set must be folded into the declared ceiling, not replace it",
            "ThinkingEffortLadder.lowerCeiling(" in block,
        )
        // Both ceiling sources (active entry and the no-entry fallback) go through the
        // fold; a single call site means one of the two paths still reports the catalog.
        val getterAt = code.indexOf("internal val currentModelMaxThinkingLevel")
        assertTrue("currentModelMaxThinkingLevel not found", getterAt >= 0)
        // The call may be wrapped across lines, so each anchor is whitespace-tolerant:
        // what is being pinned is WHICH ceiling goes through the fold, not the line breaks.
        val entryAt = Regex("withLearnedCeiling\\(\\s*entry\\.effectiveMaxThinkingLevel")
            .find(code)?.range?.first ?: -1
        val modelAt = Regex("withLearnedCeiling\\(\\s*model\\.catalogMaxThinkingLevel")
            .find(code)?.range?.first ?: -1
        assertTrue(
            "the active-entry ceiling must be folded",
            entryAt > getterAt,
        )
        assertTrue(
            "the no-entry fallback ceiling must be folded too",
            modelAt > getterAt,
        )
    }

    @Test
    fun `both ceiling sources hand the narrowing the declaration the wire reads`() {
        // [fix/effort-truth-1119b] 4 F-01. Two call sites, two DIFFERENT declarations:
        // the active entry must be enriched first (`entry.model` alone never touches
        // models.dev, so every built-in row would answer null and the narrowing would be
        // a no-op), while the no-entry fallback already holds the enriched model.
        val code = code(CHAT_VM)
        val entryCall = Regex("withLearnedCeiling\\(\\s*entry\\.effectiveMaxThinkingLevel([\\s\\S]{0,220})")
            .find(code)?.groupValues?.get(1) ?: ""
        assertTrue(
            "the active-entry ceiling must pass the ENRICHED declaration — `entry.model` " +
                "skips models.dev and silently disables the narrowing: $entryCall",
            entryCall.contains("declaredEffortValues = ModelsDevApi.effectiveModel(entry).reasoningEffortValues"),
        )
        val modelCall = Regex("withLearnedCeiling\\(\\s*model\\.catalogMaxThinkingLevel([\\s\\S]{0,220})")
            .find(code)?.groupValues?.get(1) ?: ""
        assertTrue(
            "the no-entry fallback must pass its model's own declaration: $modelCall",
            modelCall.contains("declaredEffortValues = model.reasoningEffortValues"),
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
    fun `the learned scope endpoint comes from the provider, not from customBaseURL`() {
        // Reading instance.customBaseURL would return null for every built-in endpoint
        // (it is the OVERRIDE, not the effective base), silently disabling the fold.
        //
        // [fix/effort-key-1119] the surface is `requestScope`, not `requestHost`: a
        // bare host drops the port and the path prefix, which is exactly how two
        // gateways on one host came to share one learned ceiling. These assertions
        // pin the NEW shape — the pre-fix tree fails them on the spelling alone.
        val iface = code(LLM_PROVIDER)
        assertTrue(
            "LLMProvider must expose the endpoint scope it actually sends to",
            "val requestScope: String? get() = null" in iface,
        )
        val openai = code(OPENAI)
        assertTrue(
            "OpenAIProvider must answer requestScope from its own normalized basePath",
            "override val requestScope: String? get() = effortScope ?: basePath.toHttpUrlOrNull()?.host" in openai,
        )
        // The scope is normalized INSIDE the learner, so the provider only has to hand
        // it the raw base URL: `scopeOf` is what keeps ports and path prefixes in.
        assertTrue(
            "the scope must be produced by EffortTierLearner.scopeOf, not a bare host",
            "EffortTierLearner.scopeOf(basePath)" in openai,
        )
        val vm = code(CHAT_VM)
        assertTrue(
            "the ceiling fold must take the endpoint scope from the provider",
            "provider.requestScope" in vm,
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

    // ── [fix/effort-truth-1119b] 4 F-01: the fold is driven by the NARROWED read ──
    //
    // `withLearnedCeiling` itself is private to ChatViewModel (Android deps), so its two
    // legs are pinned separately and driven end to end here: the REAL learner produces the
    // set, the REAL `lowerCeiling` folds it — the same two calls the source makes, in the
    // same order. The shape test above is what proves the wiring points at these two.

    private val ceiling400 =
        """{"error":{"message":"Thinking level is unsupported: THINKING_LEVEL_XHIGH","type":"api_error"}}"""

    @Test
    fun `a learned ceiling can not lift the picker above the declaration`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ceiling-${java.util.UUID.randomUUID()}")
        EffortTierLearner.initFile(File(dir, "gateway_efforts.json"))
        try {
            val scope = "https://gw.example/v1"
            // Learned: the whole ladder prefix below the refused XHIGH (none..high).
            // Declared: `low, medium` — a model with no `high`.
            EffortTierLearner.record(scope, """{"model":"m1"}""", ceiling400)
            val learnedRaw = EffortTierLearner.learned(scope, "m1")
            val learned = EffortTierLearner.learnedWithinOrNull(scope, "m1", listOf("low", "medium"))
            assertEquals(listOf("low", "medium"), learned)

            // The control: the raw read would advertise HIGH — the tier the wire clamps
            // back to medium, i.e. a picker that is simply wrong.
            assertEquals(ThinkingLevel.HIGH, ThinkingEffortLadder.lowerCeiling(ThinkingLevel.XHIGH, learnedRaw))
            // The fix: the result stays inside the declaration.
            assertEquals(ThinkingLevel.MEDIUM, ThinkingEffortLadder.lowerCeiling(ThinkingLevel.XHIGH, learned))
        } finally {
            EffortTierLearner.reset()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an enumerated answer keeps the picker's ceiling untouched by the declaration`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ceiling-${java.util.UUID.randomUUID()}")
        EffortTierLearner.initFile(File(dir, "gateway_efforts.json"))
        try {
            val scope = "https://gw.example/v1"
            // One-hand truth: the gateway spelled out a set wider than the catalog.
            EffortTierLearner.record(
                scope, """{"model":"m2"}""",
                """{"error":"field ReasoningEffort invalid, should be one of: low, high, xhigh"}""",
            )
            val learned = EffortTierLearner.learnedWithinOrNull(scope, "m2", listOf("low"))
            assertEquals(listOf("low", "high", "xhigh"), learned)
            assertEquals(ThinkingLevel.XHIGH, ThinkingEffortLadder.lowerCeiling(ThinkingLevel.XHIGH, learned))
        } finally {
            EffortTierLearner.reset()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `nothing learned leaves the fold without a ceiling to fold`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "ceiling-${java.util.UUID.randomUUID()}")
        EffortTierLearner.initFile(File(dir, "gateway_efforts.json"))
        try {
            // `learnedWithinOrNull` null, not the declaration: this is what keeps
            // `?: return declared` byte-identical when the gateway has said nothing.
            assertNull(EffortTierLearner.learnedWithinOrNull("https://gw.example/v1", "m3", listOf("high")))
            assertEquals(ThinkingLevel.XHIGH, ThinkingEffortLadder.lowerCeiling(ThinkingLevel.XHIGH, null))
        } finally {
            EffortTierLearner.reset()
            dir.deleteRecursively()
        }
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
