package com.openminis.app.provider.thinking

import com.openminis.app.data.model.ThinkingLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GOLDEN SNAPSHOT of [ThinkingRuleResolver.geminiThinkingConfig], the single owner of
 * the Gemini thinking contract since the Phase 2 §1 consolidation.
 *
 * Unlike [ThinkingRulesRegressionTest] (named assertions per known rule), this file
 * pins the ACTUAL output of every family branch across every level — including the
 * two guards Android previously lacked:
 *   • [T-gemini37-minimal-400] gemini-3.7+ Flash rejects `thinkingLevel:"minimal"`
 *     with a hard 400 at OFF → floor falls back to "low".
 *   • [T-gemini-tts-thinking-400] -tts/-image/-embedding/-vision ids reject ANY
 *     thinking parameter → config is null (OpenMinis#226).
 *
 * A diff here is a wire-format change for a whole Gemini family. Do not regenerate
 * the expectations to turn a red test green without explaining, in words, why the
 * contract legitimately changed.
 */
class ThinkingWireGeminiSnapshotTest {

    private val levels = listOf(
        ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
    )

    /** Stable textual form: sorted keys at every level, bools distinct from numbers. */
    private fun canonical(v: Any?): String = when (v) {
        null -> "null"
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "$it:${canonical(v.get(it))}" }
        is String -> "\"$v\""
        else -> v.toString()
    }

    private fun emit(modelId: String, level: ThinkingLevel): String =
        canonical(ThinkingRuleResolver.geminiThinkingConfig(modelId, level))

    private fun render(): String = buildString {
        val models = listOf(
            "gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite",
            "gemini-3-flash-preview", "gemini-3.5-flash", "gemini-3.7-flash",
            "gemini-3-pro", "gemini-3.1-flash-tts-preview",
            "gemini-3.1-flash-image-preview", "gemini-2.5-pro-embedding",
            "gemini-unknown-model",
        )
        for (m in models) {
            for (lv in levels) {
                append("$m/${lv.name} -> ${emit(m, lv)}")
                append('\n')
            }
        }
    }.trimEnd('\n')

    @Test
    fun `golden snapshot of every gemini family branch`() {
        assertEquals(
            """
            GEMINI THINKING CONFIG CHANGED.

            This is the byte-for-byte oracle for the Gemini thinking contract.
            If you are mid-refactor and see this fail, a family branch emits a
            different thinkingConfig than before. Diff the two blocks, identify
            which branch moved, and either restore parity or justify the change
            explicitly (e.g. a new vendor-measured guard).
            """.trimIndent(),
            EXPECTED,
            render(),
        )
    }

    companion object {
        private val EXPECTED = """
gemini-2.5-pro/OFF -> {thinkingBudget:128}
gemini-2.5-pro/LOW -> {includeThoughts:true,thinkingBudget:2048}
gemini-2.5-pro/MEDIUM -> {includeThoughts:true,thinkingBudget:8192}
gemini-2.5-pro/HIGH -> {includeThoughts:true,thinkingBudget:16384}
gemini-2.5-pro/XHIGH -> {includeThoughts:true,thinkingBudget:32768}
gemini-2.5-pro/MAX -> {includeThoughts:true,thinkingBudget:32768}
gemini-2.5-pro/ULTRA -> {includeThoughts:true,thinkingBudget:32768}
gemini-2.5-flash/OFF -> {thinkingBudget:0}
gemini-2.5-flash/LOW -> {includeThoughts:true,thinkingBudget:1024}
gemini-2.5-flash/MEDIUM -> {includeThoughts:true,thinkingBudget:4096}
gemini-2.5-flash/HIGH -> {includeThoughts:true,thinkingBudget:8192}
gemini-2.5-flash/XHIGH -> {includeThoughts:true,thinkingBudget:16384}
gemini-2.5-flash/MAX -> {includeThoughts:true,thinkingBudget:16384}
gemini-2.5-flash/ULTRA -> {includeThoughts:true,thinkingBudget:16384}
gemini-2.5-flash-lite/OFF -> null
gemini-2.5-flash-lite/LOW -> null
gemini-2.5-flash-lite/MEDIUM -> null
gemini-2.5-flash-lite/HIGH -> null
gemini-2.5-flash-lite/XHIGH -> null
gemini-2.5-flash-lite/MAX -> null
gemini-2.5-flash-lite/ULTRA -> null
gemini-3-flash-preview/OFF -> {thinkingLevel:"minimal"}
gemini-3-flash-preview/LOW -> {includeThoughts:true,thinkingLevel:"low"}
gemini-3-flash-preview/MEDIUM -> {includeThoughts:true,thinkingLevel:"medium"}
gemini-3-flash-preview/HIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3-flash-preview/XHIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3-flash-preview/MAX -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3-flash-preview/ULTRA -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3.5-flash/OFF -> {thinkingLevel:"minimal"}
gemini-3.5-flash/LOW -> {includeThoughts:true,thinkingLevel:"low"}
gemini-3.5-flash/MEDIUM -> {includeThoughts:true,thinkingLevel:"medium"}
gemini-3.5-flash/HIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3.5-flash/XHIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3.5-flash/MAX -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3.5-flash/ULTRA -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3.7-flash/OFF -> {thinkingLevel:"low"}
gemini-3.7-flash/LOW -> {includeThoughts:true,thinkingLevel:"low"}
gemini-3.7-flash/MEDIUM -> {includeThoughts:true,thinkingLevel:"medium"}
gemini-3.7-flash/HIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3.7-flash/XHIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3.7-flash/MAX -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3.7-flash/ULTRA -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3-pro/OFF -> {thinkingLevel:"low"}
gemini-3-pro/LOW -> {includeThoughts:true,thinkingLevel:"low"}
gemini-3-pro/MEDIUM -> {includeThoughts:true,thinkingLevel:"medium"}
gemini-3-pro/HIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3-pro/XHIGH -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3-pro/MAX -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3-pro/ULTRA -> {includeThoughts:true,thinkingLevel:"high"}
gemini-3.1-flash-tts-preview/OFF -> null
gemini-3.1-flash-tts-preview/LOW -> null
gemini-3.1-flash-tts-preview/MEDIUM -> null
gemini-3.1-flash-tts-preview/HIGH -> null
gemini-3.1-flash-tts-preview/XHIGH -> null
gemini-3.1-flash-tts-preview/MAX -> null
gemini-3.1-flash-tts-preview/ULTRA -> null
gemini-3.1-flash-image-preview/OFF -> null
gemini-3.1-flash-image-preview/LOW -> null
gemini-3.1-flash-image-preview/MEDIUM -> null
gemini-3.1-flash-image-preview/HIGH -> null
gemini-3.1-flash-image-preview/XHIGH -> null
gemini-3.1-flash-image-preview/MAX -> null
gemini-3.1-flash-image-preview/ULTRA -> null
gemini-2.5-pro-embedding/OFF -> null
gemini-2.5-pro-embedding/LOW -> null
gemini-2.5-pro-embedding/MEDIUM -> null
gemini-2.5-pro-embedding/HIGH -> null
gemini-2.5-pro-embedding/XHIGH -> null
gemini-2.5-pro-embedding/MAX -> null
gemini-2.5-pro-embedding/ULTRA -> null
gemini-unknown-model/OFF -> null
gemini-unknown-model/LOW -> null
gemini-unknown-model/MEDIUM -> null
gemini-unknown-model/HIGH -> null
gemini-unknown-model/XHIGH -> null
gemini-unknown-model/MAX -> null
gemini-unknown-model/ULTRA -> null
        """.trimIndent()
    }
}
