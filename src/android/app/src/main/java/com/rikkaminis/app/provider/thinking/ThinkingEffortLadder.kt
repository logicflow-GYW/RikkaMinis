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

    /**
     * [fix/thinking-level-ceiling-1118] Every tier STRICTLY WEAKER than [tier], ladder
     * order. Empty when [tier] is off the ladder or already the weakest one.
     *
     * This is the shape a "this tier is unsupported" complaint can be turned into: the
     * gateway names the tier it rejected, and everything below it stays legal. Callers
     * must treat an EMPTY answer as "nothing learned" rather than "nothing is legal" —
     * clamping a request onto an empty set is not defined here and the gateway never
     * said the weaker tiers fail too.
     *
     * Lives here so the ordering has exactly one owner; `EffortTierLearner` must not
     * grow a second copy of the ladder.
     */
    fun tiersBelow(tier: String): List<String> {
        val i = LADDER.indexOf(tier.lowercase())
        return if (i <= 0) emptyList() else LADDER.subList(0, i).toList()
    }

    /**
     * [fix/thinking-level-ceiling-1118] The highest client tier a learned tier set
     * still allows, or null when the set names no reasoning tier at all.
     *
     * This is the DISPLAY side of what [clampEffort] does on the wire: the picker may
     * only offer tiers the gateway has not already refused, so a learned set also
     * lowers the ceiling the composer shows. `none` / `minimal` carry no
     * [ThinkingLevel] counterpart (none IS the off state; minimal has no UI entry), so
     * a set that stops there returns null — the caller keeps the catalog ceiling
     * rather than rendering an empty picker.
     */
    fun highestLevelFor(tiers: List<String>?): ThinkingLevel? {
        if (tiers.isNullOrEmpty()) return null
        var best: ThinkingLevel? = null
        for (tier in tiers) {
            val level = LEVEL_BY_EFFORT[tier.lowercase()] ?: continue
            if (best == null || level.rank > best.rank) best = level
        }
        return best
    }

    /**
     * [fix/thinking-level-ceiling-1118] [catalogMax] lowered to whatever a learned set
     * still allows. Never raises the ceiling: learning can only take tiers away.
     *
     * AUTO returns unchanged — it carries no intensity, and the picker treats it as
     * exempt from the ceiling (see `availableThinkingLevels`).
     */
    fun lowerCeiling(catalogMax: ThinkingLevel, learned: List<String>?): ThinkingLevel {
        if (catalogMax == ThinkingLevel.AUTO) return catalogMax
        val learnedMax = highestLevelFor(learned) ?: return catalogMax
        return if (learnedMax.rank < catalogMax.rank) learnedMax else catalogMax
    }

    /**
     * [fix/effort-key-1119] The learned set narrowed by what the MODEL declares.
     *
     * A ceiling parsed from a `THINKING_LEVEL_XHIGH` rejection is a ladder PREFIX — it
     * says "everything strictly weaker than xhigh is still legal" and nothing about
     * which of those tiers THIS model's endpoint actually accepts. Fed to [clampEffort]
     * on its own it can reintroduce a tier the catalog never declared (e.g. `medium` on
     * a model that declares only `low` / `high`), which the gateway then rejects with
     * the very 400 this entry was learned from.
     *
     * So when the model declares a set, the usable set is the INTERSECTION of "the
     * gateway has not refused it" and "the model declares it". Three readings:
     *
     *  - `declared` empty/null → [learned] unchanged: a model that declares nothing
     *    still gets the full weight of the gateway's own answer.
     *  - [learned] empty/null → [declared] unchanged: nothing was learned, the catalog
     *    keeps its say (identical to pre-fix behaviour).
     *  - the intersection is EMPTY (the two sources name disjoint tiers) → the weakest
     *    DECLARED tier. Deliberately not [learned]: the harm this function exists to
     *    remove is sending a tier the model never declared, and falling back to a
     *    learned tier here would reintroduce exactly that. One tier, not the whole
     *    declaration, so the request still sits as low as the model allows — the
     *    contradiction is a guess either way, and the next 400 teaches us again.
     *
     * Ordered by [LADDER] so the result is deterministic regardless of input order.
     */
    fun learnedWithinDeclared(learned: List<String>?, declared: List<String>?): List<String>? {
        if (declared.isNullOrEmpty()) return learned
        if (learned.isNullOrEmpty()) return declared
        val declaredTiers = declared.map { it.lowercase() }.toSet()
        val both = learned.map { it.lowercase() }
            .filter { it in declaredTiers }
            .distinct()
            .sortedBy { LADDER.indexOf(it) }
        return both.ifEmpty { listOf(weakestDeclared(declared)) }
    }

    /**
     * The weakest tier of [declared] by [LADDER] order. A word off the ladder sorts
     * LAST (i.e. treated as the strongest), so an unrecognised declaration cannot win
     * this pick — [clampEffort] ignores such words entirely, and returning only unknown
     * words would leave the request unclamped.
     */
    private fun weakestDeclared(declared: List<String>): String {
        val ranked = declared.map { it.lowercase() }
            .minByOrNull { tier -> LADDER.indexOf(tier).let { if (it < 0) Int.MAX_VALUE else it } }
        return ranked ?: declared.first().lowercase()
    }

    /** Inverse of [wireEffort] for the tiers that have a client counterpart. */
    private val LEVEL_BY_EFFORT = mapOf(
        "low" to ThinkingLevel.LOW,
        "medium" to ThinkingLevel.MEDIUM,
        "high" to ThinkingLevel.HIGH,
        "xhigh" to ThinkingLevel.XHIGH,
        "max" to ThinkingLevel.MAX,
    )

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
