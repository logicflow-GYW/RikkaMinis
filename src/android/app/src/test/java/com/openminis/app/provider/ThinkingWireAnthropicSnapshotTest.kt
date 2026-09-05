package com.openminis.app.provider

import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.anthropic.AnthropicProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GOLDEN SNAPSHOT of the Anthropic thinking mapping tables — the provider-side
 * counterpart to [com.openminis.app.provider.thinking.ThinkingWireGeminiSnapshotTest].
 *
 * Anthropic emission is driven by three public companion tables
 * ([AnthropicProvider.modelUsesAdaptiveThinking], [AnthropicProvider.thinkingEffort],
 * [AnthropicProvider.thinkingBudget]); this file pins their output across every level
 * and the adaptive/legacy split, so a refactor that "simplifies" one of them cannot
 * silently change the wire for a whole Claude family.
 *
 * A diff here is a wire-format change. Do not regenerate expectations to turn a red
 * test green without explaining, in words, why the mapping legitimately changed.
 */
class ThinkingWireAnthropicSnapshotTest {

    private val models = listOf(
        "claude-opus-4-8", "claude-opus-4.8", "claude-sonnet-4-6", "claude-sonnet-4-5",
        "claude-haiku-4-5", "claude-opus-4-1", "claude-3-7-sonnet", "not-a-claude",
    )

    private val levels = listOf(
        ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM,
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX, ThinkingLevel.ULTRA,
    )

    private fun render(): String = buildString {
        for (m in models) {
            val adaptive = AnthropicProvider.modelUsesAdaptiveThinking(m)
            for (lv in levels) {
                val effort = AnthropicProvider.thinkingEffort(lv)
                for (mt in listOf(8192, 65536)) {
                    val budget = AnthropicProvider.thinkingBudget(mt, lv)
                    append("$m/$lv/mt$mt -> adaptive=$adaptive effort=$effort budget=$budget")
                    append('\n')
                }
            }
        }
    }.trimEnd('\n')

    @Test
    fun `anthropic thinking tables golden snapshot`() {
        val actual = render()
        println("===ANTHROPIC_EXPECTED_START===")
        println(EXPECTED)
        println("===ANTHROPIC_EXPECTED_END===")
        println("===ANTHROPIC_ACTUAL_START===")
        println(actual)
        println("===ANTHROPIC_ACTUAL_END===")
        assertEquals(
            """
            ANTHROPIC THINKING MAPPING CHANGED.

            Byte-for-byte oracle for the Anthropic thinking tables. A failure means one
            of the three companion functions emits something different for a (model,
            level, maxTokens) combination. Diff the blocks and restore parity or justify
            explicitly.
            """.trimIndent(),
            EXPECTED,
            actual,
        )
    }

    companion object {
        private val EXPECTED = """
claude-opus-4-8/OFF/mt8192 -> adaptive=true effort=low budget=0
claude-opus-4-8/OFF/mt65536 -> adaptive=true effort=low budget=0
claude-opus-4-8/LOW/mt8192 -> adaptive=true effort=low budget=8191
claude-opus-4-8/LOW/mt65536 -> adaptive=true effort=low budget=8192
claude-opus-4-8/MEDIUM/mt8192 -> adaptive=true effort=medium budget=8191
claude-opus-4-8/MEDIUM/mt65536 -> adaptive=true effort=medium budget=32768
claude-opus-4-8/HIGH/mt8192 -> adaptive=true effort=high budget=8191
claude-opus-4-8/HIGH/mt65536 -> adaptive=true effort=high budget=65535
claude-opus-4-8/XHIGH/mt8192 -> adaptive=true effort=max budget=8191
claude-opus-4-8/XHIGH/mt65536 -> adaptive=true effort=max budget=65535
claude-opus-4-8/MAX/mt8192 -> adaptive=true effort=max budget=8191
claude-opus-4-8/MAX/mt65536 -> adaptive=true effort=max budget=65535
claude-opus-4-8/ULTRA/mt8192 -> adaptive=true effort=max budget=8191
claude-opus-4-8/ULTRA/mt65536 -> adaptive=true effort=max budget=65535
claude-opus-4.8/OFF/mt8192 -> adaptive=true effort=low budget=0
claude-opus-4.8/OFF/mt65536 -> adaptive=true effort=low budget=0
claude-opus-4.8/LOW/mt8192 -> adaptive=true effort=low budget=8191
claude-opus-4.8/LOW/mt65536 -> adaptive=true effort=low budget=8192
claude-opus-4.8/MEDIUM/mt8192 -> adaptive=true effort=medium budget=8191
claude-opus-4.8/MEDIUM/mt65536 -> adaptive=true effort=medium budget=32768
claude-opus-4.8/HIGH/mt8192 -> adaptive=true effort=high budget=8191
claude-opus-4.8/HIGH/mt65536 -> adaptive=true effort=high budget=65535
claude-opus-4.8/XHIGH/mt8192 -> adaptive=true effort=max budget=8191
claude-opus-4.8/XHIGH/mt65536 -> adaptive=true effort=max budget=65535
claude-opus-4.8/MAX/mt8192 -> adaptive=true effort=max budget=8191
claude-opus-4.8/MAX/mt65536 -> adaptive=true effort=max budget=65535
claude-opus-4.8/ULTRA/mt8192 -> adaptive=true effort=max budget=8191
claude-opus-4.8/ULTRA/mt65536 -> adaptive=true effort=max budget=65535
claude-sonnet-4-6/OFF/mt8192 -> adaptive=true effort=low budget=0
claude-sonnet-4-6/OFF/mt65536 -> adaptive=true effort=low budget=0
claude-sonnet-4-6/LOW/mt8192 -> adaptive=true effort=low budget=8191
claude-sonnet-4-6/LOW/mt65536 -> adaptive=true effort=low budget=8192
claude-sonnet-4-6/MEDIUM/mt8192 -> adaptive=true effort=medium budget=8191
claude-sonnet-4-6/MEDIUM/mt65536 -> adaptive=true effort=medium budget=32768
claude-sonnet-4-6/HIGH/mt8192 -> adaptive=true effort=high budget=8191
claude-sonnet-4-6/HIGH/mt65536 -> adaptive=true effort=high budget=65535
claude-sonnet-4-6/XHIGH/mt8192 -> adaptive=true effort=max budget=8191
claude-sonnet-4-6/XHIGH/mt65536 -> adaptive=true effort=max budget=65535
claude-sonnet-4-6/MAX/mt8192 -> adaptive=true effort=max budget=8191
claude-sonnet-4-6/MAX/mt65536 -> adaptive=true effort=max budget=65535
claude-sonnet-4-6/ULTRA/mt8192 -> adaptive=true effort=max budget=8191
claude-sonnet-4-6/ULTRA/mt65536 -> adaptive=true effort=max budget=65535
claude-sonnet-4-5/OFF/mt8192 -> adaptive=false effort=low budget=0
claude-sonnet-4-5/OFF/mt65536 -> adaptive=false effort=low budget=0
claude-sonnet-4-5/LOW/mt8192 -> adaptive=false effort=low budget=8191
claude-sonnet-4-5/LOW/mt65536 -> adaptive=false effort=low budget=8192
claude-sonnet-4-5/MEDIUM/mt8192 -> adaptive=false effort=medium budget=8191
claude-sonnet-4-5/MEDIUM/mt65536 -> adaptive=false effort=medium budget=32768
claude-sonnet-4-5/HIGH/mt8192 -> adaptive=false effort=high budget=8191
claude-sonnet-4-5/HIGH/mt65536 -> adaptive=false effort=high budget=65535
claude-sonnet-4-5/XHIGH/mt8192 -> adaptive=false effort=max budget=8191
claude-sonnet-4-5/XHIGH/mt65536 -> adaptive=false effort=max budget=65535
claude-sonnet-4-5/MAX/mt8192 -> adaptive=false effort=max budget=8191
claude-sonnet-4-5/MAX/mt65536 -> adaptive=false effort=max budget=65535
claude-sonnet-4-5/ULTRA/mt8192 -> adaptive=false effort=max budget=8191
claude-sonnet-4-5/ULTRA/mt65536 -> adaptive=false effort=max budget=65535
claude-haiku-4-5/OFF/mt8192 -> adaptive=false effort=low budget=0
claude-haiku-4-5/OFF/mt65536 -> adaptive=false effort=low budget=0
claude-haiku-4-5/LOW/mt8192 -> adaptive=false effort=low budget=8191
claude-haiku-4-5/LOW/mt65536 -> adaptive=false effort=low budget=8192
claude-haiku-4-5/MEDIUM/mt8192 -> adaptive=false effort=medium budget=8191
claude-haiku-4-5/MEDIUM/mt65536 -> adaptive=false effort=medium budget=32768
claude-haiku-4-5/HIGH/mt8192 -> adaptive=false effort=high budget=8191
claude-haiku-4-5/HIGH/mt65536 -> adaptive=false effort=high budget=65535
claude-haiku-4-5/XHIGH/mt8192 -> adaptive=false effort=max budget=8191
claude-haiku-4-5/XHIGH/mt65536 -> adaptive=false effort=max budget=65535
claude-haiku-4-5/MAX/mt8192 -> adaptive=false effort=max budget=8191
claude-haiku-4-5/MAX/mt65536 -> adaptive=false effort=max budget=65535
claude-haiku-4-5/ULTRA/mt8192 -> adaptive=false effort=max budget=8191
claude-haiku-4-5/ULTRA/mt65536 -> adaptive=false effort=max budget=65535
claude-opus-4-1/OFF/mt8192 -> adaptive=false effort=low budget=0
claude-opus-4-1/OFF/mt65536 -> adaptive=false effort=low budget=0
claude-opus-4-1/LOW/mt8192 -> adaptive=false effort=low budget=8191
claude-opus-4-1/LOW/mt65536 -> adaptive=false effort=low budget=8192
claude-opus-4-1/MEDIUM/mt8192 -> adaptive=false effort=medium budget=8191
claude-opus-4-1/MEDIUM/mt65536 -> adaptive=false effort=medium budget=32768
claude-opus-4-1/HIGH/mt8192 -> adaptive=false effort=high budget=8191
claude-opus-4-1/HIGH/mt65536 -> adaptive=false effort=high budget=65535
claude-opus-4-1/XHIGH/mt8192 -> adaptive=false effort=max budget=8191
claude-opus-4-1/XHIGH/mt65536 -> adaptive=false effort=max budget=65535
claude-opus-4-1/MAX/mt8192 -> adaptive=false effort=max budget=8191
claude-opus-4-1/MAX/mt65536 -> adaptive=false effort=max budget=65535
claude-opus-4-1/ULTRA/mt8192 -> adaptive=false effort=max budget=8191
claude-opus-4-1/ULTRA/mt65536 -> adaptive=false effort=max budget=65535
claude-3-7-sonnet/OFF/mt8192 -> adaptive=false effort=low budget=0
claude-3-7-sonnet/OFF/mt65536 -> adaptive=false effort=low budget=0
claude-3-7-sonnet/LOW/mt8192 -> adaptive=false effort=low budget=8191
claude-3-7-sonnet/LOW/mt65536 -> adaptive=false effort=low budget=8192
claude-3-7-sonnet/MEDIUM/mt8192 -> adaptive=false effort=medium budget=8191
claude-3-7-sonnet/MEDIUM/mt65536 -> adaptive=false effort=medium budget=32768
claude-3-7-sonnet/HIGH/mt8192 -> adaptive=false effort=high budget=8191
claude-3-7-sonnet/HIGH/mt65536 -> adaptive=false effort=high budget=65535
claude-3-7-sonnet/XHIGH/mt8192 -> adaptive=false effort=max budget=8191
claude-3-7-sonnet/XHIGH/mt65536 -> adaptive=false effort=max budget=65535
claude-3-7-sonnet/MAX/mt8192 -> adaptive=false effort=max budget=8191
claude-3-7-sonnet/MAX/mt65536 -> adaptive=false effort=max budget=65535
claude-3-7-sonnet/ULTRA/mt8192 -> adaptive=false effort=max budget=8191
claude-3-7-sonnet/ULTRA/mt65536 -> adaptive=false effort=max budget=65535
not-a-claude/OFF/mt8192 -> adaptive=false effort=low budget=0
not-a-claude/OFF/mt65536 -> adaptive=false effort=low budget=0
not-a-claude/LOW/mt8192 -> adaptive=false effort=low budget=8191
not-a-claude/LOW/mt65536 -> adaptive=false effort=low budget=8192
not-a-claude/MEDIUM/mt8192 -> adaptive=false effort=medium budget=8191
not-a-claude/MEDIUM/mt65536 -> adaptive=false effort=medium budget=32768
not-a-claude/HIGH/mt8192 -> adaptive=false effort=high budget=8191
not-a-claude/HIGH/mt65536 -> adaptive=false effort=high budget=65535
not-a-claude/XHIGH/mt8192 -> adaptive=false effort=max budget=8191
not-a-claude/XHIGH/mt65536 -> adaptive=false effort=max budget=65535
not-a-claude/MAX/mt8192 -> adaptive=false effort=max budget=8191
not-a-claude/MAX/mt65536 -> adaptive=false effort=max budget=65535
not-a-claude/ULTRA/mt8192 -> adaptive=false effort=max budget=8191
not-a-claude/ULTRA/mt65536 -> adaptive=false effort=max budget=65535
""".trimIndent()
    }
}
