package com.rikkaminis.app.debug

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.effectiveEntryModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv22-A4] The three Debug RPC model views (`chat.models.list`,
 * `provider.models.list`, and the mutation faces' read-back
 * `entryToJson`) used to answer "what can this model do" from the raw
 * `entry.model` — base + overrides, NO models.dev enrichment. Every
 * built-in model whose local row leaves `contextWindow` / modality
 * fields null (33 of the 39 built-ins declare no context window;
 * all 39 declare null modality fields by construction) therefore
 * reported `contextWindow: null`, `supportsImages/Video/Audio/Pdf:
 * false` over the debug harness, while `minis-model-use list` — which
 * routes through [com.rikkaminis.app.provider.ModelsDevApi.effectiveModel]
 * — answered differently for the very same entry.
 *
 * Two layers, house pattern (see ChatDebugMessagePartsTest):
 *
 *  1. *Behaviour, really executed*: the enrichment core the debug views
 *     now ride on is [effectiveEntryModel] (enrich base first, apply
 *     overrides second). The arms below drive it with models.dev rows
 *     modelled on the registry's published values — expectations are
 *     hand-written literals, never derived from the production code.
 *  2. *Wiring, as source shape*: the three shipped debug files must
 *     actually route `val effective` through the resolver (and NOT read
 *     `entry.model` as the effective model). These are Context-bound
 *     call sites the JVM cannot drive, so the shipped source is read
 *     and probed — and every probe carries an in-test negative control:
 *     the same probe that accepts the shipped source must reject a
 *     mutated copy of it.
 */
class DebugModelsEnrichmentTest {

    // ── layer 1: behaviour of the enrichment the debug views ride on ────────

    /**
     * models.dev rows for one model per family, modelled on the registry's
     * published numbers. The numbers are literals — a mutation of
     * production code must not be able to make an expectation follow it.
     */
    private val devRows: Map<String, LLMModel> = mapOf(
        "claude-haiku-4-5" to LLMModel(
            id = "claude-haiku-4-5", displayName = "Claude Haiku 4.5", provider = "Anthropic",
            contextWindow = 200_000, maxOutputTokens = 64_000, supportsReasoning = true,
            inputModalities = listOf("text", "image", "pdf"),
            outputModalities = listOf("text"),
        ),
        "gemini-3-pro-preview" to LLMModel(
            id = "gemini-3-pro-preview", displayName = "Gemini 3 Pro (Preview)", provider = "Google",
            contextWindow = 1_000_000, maxOutputTokens = 65_536, supportsReasoning = true,
            inputModalities = listOf("text", "image", "audio", "video", "pdf"),
            outputModalities = listOf("text"),
        ),
        "gpt-5.2" to LLMModel(
            id = "gpt-5.2", displayName = "GPT-5.2", provider = "OpenAI",
            contextWindow = 1_680_000, maxOutputTokens = 128_000, supportsReasoning = true,
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("text"),
        ),
        "grok-4.5" to LLMModel(
            id = "grok-4.5", displayName = "Grok 4.5", provider = "xAI",
            contextWindow = 1_000_000, maxOutputTokens = 32_000, supportsReasoning = true,
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("text"),
        ),
        "kimi-k3" to LLMModel(
            id = "kimi-k3", displayName = "Kimi K3", provider = "Kimi",
            contextWindow = 1_000_000, maxOutputTokens = 32_000,
            inputModalities = listOf("text"),
            outputModalities = listOf("text"),
        ),
    )

    /** Mirrors `ModelsDevApi.applyDevData`: registry fills, base keeps what dev omits. */
    private fun devEnrich(model: LLMModel): LLMModel {
        val dev = devRows[model.id] ?: return model
        return model.copy(
            contextWindow = dev.contextWindow ?: model.contextWindow,
            maxOutputTokens = dev.maxOutputTokens ?: model.maxOutputTokens,
            supportsReasoning = dev.supportsReasoning ?: model.supportsReasoning,
            inputModalities = dev.inputModalities ?: model.inputModalities,
            outputModalities = dev.outputModalities ?: model.outputModalities,
        )
    }

    private fun entry(model: LLMModel, overrides: ModelOverrides = ModelOverrides()) =
        ModelEntry(providerInstanceId = "inst-1", baseModel = model, overrides = overrides)

    private fun catalogModel(id: String): LLMModel = LLMModel.allModels.first { it.id == id }

    @Test
    fun `the raw entry reports nothing the catalog left null`() {
        // The defect, pinned first: every one of these is exactly what the
        // debug views answered pre-fix (`entry.model` = base + overrides,
        // no enrichment).
        assertEquals(null, catalogModel("gemini-3-pro-preview").contextWindow)
        assertEquals(null, catalogModel("gpt-5.2").contextWindow)
        assertEquals(null, catalogModel("grok-4.5").contextWindow)
        assertEquals(null, catalogModel("kimi-k3").contextWindow)
        assertNull(catalogModel("gemini-3-pro-preview").inputModalities)
        assertNull(catalogModel("gpt-5.2").inputModalities)
    }

    @Test
    fun `exactly the anthropic six carry a local context window`() {
        // Report claim, verified against the shipped catalog: 39 built-ins,
        // 6 with an explicit window, 33 null — so the raw read answered 0 /
        // null for 33 of 39 while the registry knows every one of them.
        assertEquals(39, LLMModel.allModels.size)
        assertEquals(6, LLMModel.allModels.count { it.contextWindow != null })
        assertEquals(33, LLMModel.allModels.count { it.contextWindow == null })
    }

    @Test
    fun `the debug views now report the registry window for a bare built-in`() {
        // One per family: enrichment supplies what the catalog left null.
        assertEquals(200_000, effectiveEntryModel(entry(catalogModel("claude-haiku-4-5")), ::devEnrich).contextWindow)
        assertEquals(1_000_000, effectiveEntryModel(entry(catalogModel("gemini-3-pro-preview")), ::devEnrich).contextWindow)
        assertEquals(1_680_000, effectiveEntryModel(entry(catalogModel("gpt-5.2")), ::devEnrich).contextWindow)
        assertEquals(1_000_000, effectiveEntryModel(entry(catalogModel("grok-4.5")), ::devEnrich).contextWindow)
        assertEquals(1_000_000, effectiveEntryModel(entry(catalogModel("kimi-k3")), ::devEnrich).contextWindow)
    }

    @Test
    fun `a fully covered registry leaves no built-in without a window`() {
        // A dev row for every catalog id (fixture input, not expectation):
        // enrichment must fill all 39 — the raw chain left 33 at null.
        val fullyCovered: (LLMModel) -> LLMModel = { m ->
            m.copy(
                contextWindow = 1_000_000,
                maxOutputTokens = 32_000,
                inputModalities = listOf("text"),
                outputModalities = listOf("text"),
            )
        }
        for (m in LLMModel.allModels) {
            assertEquals(1_000_000, effectiveEntryModel(entry(m), fullyCovered).contextWindow)
            assertEquals(32_000, effectiveEntryModel(entry(m), fullyCovered).maxOutputTokens)
        }
    }

    @Test
    fun `capabilities come from the enriched modalities like the debug list computes them`() {
        // chat.models.list derives the capability booleans from the
        // effective input modalities: `put("supportsImages", "image" in inputs)`
        // with `inputs = effective.inputModalities ?: emptyList()`.
        val geminiInputs = effectiveEntryModel(entry(catalogModel("gemini-3-pro-preview")), ::devEnrich)
            .inputModalities ?: emptyList()
        assertTrue("image" in geminiInputs)
        assertTrue("pdf" in geminiInputs)
        assertTrue("video" in geminiInputs)
        assertTrue("audio" in geminiInputs)

        val kimiInputs = effectiveEntryModel(entry(catalogModel("kimi-k3")), ::devEnrich)
            .inputModalities ?: emptyList()
        assertFalse("image" in kimiInputs)
        assertFalse("pdf" in kimiInputs)
    }

    @Test
    fun `an entry with no dev row still falls back to override then local value`() {
        // Enrichment is a DEFAULT, never an override of user intent: a custom
        // relay model the registry has never heard of keeps answering from
        // its own fields, exactly as before.
        val custom = LLMModel(id = "my-relay/model", displayName = "Relay", provider = "Custom")
        assertEquals(123_456, effectiveEntryModel(entry(custom, ModelOverrides(contextWindow = 123_456)), ::devEnrich).contextWindow)
        assertEquals(null, effectiveEntryModel(entry(custom), ::devEnrich).contextWindow)

        val local = LLMModel(id = "local-model", displayName = "Local", provider = "Custom", contextWindow = 64_000)
        assertEquals(64_000, effectiveEntryModel(entry(local), ::devEnrich).contextWindow)
    }

    // ── layer 2: wiring, as source shape ────────────────────────────────────

    private fun readRepoFile(relative: String): String? {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        return null
    }

    /** Drop line and block comments (prose about a removed shape is legal). */
    private fun codeOnly(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    sb.append(src[i + 1])
                    i += 2
                    continue
                }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                sb.append(c)
                i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i < src.length && !(src[i] == '*' && i + 1 < src.length && src[i + 1] == '/')) i++
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun file(rel: String): String =
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/debug/$rel")
            ?: "" // tree not on disk → the file-specific probes below fail loudly

    /**
     * The decision, not the spelling: the view's `effective` must be produced
     * by the shared resolver (exactly one call), and a direct
     * `val effective = entry.model` read must be gone.
     */
    private fun routesThroughResolver(code: String): Boolean {
        val raw = Regex("""val\s+effective(\s*:\s*LLMModel)?\s*=\s*entry\.model""").findAll(code).count()
        val calls = Regex("""ModelsDevApi\.effectiveModel\(""").findAll(code).count()
        return raw == 0 && calls == 1
    }

    @Test
    fun `chat models list routes the effective model through the resolver`() {
        val code = codeOnly(file("ChatDebugMethods.kt"))
        assertTrue("chat.models.list must resolve the effective model through ModelsDevApi.effectiveModel", routesThroughResolver(code))
    }

    @Test
    fun `provider models list routes the effective model through the resolver`() {
        val code = codeOnly(file("ProviderDebugMethods.kt"))
        assertTrue("provider.models.list must resolve the effective model through ModelsDevApi.effectiveModel", routesThroughResolver(code))
    }

    @Test
    fun `provider mutation read-back routes the effective model through the resolver`() {
        val code = codeOnly(file("ProviderMutationMethods.kt"))
        assertTrue("entryToJson must resolve the effective model through ModelsDevApi.effectiveModel", routesThroughResolver(code))
    }

    @Test
    fun `the probes have teeth`() {
        // Negative control, one per failure shape:
        //  a) pre-fix: the raw `entry.model` read (no resolver call);
        //  b) regression: the resolver call present but the raw read back too.
        val preFix = """
            fun modelsList(params: JSONObject): JSONObject {
                for (entry in visible) {
                    val effective: LLMModel = entry.model
                    put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
                }
            }
        """.trimIndent()
        assertFalse(routesThroughResolver(codeOnly(preFix)))

        val partial = """
            fun modelsList(params: JSONObject): JSONObject {
                for (entry in visible) {
                    val resolved = com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)
                    val effective: LLMModel = entry.model
                    put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
                }
            }
        """.trimIndent()
        assertFalse(routesThroughResolver(codeOnly(partial)))

        // And the shipped shape (resolver, no raw read) is accepted:
        val fixed = """
            fun modelsList(params: JSONObject): JSONObject {
                for (entry in visible) {
                    val effective: LLMModel =
                        com.rikkaminis.app.provider.ModelsDevApi.effectiveModel(entry)
                    put("contextWindow", effective.contextWindow ?: JSONObject.NULL)
                }
            }
        """.trimIndent()
        assertTrue(routesThroughResolver(codeOnly(fixed)))
    }
}
