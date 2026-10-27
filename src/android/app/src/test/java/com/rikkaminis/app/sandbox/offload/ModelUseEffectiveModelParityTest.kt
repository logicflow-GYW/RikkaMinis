package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.effectiveEntryModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [dv23-A03] The model that crosses into `:modelservice` must be the ENRICHED
 * one — the same object `cmdRun` judged the `--output` / input-modality guards
 * on — and not the raw `entry.model` (baseModel + overrides only).
 *
 * The defect: `cmdRun` resolved `effectiveModel` (models.dev base + overrides)
 * and judged `--output out.png` / audio-input on it, but handed the RAW
 * `entry.model` to `ModelExecutionDispatcher.buildRequestJson` at BOTH dispatch
 * sites. `buildRequestJson` serializes the capability fields as PRESENT-ONLY
 * keys, so a null field does not travel as null — it disappears, and the
 * worker rebuilds an `LLMModel` that has never seen models.dev (that process
 * never calls `ModelsDevApi.init`). Consequences, all silent:
 *
 *   • `output_modalities` gone → `GeminiProvider` skips
 *     `responseModalities: ["TEXT","IMAGE"]` → an image run that PASSED the
 *     gate in the app process comes back text-only and writes no file;
 *   • `max_output_tokens` gone → the provider falls back to its hardcoded
 *     default instead of the declared ceiling;
 *   • `reasoning_effort_values` / `declares_no_effort_tiers` /
 *     `interleaved_reasoning_field` gone → the effort picker and the
 *     Anthropic-style reasoning echo are inert in the worker.
 *
 * The source-shape assertions are derived per dispatch site and counted, so a
 * THIRD dispatch point cannot reintroduce the raw hand-off. Expected values
 * are hand-written literals, never derived from the constants under test.
 */
class ModelUseEffectiveModelParityTest {

    // ── fixtures ──────────────────────────────────────────────────────────

    /** A bare built-in: every capability field null, like the 39 in-tree ones. */
    private fun builtIn() = LLMModel(
        id = "gpt-image-2",
        displayName = "GPT Image 2",
        provider = "openai",
    )

    /**
     * Stands in for a models.dev row. `ModelsDevApi.applyDevData` fills every
     * field unconditionally, which is why the enriched model carries values the
     * bare built-in does not have.
     */
    private fun devEnrich(model: LLMModel): LLMModel = model.copy(
        contextWindow = 128_000,
        maxOutputTokens = 32_768,
        supportsReasoning = true,
        interleavedReasoningField = "reasoning_content",
        inputModalities = listOf("text", "image"),
        outputModalities = listOf("text", "image"),
        reasoningEffortValues = listOf("low", "medium", "high"),
        declaresNoEffortTiers = false,
    )

    private fun entry(model: LLMModel = builtIn(), overrides: ModelOverrides = ModelOverrides()) =
        ModelEntry(providerInstanceId = "inst-1", baseModel = model, overrides = overrides)

    // ── ① the enriched model really carries the capabilities ──────────────

    @Test
    fun `the enriched built-in carries the registry capabilities the raw entry lacks`() {
        val e = entry()
        val effective = effectiveEntryModel(e, ::devEnrich)
        assertEquals(listOf("text", "image"), effective.outputModalities)
        assertEquals(listOf("text", "image"), effective.inputModalities)
        assertEquals(32_768, effective.maxOutputTokens)
        assertEquals(128_000, effective.contextWindow)
        assertEquals(listOf("low", "medium", "high"), effective.reasoningEffortValues)
        assertEquals("reasoning_content", effective.interleavedReasoningField)
        // The raw entry is the shape that used to be handed to the worker: every
        // capability field null, i.e. absent from the serialized request.
        assertNull(e.model.outputModalities)
        assertNull(e.model.inputModalities)
        assertNull(e.model.maxOutputTokens)
        assertNull(e.model.contextWindow)
        assertNull(e.model.reasoningEffortValues)
        assertNull(e.model.interleavedReasoningField)
    }

    @Test
    fun `enrichment is applied before the user's overrides`() {
        // The ordering contract the shared resolver exists for: an explicit user
        // choice must win over the registry default.
        val e = entry(
            overrides = ModelOverrides(outputModalities = listOf("text"), maxOutputTokens = 1_024),
        )
        val effective = effectiveEntryModel(e, ::devEnrich)
        assertEquals(listOf("text"), effective.outputModalities)
        assertEquals(1_024, effective.maxOutputTokens)
    }

    // ── ② the dispatched model is the enriched one ─────────────────────────

    @Test
    fun `no dispatch site hands the raw entry to the worker`() {
        val handler = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt",
        ) ?: return // tree not on disk → skip (see class kdoc)
        val code = codeOnly(handler)
        assertFalse(
            "the raw entry must not be handed to :modelservice — every models.dev " +
                "capability field dies at the process boundary",
            Regex("""model\s*=\s*[A-Za-z_][A-Za-z0-9_.]*\.model\b""").containsMatchIn(code),
        )
    }

    @Test
    fun `every dispatch site hands over the enriched model`() {
        val handler = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt",
        ) ?: return
        val code = codeOnly(handler)
        val args = dispatchModelArgs(code)
        assertEquals(
            "the handler must have exactly the two known dispatch sites " +
                "(passthrough + standard) — a third one needs the same guarantee",
            2,
            args.size,
        )
        val alias = Regex(
            """\bval\s+([A-Za-z_]\w*)\s*(?::\s*[^=\n]+)?=\s*[^=\n]*\beffectiveModel\s*\(""",
        ).find(code)?.groupValues?.get(1)
        assertNotNull("the enriched model must be bound in the handler", alias)
        for ((i, arg) in args.withIndex()) {
            assertEquals(
                "dispatch site #$i must hand over the enriched model (`$alias`), not `$arg`",
                alias,
                arg,
            )
        }
    }

    @Test
    fun `the enriched model is resolved exactly once per handler`() {
        // The literal call count is pinned by the parity test above (2: the
        // --output guard and the input-modality guard). This one pins that the
        // dispatch sites REUSE that binding instead of resolving again — a third
        // literal call would break the pinned count, so both must hold.
        val handler = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt",
        ) ?: return
        // Counted on the RAW text, exactly like the fence in
        // ModelUseModalityParityTest: a prose mention of the call form would
        // count there too, so this assertion must use the same口径 or the two
        // would disagree about the same file.
        assertEquals(
            "the handler must resolve the enriched model exactly twice (the two guards)",
            2,
            Regex("""ModelsDevApi\.effectiveModel\(""").findAll(handler).count(),
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** The `model =` argument of every `buildRequestJson(` call site, in order. */
    private fun dispatchModelArgs(code: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (true) {
            val at = code.indexOf("buildRequestJson(", i)
            if (at < 0) break
            var depth = 0
            var j = at + "buildRequestJson(".length - 1
            while (j < code.length) {
                when (code[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            val args = code.substring(at + "buildRequestJson(".length, j)
            val m = Regex("""model\s*=\s*([^,\n]+)""").find(args)
            out += (m?.groupValues?.get(1) ?: "").trim()
            i = at + 1
        }
        return out
    }

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
}
