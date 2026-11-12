package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [gateway-context-1112] Regression tests for the context-window override
 * derivation.
 *
 * The defect this pins: the detail screen PREFILLS the context-window field
 * with a shape guess when no real value is known anywhere, and the save wrote
 * that guess into `ModelOverrides.contextWindow`. An override outranks every
 * source, so the guess was PINNED: the number the endpoint later reported for
 * the same model could never take effect, and the entry was stuck on the
 * guess forever.
 *
 * The rule under test: the override carries USER INTENT only. An untouched
 * prefill is not user intent.
 *
 * Every expected value is a hand-written literal.
 */
class ResolveContextWindowOverrideTest {

    private val baseWithoutWindow = LLMModel(
        id = "codebuddy-cn/hy4free",
        displayName = "Hy4",
        provider = "Custom",
    )

    private fun baseWithWindow(value: Int) = LLMModel(
        id = "codebuddy-cn/hy4free",
        displayName = "Hy4",
        provider = "Custom",
        contextWindow = value,
    )

    @Test
    fun `a blank field writes no override`() {
        val out = resolveContextWindowOverride("", baseWithoutWindow, ModelOverrides())
        assertNull(out)
    }

    @Test
    fun `a zero field writes no override`() {
        val out = resolveContextWindowOverride("0", baseWithoutWindow, ModelOverrides())
        assertNull(out)
    }

    @Test
    fun `a non-numeric field writes no override`() {
        val out = resolveContextWindowOverride("abc", baseWithoutWindow, ModelOverrides())
        assertNull(out)
    }

    @Test
    fun `the untouched prefill guess is NOT pinned`() {
        // The shape guess the screen pre-filled (unknown id → 1M) must not
        // become a persisted override, or it would outrank the endpoint's
        // own number forever.
        val guess = com.rikkaminis.app.data.model.inferContextWindowTokens(baseWithoutWindow)
        val out = resolveContextWindowOverride(guess.toString(), baseWithoutWindow, ModelOverrides())
        assertNull("the prefill guess must not be persisted as an override", out)
    }

    @Test
    fun `a value the user typed IS persisted`() {
        // A genuine edit on a model with no real window anywhere.
        val out = resolveContextWindowOverride("65536", baseWithoutWindow, ModelOverrides())
        assertEquals(65536, out)
    }

    @Test
    fun `a value equal to the base window inherits instead of freezing`() {
        val out = resolveContextWindowOverride("960000", baseWithWindow(960_000), ModelOverrides())
        assertNull("equal to the base → inherit, do not freeze", out)
    }

    @Test
    fun `a value different from the base window is persisted`() {
        val out = resolveContextWindowOverride("200000", baseWithWindow(960_000), ModelOverrides())
        assertEquals(200_000, out)
    }

    @Test
    fun `an unchanged existing override is kept verbatim`() {
        // The user set 200 000 before; the screen shows it; saving again
        // must not drop it.
        val previous = ModelOverrides(contextWindow = 200_000)
        val out = resolveContextWindowOverride("200000", baseWithWindow(960_000), previous)
        assertEquals(200_000, out)
    }

    @Test
    fun `an existing override the user changed is replaced`() {
        val previous = ModelOverrides(contextWindow = 200_000)
        val out = resolveContextWindowOverride("400000", baseWithWindow(960_000), previous)
        assertEquals(400_000, out)
    }

    @Test
    fun `clearing the field drops the existing override`() {
        // "inherit from the provider" is a legitimate action.
        val previous = ModelOverrides(contextWindow = 200_000)
        val out = resolveContextWindowOverride("", baseWithWindow(960_000), previous)
        assertNull(out)
    }
}
