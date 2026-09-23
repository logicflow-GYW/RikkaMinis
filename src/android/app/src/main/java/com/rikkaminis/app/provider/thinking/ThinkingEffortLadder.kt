package com.rikkaminis.app.provider.thinking

import com.rikkaminis.app.data.model.ThinkingLevel

/**
 * [T-thinking-effort-ladder] The tier <-> wire-value mapping shared by every emitter.
 *
 * Extracted verbatim from `ThinkingRuleResolver` so the mapping can be exercised by a
 * plain JVM test without dragging the whole resolver (and its org.json / provider
 * dependencies) into the test compile unit. `ThinkingRuleResolver` keeps its public
 * functions as thin delegates, so no call site changed.
 *
 * The two halves mean different things:
 *  • [wireEffort] is the CLIENT's opinion — what "HIGH" is called on the wire.
 *  • [clampEffort] is the MODEL's constraint — the declared set overrides that opinion
 *    when the two disagree, degrading DOWNWARD so an unsupported request never 400s.
 */
object ThinkingEffortLadder {

    /** Ordering used by [clampEffort]. `ultra` is intentionally absent — see [wireEffort]. */
    private val LADDER = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    fun wireEffort(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF, ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // ULTRA is a client-side "Max + orchestration" concept and is NEVER a valid
        // server effort string (iOS b38bf3d5).
        ThinkingLevel.MAX, ThinkingLevel.ULTRA -> "max"
        ThinkingLevel.AUTO -> "low" // unreachable; AUTO returns at the top of apply()
    }

    /**
     * [T-android-xhigh-effort-clamp] MiMo/Agnes reject xhigh (400/422); their ladder tops
     * out at high. Matches the FAMILY substring, not one spelling — the live API serves
     * `mimo-v2.5` while docs say `mimo-2.5` (iOS 72968c4f).
     */
    fun clampEffortForModel(effort: String, lid: String): String =
        if (effort == "xhigh" && (lid.contains("mimo") || lid.contains("agnes"))) "high" else effort

    /** Snap a requested tier onto the model's declared set, walking DOWN then up. */
    fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val want = LADDER.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = LADDER.indexOf(v)
            if (i >= 0) i to v else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }
}
