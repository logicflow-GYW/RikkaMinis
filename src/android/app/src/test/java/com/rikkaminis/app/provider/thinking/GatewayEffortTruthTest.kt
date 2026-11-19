package com.rikkaminis.app.provider.thinking

import com.rikkaminis.app.data.model.ThinkingLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [T-sensenova-effort-enum] Pins the gateway-measured effort enums and the wire decision
 * built on them.
 *
 * The enums are empirical (probed live 2026-09-23 — `GatewayEffortTruth` documents the
 * one-request method). These tests exist so a future "tidy-up" of the table cannot
 * silently reintroduce the collapse users reported: the picker drew LOW / MEDIUM / HIGH
 * and the wire carried ONE identical value for all three, so choosing a tier changed
 * nothing.
 */
class GatewayEffortTruthTest {

    private val host = "token.sensenova.cn"

    private lateinit var dir: File
    private lateinit var effortsFile: File

    @Before
    fun setUpLearner() {
        // An initialized-but-empty learner: `entryFor` really runs (it would return null
        // on a null target without even looking), which is what makes the "nothing
        // learned" arms below mean something.
        dir = File(System.getProperty("java.io.tmpdir"), "get-${java.util.UUID.randomUUID()}")
        effortsFile = File(dir, "gateway_efforts.json")
        EffortTierLearner.initFile(effortsFile)
    }

    @After
    fun tearDownLearner() {
        EffortTierLearner.reset()
        dir.deleteRecursively()
    }

    @Test
    fun `only the measured hosts are treated as sensenova`() {
        assertTrue(GatewayEffortTruth.isSensenovaHost("token.sensenova.cn"))
        assertTrue(GatewayEffortTruth.isSensenovaHost("api.sensenova.cn"))
        assertFalse(GatewayEffortTruth.isSensenovaHost("api.openai.com"))
        assertFalse(GatewayEffortTruth.isSensenovaHost("token.sensenova.cn.evil.test"))
        assertFalse(GatewayEffortTruth.isSensenovaHost(""))
    }

    @Test
    fun `other hosts are not covered by the table`() {
        assertNull(GatewayEffortTruth.tiersFor("api.openai.com", "glm-5.2"))
        assertNull(GatewayEffortTruth.tiersFor("localhost", "sensenova-6.8-flash-lite"))
    }

    @Test
    fun `the enum is per model, not per host`() {
        // Same gateway: glm-5.2 takes "max", sensenova-6.8-flash-lite 400s on it.
        // This is why the host table can no longer emit one hard-coded value.
        val glm = GatewayEffortTruth.tiersFor(host, "glm-5.2")
        val lite = GatewayEffortTruth.tiersFor(host, "sensenova-6.8-flash-lite")
        assertTrue(glm!!.contains("max"))
        assertFalse(lite!!.contains("max"))
        assertTrue(lite.contains("xhigh"))
    }

    @Test
    fun `measured tables match the probe output`() {
        assertEquals(
            listOf("none", "low", "medium", "high", "xhigh"),
            GatewayEffortTruth.tiersFor(host, "sensenova-6.8-flash-lite"),
        )
        assertEquals(
            listOf("none", "low", "medium", "high", "xhigh"),
            GatewayEffortTruth.tiersFor(host, "deepseek-v4-flash"),
        )
        assertEquals(
            listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"),
            GatewayEffortTruth.tiersFor(host, "glm-5.2"),
        )
        assertEquals(
            listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
            GatewayEffortTruth.tiersFor(host, "deepseek-v4-pro"),
        )
    }

    @Test
    fun `every enabled tier keeps its own wire value on a five-tier model`() {
        // The reported symptom: three distinct picker options produced one request.
        val id = "sensenova-6.8-flash-lite"
        assertEquals("low", GatewayEffortTruth.resolveTier(host, id, ThinkingLevel.LOW, null))
        assertEquals("medium", GatewayEffortTruth.resolveTier(host, id, ThinkingLevel.MEDIUM, null))
        assertEquals("high", GatewayEffortTruth.resolveTier(host, id, ThinkingLevel.HIGH, null))
        assertEquals("xhigh", GatewayEffortTruth.resolveTier(host, id, ThinkingLevel.XHIGH, null))
        // MAX sits above this model's enum: degrade downward, never 400.
        assertEquals("xhigh", GatewayEffortTruth.resolveTier(host, id, ThinkingLevel.MAX, null))
    }

    @Test
    fun `a model whose enum includes max actually gets max`() {
        assertEquals("max", GatewayEffortTruth.resolveTier(host, "glm-5.2", ThinkingLevel.MAX, null))
        assertEquals("xhigh", GatewayEffortTruth.resolveTier(host, "glm-5.2", ThinkingLevel.XHIGH, null))
        assertEquals("low", GatewayEffortTruth.resolveTier(host, "glm-5.2", ThinkingLevel.LOW, null))
        // ULTRA has no picker tier; it maps to the ladder top.
        assertEquals("max", GatewayEffortTruth.resolveTier(host, "glm-5.2", ThinkingLevel.ULTRA, null))
    }

    @Test
    fun `measurement outranks a stale declared set`() {
        // Older saves carry models.dev's under-declared row (no xhigh). The measurement
        // must win, otherwise the very tier the user could not reach stays lost.
        assertEquals(
            "xhigh",
            GatewayEffortTruth.resolveTier(
                host, "sensenova-6.8-flash-lite", ThinkingLevel.XHIGH,
                listOf("none", "low", "medium", "high"),
            ),
        )
    }

    @Test
    fun `unknown model falls back to the declared set`() {
        assertEquals(
            "high",
            GatewayEffortTruth.resolveTier(
                host, "brand-new-model", ThinkingLevel.HIGH, listOf("high", "max"),
            ),
        )
        // And a tier the declared set lacks still clamps onto it rather than escaping.
        assertEquals(
            "max",
            GatewayEffortTruth.resolveTier(
                host, "brand-new-model", ThinkingLevel.HIGH, listOf("max"),
            ),
        )
    }

    @Test
    fun `unknown model with no declared set stays inside the safe fallback`() {
        // "max" is rejected by six of the seven measured models, so the fallback must
        // never emit it — a dead tier beats a 400 on an endpoint we have not probed.
        assertFalse(GatewayEffortTruth.SENSENOVA_FALLBACK_TIERS.contains("max"))
        assertFalse(GatewayEffortTruth.SENSENOVA_FALLBACK_TIERS.contains("minimal"))
        assertFalse(GatewayEffortTruth.SENSENOVA_FALLBACK_TIERS.contains("ultra"))
        assertEquals(
            "xhigh",
            GatewayEffortTruth.resolveTier(host, "brand-new-model", ThinkingLevel.MAX, null),
        )
        assertEquals(
            "low",
            GatewayEffortTruth.resolveTier(host, "brand-new-model", ThinkingLevel.LOW, null),
        )
    }

    @Test
    fun `an empty declared set is treated as no information`() {
        // An empty list must not be mistaken for "nothing is allowed" — it degrades to
        // the safe fallback exactly like a null.
        assertEquals(
            "high",
            GatewayEffortTruth.resolveTier(host, "brand-new-model", ThinkingLevel.HIGH, emptyList()),
        )
    }

    // ── [T-senseaudio-effort-enum] second measured gateway (api.senseaudio.cn) ──

    private val saHost = "api.senseaudio.cn"

    @Test
    fun `senseaudio host is measured but is not a sensenova host`() {
        assertTrue(GatewayEffortTruth.isMeasuredHost(saHost))
        assertTrue(GatewayEffortTruth.isMeasuredHost("token.sensenova.cn"))
        assertFalse(GatewayEffortTruth.isSensenovaHost(saHost))
        assertFalse(GatewayEffortTruth.isMeasuredHost("api.senseaudio.cn.evil.test"))
        // Only the probed host is listed — token.senseaudio.cn was never probed.
        assertFalse(GatewayEffortTruth.isMeasuredHost("token.senseaudio.cn"))
    }

    @Test
    fun `senseaudio table matches the probe output`() {
        assertEquals(
            listOf("none", "low", "high", "xhigh", "max"),
            GatewayEffortTruth.tiersFor(saHost, "deepseek-v4.1-flash"),
        )
        // A model we have not probed on this gateway stays un-measured.
        assertNull(GatewayEffortTruth.tiersFor(saHost, "senseaudio-s2"))
        assertNull(GatewayEffortTruth.tiersFor(saHost, "sensenova-6.8-flash-lite"))
    }

    @Test
    fun `senseaudio medium degrades to low, xhigh and max are reachable`() {
        // The reported bug: `medium` 400s on this gateway — it must never reach the wire.
        val id = "deepseek-v4.1-flash"
        assertEquals("low", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.MEDIUM, null))
        assertEquals("low", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.LOW, null))
        assertEquals("high", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.HIGH, null))
        // The tiers the gateway actually serves become reachable instead of capped.
        assertEquals("xhigh", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.XHIGH, null))
        assertEquals("max", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.MAX, null))
    }

    @Test
    fun `the two gateways keep different ladders for the same model id`() {
        // Same id, different gateway: Sensenova's deepseek-v4.1-flash takes `medium`
        // (and no `max`), SenseAudio's takes `max` and 400s on `medium`. The tables
        // must never be merged — merging would reintroduce one of the two 400s.
        val id = "deepseek-v4.1-flash"
        assertTrue(GatewayEffortTruth.tiersFor(host, id)!!.contains("medium"))
        assertFalse(GatewayEffortTruth.tiersFor(saHost, id)!!.contains("medium"))
        assertTrue(GatewayEffortTruth.tiersFor(saHost, id)!!.contains("max"))
    }

    @Test
    fun `measurement outranks a wrong declared set on senseaudio too`() {
        // Older saves carry a models.dev row that names `medium` (400s here) and omits
        // `max` (reachable here) — the measured table must win on this gateway as well.
        assertEquals(
            "low",
            GatewayEffortTruth.resolveTier(
                saHost, "deepseek-v4.1-flash", ThinkingLevel.MEDIUM,
                listOf("none", "minimal", "low", "medium", "high"),
            ),
        )
        assertEquals(
            "max",
            GatewayEffortTruth.resolveTier(
                saHost, "deepseek-v4.1-flash", ThinkingLevel.MAX,
                listOf("none", "minimal", "low", "medium", "high"),
            ),
        )
    }

    @Test
    fun `an unmeasured model on senseaudio never inherits the sensenova last resort`() {
        // The live shape (device log, api.senseaudio.cn): the model in use is NOT in
        // SENSEAUDIO_TIERS and the entry declares no tier set at all
        // (`declared=null`), so resolveTier reaches its last resort. Sharing
        // sensenova's set would put `medium` on the wire — the single value this
        // gateway 400s — so each gateway family carries its own last resort.
        val id = "glm-5.3-flash"
        assertNull(GatewayEffortTruth.tiersFor(saHost, id))
        assertEquals("low", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.MEDIUM, null))
        assertEquals("low", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.LOW, null))
        assertEquals("high", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.HIGH, null))
        assertEquals("xhigh", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.XHIGH, null))
        assertEquals("max", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.MAX, null))
    }

    @Test
    fun `the senseaudio last resort keeps the tiers the gateway serves`() {
        // Contents of the new table, pinned directly. (Dropped from the PRE arm: the
        // symbol does not exist there — that is the point of the split.)
        assertFalse(GatewayEffortTruth.SENSEAUDIO_FALLBACK_TIERS.contains("medium"))
        assertTrue(GatewayEffortTruth.SENSEAUDIO_FALLBACK_TIERS.contains("none"))
        assertTrue(GatewayEffortTruth.SENSEAUDIO_FALLBACK_TIERS.contains("max"))
    }

    @Test
    fun `a declared set still outranks the senseaudio last resort`() {
        // Priority #3 survives the split: an unmeasured model whose catalog row exists
        // keeps that row's opinion. The last resort is a last resort, not an override.
        assertEquals(
            "high",
            GatewayEffortTruth.resolveTier(saHost, "senseaudio-s2", ThinkingLevel.HIGH, listOf("high", "max")),
        )
    }

    @Test
    fun `sensenova keeps medium in its last resort`() {
        // Regression on the pre-existing gateway: `medium` is legal on every measured
        // sensenova model, so the host-keyed split must not take it away from the
        // unmeasured ones either (the fallback was host-global before).
        assertTrue(GatewayEffortTruth.SENSENOVA_FALLBACK_TIERS.contains("medium"))
        assertEquals("medium", GatewayEffortTruth.resolveTier(host, "brand-new-model", ThinkingLevel.MEDIUM, null))
        assertEquals("xhigh", GatewayEffortTruth.resolveTier(host, "brand-new-model", ThinkingLevel.MAX, null))
    }

    @Test
    fun `a host with no measurement keeps the last resort it had before the split`() {
        // resolveTier is only reachable behind isMeasuredHost; a host outside the table
        // keeps the historical set rather than silently inheriting senseaudio's.
        assertFalse(GatewayEffortTruth.isMeasuredHost("api.openai.com"))
        assertFalse(GatewayEffortTruth.isMeasuredHost("api.senseaudio.cn.evil.test"))
        assertEquals("medium", GatewayEffortTruth.resolveTier("api.openai.com", "any-model", ThinkingLevel.MEDIUM, null))
        assertEquals("medium", GatewayEffortTruth.resolveTier("api.senseaudio.cn.evil.test", "any-model", ThinkingLevel.MEDIUM, null))
    }

    // ── [fix/effort-truth-1119b] 4 F-02: the learned set is read NARROWED, and its
    //    absence must not swallow the measured table ─────────────────────────────

    private val thinkingLevel400 =
        """{"error":{"message":"Thinking level is unsupported: THINKING_LEVEL_XHIGH","type":"api_error"}}"""

    @Test
    fun `a learned ceiling is narrowed by the declaration before it clamps`() {
        // The finding: a CEILING entry is the ladder PREFIX below the refused tier, so it
        // names `medium` even for a model that never declared it. Reading it raw put that
        // tier back on the wire — the same 400 the entry came from. `api.senseaudio.cn`
        // rejects `medium`, which is exactly the value in the middle of this prefix.
        val id = "deepseek-v4.1-flash"
        EffortTierLearner.record(saHost, """{"model":"$id"}""", thinkingLevel400)
        // Precondition: the raw read really contains the undeclared tier (otherwise this
        // test would pass for the wrong reason).
        assertTrue(EffortTierLearner.learned(saHost, id)!!.contains("medium"))

        assertEquals(
            "low",
            GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.MEDIUM, listOf("low", "high")),
        )
        // And the declared tiers keep working through the same narrowed set.
        assertEquals(
            "high",
            GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.HIGH, listOf("low", "high")),
        )
    }

    @Test
    fun `a learned ceiling with nothing declared keeps its old width`() {
        // The narrowing must not be a blanket restriction: a model the catalog never
        // described still gets the full weight of the gateway's own answer.
        val id = "deepseek-v4.1-flash"
        EffortTierLearner.record(saHost, """{"model":"$id"}""", thinkingLevel400)
        assertEquals("medium", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.MEDIUM, null))
        assertEquals("high", GatewayEffortTruth.resolveTier(saHost, id, ThinkingLevel.XHIGH, null))
    }

    @Test
    fun `an enumerated learned set is NOT narrowed on this path either`() {
        // One-hand truth: the gateway spelled its legal set out. Narrowing it by the
        // catalog is what the learner exists to prevent (sensenova under-declares).
        val id = "glm-5.2"
        EffortTierLearner.record(
            host, """{"model":"$id"}""",
            """{"error":"field ReasoningEffort invalid, should be one of: low, high, xhigh"}""",
        )
        assertEquals(
            "xhigh",
            GatewayEffortTruth.resolveTier(host, id, ThinkingLevel.XHIGH, listOf("low")),
        )
    }

    @Test
    fun `with nothing learned the measured table still outranks the declaration`() {
        // THE trap of this round: `learnedWithin` answers the DECLARATION when nothing is
        // learned, so routing this read through it would short-circuit `tiersFor` and hand
        // the wire the stale models.dev row the measurement exists to correct. The read is
        // the `OrNull` variant for exactly this arm.
        val unrelated = "glm-5.2"
        EffortTierLearner.record(saHost, """{"model":"$unrelated"}""", thinkingLevel400)

        assertEquals(
            "xhigh",
            GatewayEffortTruth.resolveTier(
                host, "sensenova-6.8-flash-lite", ThinkingLevel.XHIGH,
                listOf("none", "low", "medium", "high"),
            ),
        )
        // Sanity: that model has no entry of its own, so the table — not the declaration
        // and not the last resort — is what answered.
        assertNull(EffortTierLearner.learned(host, "sensenova-6.8-flash-lite"))
        assertTrue(GatewayEffortTruth.tiersFor(host, "sensenova-6.8-flash-lite")!!.contains("xhigh"))
    }
}
