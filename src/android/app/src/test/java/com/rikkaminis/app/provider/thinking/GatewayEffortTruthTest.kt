package com.rikkaminis.app.provider.thinking

import com.rikkaminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
