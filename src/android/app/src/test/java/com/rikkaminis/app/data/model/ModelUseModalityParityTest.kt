package com.rikkaminis.app.data.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [dv21-A05] One entry, one answer: the modality resolution of a `ModelEntry`
 * must be the same everywhere it is asked.
 *
 * The bug was an ORDER (and a missing step), not a wrong formula:
 *   - `ModelUseManager.entryDict` enriched **after** merging the user's
 *     overrides, so `ModelsDevApi.applyDevData` (`devModel.x ?: model.x`, with
 *     the registry value on the LEFT of the elvis) put the models.dev row back
 *     on top of an explicit user choice — `minis-model-use list` reported the
 *     registry's modalities / context window for a model the user had just
 *     re-configured;
 *   - `ModelUseOffloadHandler` (the `--modality` filter and both `run` guards)
 *     and `ModelsCollection.effectiveModalities` skipped enrichment *entirely*
 *     and read the raw lists, so all 39 built-in models — whose modality fields
 *     are null by construction — reported no modalities at all. `list` printed
 *     `image_input` for such a model and `list --modality image_input` filtered
 *     it right back out, and `supportsTools` read `false` for a plain text model.
 *
 * The fix collapses the three into `ModelsDevApi.effectiveModel` →
 * `LLMModel.directionalModalities`. This test pins the ORDER (the half a JVM
 * test can execute: the ordering core is pure, the registry lookup is not),
 * and pins by source shape that the three consumers really do go through it.
 *
 * Expected values are hand-written literals, never derived from the production
 * constants — deriving them would let a mutation masquerade as PASS.
 */
class ModelUseModalityParityTest {

    // ── fixtures ────────────────────────────────────────────────────────────

    /**
     * Stands in for a models.dev row that has data for every field —
     * `ModelsDevApi.applyDevData` fills them unconditionally, which is exactly
     * why enrichment must happen BEFORE the overrides are applied.
     */
    private fun devEnrich(model: LLMModel): LLMModel = LLMModel(
        id = model.id,
        displayName = model.displayName,
        provider = model.provider,
        contextWindow = 128_000,
        inputModalities = listOf("text", "image"),
        outputModalities = listOf("text"),
    )

    private fun base(
        id: String = "gpt-4o",
        inputs: List<String>? = null,
        outputs: List<String>? = null,
        contextWindow: Int? = null,
    ) = LLMModel(
        id = id,
        displayName = "GPT-4o",
        provider = "openai",
        contextWindow = contextWindow,
        inputModalities = inputs,
        outputModalities = outputs,
    )

    private fun entry(model: LLMModel, overrides: ModelOverrides = ModelOverrides()) =
        ModelEntry(providerInstanceId = "inst-1", baseModel = model, overrides = overrides)

    // ── ① no override: models.dev supplies the modalities ───────────────────

    @Test
    fun `an entry without overrides takes its modalities from the registry`() {
        // The base model is a bare built-in (null modality fields), so anything
        // that shows up here came from enrichment.
        val e = entry(base())
        assertEquals(
            listOf("text_input", "text_output", "image_input"),
            effectiveEntryModel(e, ::devEnrich).directionalModalities,
        )
        assertEquals(128_000, effectiveEntryModel(e, ::devEnrich).contextWindow)
    }

    // ── ② overrides win over models.dev (the ordering contract) ─────────────

    @Test
    fun `an explicit modality override is not restored to the registry value`() {
        val e = entry(
            base(),
            ModelOverrides(inputModalities = listOf("text"), outputModalities = listOf("text")),
        )
        assertEquals(
            // image_input must NOT be back: the user narrowed this entry to text.
            listOf("text_input", "text_output"),
            effectiveEntryModel(e, ::devEnrich).directionalModalities,
        )
    }

    @Test
    fun `an explicit context window override is not restored to the registry value`() {
        val e = entry(base(contextWindow = 1_000_000), ModelOverrides(contextWindow = 400_000))
        assertEquals(400_000, effectiveEntryModel(e, ::devEnrich).contextWindow)
    }

    @Test
    fun `the reverse order is what the bug looked like`() {
        // Teeth for the two tests above: enrich(merged) — the pre-fix call in
        // ModelUseManager.entryDict — really does hand the registry value back.
        val e = entry(
            base(),
            ModelOverrides(inputModalities = listOf("text"), outputModalities = listOf("text")),
        )
        assertEquals(
            listOf("text_input", "text_output", "image_input"),
            devEnrich(e.model).directionalModalities,
        )
        assertEquals(128_000, devEnrich(e.model).contextWindow)
    }

    @Test
    fun `the merge itself is single-sourced`() {
        // ModelEntry.model and the enriched path must not be able to drift.
        val e = entry(base(inputs = listOf("text")), ModelOverrides(contextWindow = 7))
        assertEquals(e.baseModel.withOverrides(e.overrides), e.model)
    }

    // ── ③ null modalities: per-direction text fallback ──────────────────────

    @Test
    fun `a model with no modality data is text in and text out`() {
        val m = base()
        assertEquals(listOf("text_input", "text_output"), m.directionalModalities)
        // `minis-config get models.<id>.supportsTools` reads
        // `"text_output" in effectiveModalities(e)`.
        assertTrue("text_output" in m.directionalModalities)
        assertFalse("image_input" in m.directionalModalities)
    }

    @Test
    fun `an empty list counts as absent on that side only`() {
        val m = base(inputs = emptyList(), outputs = listOf("image"))
        // The output side is declared (image), so it must NOT gain a text_output:
        // an image generator reported as text-capable would be offered as a new
        // chat default and advertised as tool-capable.
        assertEquals(listOf("text_input", "image_output"), m.directionalModalities)
    }

    @Test
    fun `suffixed provider values land on the same names as models dev values`() {
        // OpenRouter-shaped base (…_input / …_output) vs models.dev-shaped base.
        val providerShaped = base(inputs = listOf("text_input", "image_input"), outputs = listOf("text_output"))
        val devShaped = base(inputs = listOf("text", "image"), outputs = listOf("text"))
        assertEquals(listOf("text_input", "text_output", "image_input"), devShaped.directionalModalities)
        assertEquals(listOf("text_input", "text_output", "image_input"), providerShaped.directionalModalities)
    }

    @Test
    fun `the canonical vocabulary is the literal both tools speak`() {
        assertEquals(
            listOf(
                "text_input", "text_output",
                "image_input", "pdf_input", "audio_input", "video_input",
                "image_output", "audio_output", "video_output",
            ),
            modalityNamesInOrder,
        )
        assertEquals(
            listOf("text_input", "image_input", "pdf_input"),
            expandDirectionalModalities(listOf("text", "image", "pdf"), null),
        )
    }

    @Test
    fun `the raw override reader is not polluted by the fallback`() {
        // `modalitiesOverride` has to report exactly what the user wrote; a
        // partial override must not read back with a derived text_output.
        assertEquals(emptyList<String>(), expandDirectionalModalities(null, null))
        assertEquals(
            listOf("image_input"),
            expandDirectionalModalities(listOf("image_input"), null),
        )
    }

    // ── ④ parity: all three consumers go through the one resolver ───────────

    @Test
    fun `every modality consumer routes through the shared resolver`() {
        val handler = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt",
        ) ?: return // tree not on disk → skip (see class kdoc)
        val collection = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/config/collections/ModelsCollection.kt",
        ) ?: return
        val manager = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/offload/ModelUseManager.kt",
        ) ?: return
        val chatDebug = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/debug/ChatDebugMethods.kt",
        ) ?: return
        val providerDebug = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/debug/ProviderDebugMethods.kt",
        ) ?: return
        val providerMutation = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/debug/ProviderMutationMethods.kt",
        ) ?: return

        // The offload filter/guards, the config collection and the agent-facing
        // payload must all resolve through ModelsDevApi.effectiveModel.
        assertEquals(
            "ModelUseOffloadHandler must resolve modalities through the shared entry",
            2,
            Regex("""ModelsDevApi\.effectiveModel\(""").findAll(handler).count(),
        )
        assertTrue(
            "ModelUseOffloadHandler must not read the raw entry modal list again",
            Regex("""entry\.model\.(input|output)Modalities""").findAll(handler).count() == 0,
        )
        assertEquals(
            "ModelsCollection must resolve modalities through the shared entry",
            1,
            Regex("""ModelsDevApi\.effectiveModel\(""").findAll(collection).count(),
        )
        assertEquals(
            "ModelUseManager must resolve modalities through the shared entry",
            1,
            Regex("""ModelsDevApi\.effectiveModel\(""").findAll(manager).count(),
        )
        // [dv22-A4] The three Debug RPC views answer "what can this model do"
        // too, so they go through the same entry (contextWindow and the
        // capability booleans over the harness used to read the raw
        // entry.model and disagree with everything above).
        assertEquals(
            "chat.models.list must resolve through the shared entry",
            1,
            Regex("""ModelsDevApi\.effectiveModel\(""").findAll(chatDebug).count(),
        )
        assertEquals(
            "provider.models.list must resolve through the shared entry",
            1,
            Regex("""ModelsDevApi\.effectiveModel\(""").findAll(providerDebug).count(),
        )
        assertEquals(
            "provider.models.add/update read-back must resolve through the shared entry",
            1,
            Regex("""ModelsDevApi\.effectiveModel\(""").findAll(providerMutation).count(),
        )
        assertEquals(
            "no consumer may read the raw entry as the effective model any more",
            0,
            Regex("""val\s+effective(\s*:\s*LLMModel)?\s*=\s*entry\.model""").findAll(
                chatDebug + providerDebug + providerMutation,
            ).count(),
        )
    }

    @Test
    fun `the collection numeric readers resolve through the shared entry`() {
        // [dv22-A4] models.<id>.contextWindow / maxOutputTokens used to read
        // `overrides ?: baseModel ?: 0`, skipping models.dev — 33 of 39
        // built-ins answered 0. They now go through the collection's private
        // alias of the shared resolver (the alias is what keeps the literal
        // call count above at exactly 1).
        val collection = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/config/collections/ModelsCollection.kt",
        ) ?: return
        val code = codeOnly(collection)
        assertTrue(
            "contextWindow reader must read effectiveModel(e).contextWindow",
            "effectiveModel(e).contextWindow" in code,
        )
        assertTrue(
            "maxOutputTokens reader must read effectiveModel(e).maxOutputTokens",
            "effectiveModel(e).maxOutputTokens" in code,
        )
        assertFalse(
            "the raw `overrides ?: baseModel` window chain must be gone",
            "e.overrides.contextWindow ?: e.baseModel.contextWindow" in code,
        )
        assertFalse(
            "the raw `overrides ?: baseModel` output-token chain must be gone",
            "e.overrides.maxOutputTokens ?: e.baseModel.maxOutputTokens" in code,
        )
        assertTrue(
            "the alias must delegate to the shared resolver",
            Regex("""private fun effectiveModel\(e: ModelEntry\): LLMModel\s*=\s*\n?\s*ModelsDevApi\.effectiveModel\(e\)""").containsMatchIn(code),
        )
    }

    @Test
    fun `the directional expansion has exactly one definition`() {
        val collection = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/config/collections/ModelsCollection.kt",
        ) ?: return
        val model = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/data/model/LLMModel.kt",
        ) ?: return
        // The collection used to carry a private copy of the vocabulary and its
        // own appendDirectional(); a second copy is how the two surfaces drifted.
        assertFalse(
            "ModelsCollection must not carry its own modality vocabulary again",
            codeOnly(collection).contains("private val modalityNamesInOrder"),
        )
        assertFalse(
            "ModelsCollection must not carry its own directional expansion again",
            codeOnly(collection).contains("private fun appendDirectional"),
        )
        assertEquals(
            "the canonical vocabulary is defined once, in LLMModel.kt",
            1,
            Regex("""val modalityNamesInOrder""").findAll(codeOnly(model)).count(),
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

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
