package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import com.rikkaminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1023-B] Regression tests for the pure derivation behind
 * ModelEntryDetailScreen's Save button.
 *
 * The inline version had four defects (all reproduced in
 * probe/pre_proof_sim.py pre-fix):
 *   1. the modality lists were rebuilt from the toggle switches alone, so
 *      "text" (both sides) and output "video" were dropped — a written
 *      override is non-null, and ModelEntry.model replaces the base list
 *      wholesale, so the model's effective outputModalities lost "text":
 *      LLMModel.isTextOutput flipped false (dropped from the new-chat default
 *      candidates) and ModelUseProtocol.isPureImageGenerator flipped true
 *      (pinned to the Images endpoint);
 *   2. the "changed?" comparison ran against the RAW base list while the
 *      switches are derived through normalizeModalityName() — a suffixed base
 *      ("text_input" / "image_input" from OpenRouter) always "differed" and
 *      forced a write on a no-op save;
 *   3. the positional ModelOverrides(...) constructor dropped
 *      maxThinkingLevel (7th field, owned by backup import / config restore).
 *
 * Expected values are literal on purpose — deriving them from the production
 * constants would let a mutation masquerade as PASS.
 */
class ModelEntryOverrideBuilderTest {

    private fun base(
        inputs: List<String>? = null,
        outputs: List<String>? = null,
        displayName: String = "Base Model",
        supportsReasoning: Boolean? = null,
    ) = LLMModel(
        id = "test-model",
        displayName = displayName,
        provider = "TestProvider",
        supportsReasoning = supportsReasoning,
        inputModalities = inputs,
        outputModalities = outputs,
    )

    /** Save with every switch left exactly where the screen put it. */
    private fun saveUntouched(
        model: LLMModel,
        previous: ModelOverrides = ModelOverrides(),
    ): ModelOverrides {
        val inputs = (previous.inputModalities ?: model.inputModalities ?: emptyList())
            .map { it.removeSuffix("_input").removeSuffix("_output").lowercase() }
        val outputs = (previous.outputModalities ?: model.outputModalities ?: emptyList())
            .map { it.removeSuffix("_input").removeSuffix("_output").lowercase() }
        return buildModelEntryOverrides(
            baseModel = model,
            previous = previous,
            displayName = model.displayName,
            maxOutputTokensText = "",
            contextWindowText = "",
            thinkingEnabled = model.supportsReasoning ?: false,
            imageInput = "image" in inputs,
            pdfInput = "pdf" in inputs,
            audioInput = "audio" in inputs,
            videoInput = "video" in inputs,
            imageOutput = "image" in outputs,
            audioOutput = "audio" in outputs,
        )
    }

    // ── Criterion 1 (task book §3.4): an untouched save writes nothing ──

    @Test
    fun untouchedSave_onTextAndImageModel_keepsBothModalityOverridesNull() {
        val model = base(inputs = listOf("text", "image"), outputs = listOf("text", "image"))
        val result = saveUntouched(model)
        assertNull(result.inputModalities)
        assertNull(result.outputModalities)
        assertTrue(result.isEmpty)
    }

    @Test
    fun untouchedSave_onSuffixedBase_keepsBothModalityOverridesNull() {
        // OpenRouter-style raw tokens: switches come from the normalized space,
        // so the comparison must run there too — otherwise a no-op save forces
        // a write and freezes the entry to today's provider metadata.
        val model = base(
            inputs = listOf("text_input", "image_input"),
            outputs = listOf("text_output", "image_output"),
        )
        val result = saveUntouched(model)
        assertNull(result.inputModalities)
        assertNull(result.outputModalities)
    }

    @Test
    fun untouchedSave_onNullModalities_keepsBothModalityOverridesNull() {
        val result = saveUntouched(base(inputs = null, outputs = null))
        assertNull(result.inputModalities)
        assertNull(result.outputModalities)
    }

    @Test
    fun untouchedSave_onPureImageModel_keepsOutputOverrideNull() {
        // base outputs ["image"], switch reflects it → no divergence, no write.
        val result = saveUntouched(base(inputs = listOf("text"), outputs = listOf("image")))
        assertNull(result.outputModalities)
        assertNull(result.inputModalities)
    }

    // ── Criterion 2: non-toggle dimensions survive a real edit ──

    @Test
    fun enablingImageOutput_keepsTextOutput() {
        val model = base(inputs = listOf("text"), outputs = listOf("text"))
        val result = buildModelEntryOverrides(
            baseModel = model,
            previous = ModelOverrides(),
            displayName = model.displayName,
            maxOutputTokensText = "",
            contextWindowText = "",
            thinkingEnabled = false,
            imageInput = false,
            pdfInput = false,
            audioInput = false,
            videoInput = false,
            imageOutput = true,
            audioOutput = false,
        )
        assertEquals(listOf("image", "text"), result.outputModalities)
    }

    @Test
    fun disablingImageInput_keepsTextInput() {
        val model = base(inputs = listOf("text", "image"), outputs = listOf("text"))
        val result = buildModelEntryOverrides(
            baseModel = model,
            previous = ModelOverrides(),
            displayName = model.displayName,
            maxOutputTokensText = "",
            contextWindowText = "",
            thinkingEnabled = false,
            imageInput = false,
            pdfInput = false,
            audioInput = false,
            videoInput = false,
            imageOutput = false,
            audioOutput = false,
        )
        assertEquals(listOf("text"), result.inputModalities)
    }

    @Test
    fun videoOutput_defaultsToBase_soAnUntouchedSaveKeepsInheriting() {
        val model = base(inputs = listOf("text"), outputs = listOf("text", "video"))
        // [RC-1114 U-18] The screen now HAS a video-output switch, seeded from
        // the base — so an untouched save still writes nothing (the derived
        // toggle equals the base), which is the property this test has always
        // pinned. The switch only becomes an override when the user flips it
        // (next test).
        assertNull(saveUntouched(model).outputModalities)

        // Enabling image output must not drop video.
        val edited = buildModelEntryOverrides(
            baseModel = model,
            previous = ModelOverrides(),
            displayName = model.displayName,
            maxOutputTokensText = "",
            contextWindowText = "",
            thinkingEnabled = false,
            imageInput = false,
            pdfInput = false,
            audioInput = false,
            videoInput = false,
            imageOutput = true,
            audioOutput = false,
        )
        assertEquals(setOf("image", "text", "video"), edited.outputModalities!!.toSet())
    }

    @Test
    fun videoOutput_turnedOff_becomesAnOverrideAndDropsVideo() {
        // [RC-1114 U-18] The dimension is now user-writable. A model whose
        // catalog row declares video can be told it does NOT produce video —
        // the case that was impossible before (the screen passed the dimension
        // through untouched, so "video" always survived from the base).
        val model = base(inputs = listOf("text"), outputs = listOf("text", "video"))
        val result = buildModelEntryOverrides(
            baseModel = model,
            previous = ModelOverrides(),
            displayName = model.displayName,
            maxOutputTokensText = "",
            contextWindowText = "",
            thinkingEnabled = false,
            imageInput = false,
            pdfInput = false,
            audioInput = false,
            videoInput = false,
            imageOutput = false,
            audioOutput = false,
            videoOutput = false,
        )
        assertEquals(setOf("text"), result.outputModalities!!.toSet())
    }

    @Test
    fun videoOutput_turnedOn_forAModelWithoutIt_becomesAnOverride() {
        // The mirror direction: mark a text-only model as producing video.
        val model = base(inputs = listOf("text"), outputs = listOf("text"))
        val result = buildModelEntryOverrides(
            baseModel = model,
            previous = ModelOverrides(),
            displayName = model.displayName,
            maxOutputTokensText = "",
            contextWindowText = "",
            thinkingEnabled = false,
            imageInput = false,
            pdfInput = false,
            audioInput = false,
            videoInput = false,
            imageOutput = false,
            audioOutput = false,
            videoOutput = true,
        )
        assertEquals(setOf("text", "video"), result.outputModalities!!.toSet())
    }

    @Test
    fun maxThinkingLevel_omittedByCaller_isPreservedNotWiped() {
        // [RC-1114 U-18] The new parameter defaults to `previous.maxThinkingLevel`,
        // so a caller that does not own the field (every pre-existing call site)
        // cannot wipe a value written by backup import / config restore / RPC.
        // This is the same contract `previous.copy(...)` gave for free before
        // the field became a named argument — pinned here because a named
        // argument is exactly where that free passthrough can be lost.
        val previous = ModelOverrides(maxThinkingLevel = ThinkingLevel.ULTRA)
        val result = buildModelEntryOverrides(
            baseModel = base(inputs = listOf("text"), outputs = listOf("text")),
            previous = previous,
            displayName = "Base Model",
            maxOutputTokensText = "",
            contextWindowText = "",
            thinkingEnabled = false,
            imageInput = false,
            pdfInput = false,
            audioInput = false,
            videoInput = false,
            imageOutput = false,
            audioOutput = false,
        )
        assertEquals(ThinkingLevel.ULTRA, result.maxThinkingLevel)
    }

    @Test
    fun maxThinkingLevel_setToNull_clearsTheOverride() {
        // "Inherit model default" must actually clear, not freeze today's level.
        val previous = ModelOverrides(maxThinkingLevel = ThinkingLevel.MAX)
        val result = buildModelEntryOverrides(
            baseModel = base(inputs = listOf("text"), outputs = listOf("text")),
            previous = previous,
            displayName = "Base Model",
            maxOutputTokensText = "",
            contextWindowText = "",
            thinkingEnabled = false,
            imageInput = false,
            pdfInput = false,
            audioInput = false,
            videoInput = false,
            imageOutput = false,
            audioOutput = false,
            maxThinkingLevel = null,
        )
        assertNull(result.maxThinkingLevel)
    }

    @Test
    fun unknownCatalogToken_passesThroughBase() {
        // Future catalog token with no switch — do not silently drop it.
        val model = base(inputs = listOf("text", "hologram"), outputs = listOf("text"))
        assertNull(saveUntouched(model).inputModalities)
    }

    // ── Criterion 3: maxThinkingLevel survives Save ──

    @Test
    fun save_keepsMaxThinkingLevelOverride() {
        val previous = ModelOverrides(maxThinkingLevel = ThinkingLevel.HIGH)
        val result = saveUntouched(
            base(inputs = listOf("text", "image"), outputs = listOf("text", "image")),
            previous = previous,
        )
        assertEquals(ThinkingLevel.HIGH, result.maxThinkingLevel)
        assertFalse(result.isEmpty)
        assertNull(result.outputModalities)
    }

    @Test
    fun save_keepsOtherFieldsTheScreenDoesNotOwn() {
        // Same field-ownership contract as maxThinkingLevel: anything copy()
        // does not name must survive.
        val previous = ModelOverrides(maxThinkingLevel = ThinkingLevel.ULTRA)
        val result = saveUntouched(base(inputs = listOf("text"), outputs = listOf("text")), previous)
        assertEquals(ThinkingLevel.ULTRA, result.maxThinkingLevel)
    }

    // ── Criterion 4: the six screen-owned fields keep pre-fix semantics ──

    @Test
    fun displayName_equalToBase_staysNull() {
        val model = base(inputs = listOf("text"), outputs = listOf("text"), displayName = "Same Name")
        val result = buildModelEntryOverrides(
            baseModel = model, previous = ModelOverrides(),
            displayName = "  Same Name  ", maxOutputTokensText = "", contextWindowText = "",
            thinkingEnabled = false, imageInput = false, pdfInput = false,
            audioInput = false, videoInput = false, imageOutput = false, audioOutput = false,
        )
        assertNull(result.displayName)
    }

    @Test
    fun displayName_divergingFromBase_isTrimmedAndPersisted() {
        val model = base()
        val result = buildModelEntryOverrides(
            baseModel = model, previous = ModelOverrides(),
            displayName = "  My Name  ", maxOutputTokensText = "512", contextWindowText = "200000",
            thinkingEnabled = true, imageInput = false, pdfInput = false,
            audioInput = false, videoInput = false, imageOutput = false, audioOutput = false,
        )
        assertEquals("My Name", result.displayName)
        assertEquals(512, result.maxOutputTokens)
        assertEquals(200000, result.contextWindow)
        assertEquals(true, result.supportsReasoning)
    }

    @Test
    fun blankOrNonPositiveNumbers_stayNull() {
        val model = base(supportsReasoning = true)
        val result = buildModelEntryOverrides(
            baseModel = model, previous = ModelOverrides(),
            displayName = model.displayName, maxOutputTokensText = "  ", contextWindowText = "0",
            thinkingEnabled = true, imageInput = false, pdfInput = false,
            audioInput = false, videoInput = false, imageOutput = false, audioOutput = false,
        )
        assertNull(result.maxOutputTokens)
        assertNull(result.contextWindow)
        assertNull(result.supportsReasoning) // equals base true → no override
    }

    // ── Criterion 5: deriveModalityList contract ──

    @Test
    fun deriveModalityList_togglesFirst_thenBasePassthrough() {
        val out = deriveModalityList(
            base = listOf("text", "audio"),
            toggles = listOf("image" to true, "audio" to true),
        )
        // toggle order (image, audio) first, then base passthrough (text); the
        // audio dimension is owned by the switch so it is not re-added.
        assertEquals(listOf("image", "audio", "text"), out)
    }

    @Test
    fun deriveModalityList_normalizesDedupesAndDropsJunk() {
        val out = deriveModalityList(
            base = listOf("Text", "text_input", "image", "_input"),
            toggles = emptyList(),
        )
        // "_input" normalizes to "" and must not reach the persisted list.
        assertEquals(listOf("text", "image"), out)
    }

    // ── Criterion 6: end-to-end consumer contract (why this matters) ──

    @Test
    fun savedOverride_keepsHybridModelUsableForTextChat() {
        val model = base(inputs = listOf("text", "image"), outputs = listOf("text", "image"))
        val entry = ModelEntry(
            providerInstanceId = "inst-1",
            baseModel = model,
            overrides = saveUntouched(model),
        )
        assertNull(entry.overrides.outputModalities)
        assertTrue(entry.model.isTextOutput)
    }

    @Test
    fun savedOverride_afterEnablingImageIO_stillTextOutput() {
        // Text-only base; user flips BOTH image switches on.
        val model = base(inputs = listOf("text"), outputs = listOf("text"))
        val overrides = buildModelEntryOverrides(
            baseModel = model, previous = ModelOverrides(),
            displayName = model.displayName, maxOutputTokensText = "", contextWindowText = "",
            thinkingEnabled = false, imageInput = true, pdfInput = false,
            audioInput = false, videoInput = false, imageOutput = true, audioOutput = false,
        )
        val entry = ModelEntry(providerInstanceId = "inst-1", baseModel = model, overrides = overrides)
        assertEquals(listOf("image", "text"), entry.overrides.outputModalities)
        assertEquals(listOf("image", "text"), entry.overrides.inputModalities)
        assertTrue(entry.model.isTextOutput)
        assertEquals(listOf("image", "text"), entry.model.outputModalities)
    }

    @Test
    fun counterfactual_toggleOnlyOverride_breaksTextOutput() {
        // The pre-fix shape, frozen as damage evidence: a toggle-only list that
        // dropped "text" makes the same hybrid model report isTextOutput=false
        // (→ dropped from the new-chat default candidates,
        // ProviderRepository) and pure-image-generator=true (→ pinned to the
        // Images endpoint, ModelUseProtocol).
        val model = base(inputs = listOf("text", "image"), outputs = listOf("text", "image"))
        val broken = ModelEntry(
            providerInstanceId = "inst-1",
            baseModel = model,
            overrides = ModelOverrides(outputModalities = listOf("image")),
        )
        assertFalse(broken.model.isTextOutput)
        assertFalse(broken.overrides.outputModalities!!.contains("text"))
    }
}
