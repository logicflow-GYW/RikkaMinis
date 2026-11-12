package com.rikkaminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [gateway-context-1112] The two context-window heuristics must not drift.
 *
 * `LLMModel.contextWindowTokens` (runtime budget, agent loop, token
 * accounting) and `inferContextWindowTokens` (the detail screen's prefill
 * and the model-group slider ceiling) used to be SEPARATE tables. They
 * disagreed — most visibly on the unknown-id default, 1 000 000 in one and
 * 128 000 in the other — so the same model showed two different context
 * windows depending on which screen asked. That is exactly the "same model,
 * different context window" report.
 *
 * The contract now: one table, one owner. The helper delegates.
 *
 * Every expected value is a hand-written literal.
 */
class ContextWindowHeuristicSingleSourceTest {

    private val ids = listOf(
        "codebuddy-cn/hy4free",
        "chatgpt/gpt-6-luna",
        "codebuddy-cn/glm-5.3-flash",
        "codebuddy-cn/deepseek-v4.1-flash",
        "trae-cn/minimax-m3",
        "claude-sonnet-4-6",
        "claude-haiku-4-5",
        "gpt-5.2",
        "gpt-4o",
        "deepseek-v4",
        "gemini-1.0-pro",
        "totally-unknown-model",
    )

    @Test
    fun `the helper and the property agree on every id`() {
        for (id in ids) {
            val model = LLMModel(id, id, "Custom")
            assertEquals(
                "id=$id must have ONE context-window answer",
                model.contextWindowTokens,
                inferContextWindowTokens(model),
            )
        }
    }

    @Test
    fun `an explicit window is honoured by both`() {
        val model = LLMModel("codebuddy-cn/hy4free", "Hy4", "Custom", contextWindow = 960_000)
        assertEquals(960_000, model.contextWindowTokens)
        assertEquals(960_000, inferContextWindowTokens(model))
    }

    @Test
    fun `the unknown-id default is the optimistic one`() {
        // The value the runtime uses — the helper must not answer 128 000
        // while the runtime answers 1 000 000.
        val model = LLMModel("totally-unknown-model", "Unknown", "Custom")
        assertEquals(1_000_000, model.contextWindowTokens)
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }

    @Test
    fun `the gateway model id no longer lands on 128K`() {
        // The reported symptom, pinned: this id used to resolve to 128 000
        // in the detail screen's prefill.
        val model = LLMModel("codebuddy-cn/hy4free", "Hy4", "Custom")
        assertEquals(1_000_000, inferContextWindowTokens(model))
    }
}
