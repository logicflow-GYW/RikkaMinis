package com.rikkaminis.app.provider

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ThinkingLevel

/**
 * [T-android-thinking-level-arch] Declarative catalog of each model's thinking-
 * level ceiling. Adding a model = adding a rule; retiring one = removing a rule.
 * It touches no other code path — a model that matches no rule falls back to
 * [catalogMaxThinkingLevel]'s conservative supportsReasoning default.
 *
 * Kept content-aligned with iOS ThinkingLevelCatalog.swift (same understanding
 * of what each model can do), Kotlin idiom on this side.
 */
object ThinkingLevelCatalog {
    private data class Rule(val match: (String) -> Boolean, val max: ThinkingLevel)

    private val rules: List<Rule> = listOf(
        // GPT-5.6 family: sol / terra / luna all reach MAX. ULTRA is a
        // client-side "Max + orchestration" concept, never a wire effort — the
        // effort layer maps both MAX and ULTRA to "max". Keep in lockstep with
        // iOS ThinkingLevelCatalog.swift.
        Rule({ it.startsWith("gpt-5.6-sol") || it.startsWith("gpt-5.6-terra") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-5.6-luna") }, ThinkingLevel.MAX),
        Rule({ it.startsWith("gpt-5.5") }, ThinkingLevel.XHIGH),
        // Third-party models known to top out at high.
        // MiMo ships BOTH id spellings in the wild: catalog docs say
        // "MiMo-2.5" but the live API (api.xiaomimimo.com /v1/models) returns
        // "mimo-v2.5" / "mimo-v2.5-pro" — the old "mimo-2.5" substring missed
        // those, so the clamp passed xhigh straight through to a backend that
        // 400s on it. Match the family, not one spelling (mirrors iOS 72968c4f).
        Rule({ it.contains("mimo") || it.contains("agnes") }, ThinkingLevel.HIGH),
        // ByteDance seed (Volcano Ark "seed-1.6…"/"seed-2.0…", OpenRouter
        // "bytedance-seed/…"): rejects xhigh with "Invalid reasoning_effort:
        // xhigh". Ark's ladder tops out at high.
        Rule({ it.contains("seed-") || it.contains("bytedance-seed") }, ThinkingLevel.HIGH),
        // Anthropic Opus 4.x adaptive-thinking family. The old per-version
        // startsWith("claude-opus-4.7"/"claude-opus-4.6") checks never matched:
        // LLMModel.id separates the minor version with a hyphen
        // (claude-opus-4-8 / claude-opus-4-6), not a dot, so every Claude Opus
        // fell through to the XHIGH default instead of MAX — and Opus 4.8 had no
        // rule at all. Normalize dots→hyphens first, then a single prefix match
        // covers 4.6 / 4.7 / 4.8 and future 4.x (mirrors iOS normalizedHasPrefix).
        Rule({ normalizedHasPrefix(it, "claude-opus-4") }, ThinkingLevel.MAX),
    )

    /** Prefix match that treats "." and "-" interchangeably in the version
     *  separator so a rule matches whether the id is dotted or hyphenated. */
    private fun normalizedHasPrefix(id: String, prefix: String): Boolean =
        id.replace('.', '-').startsWith(prefix)

    /** Null means the catalog doesn't cover this model — the caller should fall
     *  through to the supportsReasoning default. */
    fun declaredMaxLevel(modelId: String): ThinkingLevel? {
        val lid = modelId.lowercase()
        return rules.firstOrNull { it.match(lid) }?.max
    }
}

/**
 * [T-android-thinking-level-arch] "How high can this model's thinking go?"
 * resolved through the built-in tiers only (no user override — see
 * [ModelEntry.effectiveMaxThinkingLevel] for that):
 *   1. supportsReasoning == false → OFF (checked BEFORE catalog rules so a
 *      broadened family rule can't lift a non-reasoning member's ceiling).
 *   2. ThinkingLevelCatalog rule.
 *   3. true/null → XHIGH (conservative default so a reasoning model isn't
 *      accidentally capped below the tiers every provider already accepted
 *      pre-GPT-5.6).
 */
val LLMModel.catalogMaxThinkingLevel: ThinkingLevel
    get() {
        // A model that can't reason has max level OFF regardless of any
        // catalog family rule — family rules match by id substring, so a
        // broadened rule (e.g. "mimo" covering mimo-v2.5) must not lift the
        // ceiling of that family's non-reasoning members (mimo-v2.5-tts/-asr).
        // [T-fallback-thinking-preclamp]
        if (supportsReasoning == false) return ThinkingLevel.OFF
        // [T-thinking-levels-data-driven] A declared effort set is a stronger
        // statement than any id-substring rule: it names the exact tiers the
        // backend accepts. Take its top tier as the ceiling so a model whose
        // declaration reaches beyond the hardcoded default (XHIGH) — e.g.
        // zhipuai glm-5.2 / deepseek-v4, both ["high","max"] — is actually
        // reachable from the UI (mirrors iOS 47dc71b3). Fixes only the CEILING;
        // clampEffort stays as-is.
        selectableThinkingLevels.lastOrNull()?.let { return it }
        return ThinkingLevelCatalog.declaredMaxLevel(id) ?: ThinkingLevel.XHIGH
    }

/**
 * [T-thinking-levels-data-driven] The thinking levels worth OFFERING for this
 * model, derived from the catalog's declared effort tiers. Returning one level
 * per DISTINCT declared tier makes the picker honest: every option produces a
 * different request. Empty when nothing is declared, so the legacy id-rule
 * ceiling still applies. OFF is never included (it's a separate toggle).
 */
val LLMModel.selectableThinkingLevels: List<ThinkingLevel>
    get() {
        val declared = reasoningEffortValues
        if (declared.isNullOrEmpty()) return emptyList()
        val mapping = listOf(
            "low" to ThinkingLevel.LOW,
            "medium" to ThinkingLevel.MEDIUM,
            "high" to ThinkingLevel.HIGH,
            "xhigh" to ThinkingLevel.XHIGH,
            "max" to ThinkingLevel.MAX,
        )
        val set = declared.map { it.lowercase() }.toSet()
        return mapping.filter { set.contains(it.first) }.map { it.second }
    }

/**
 * [T-android-thinking-level-arch] The four-level resolution the rest of the app
 * consults: the user's manual override on the entry (highest priority) wins over
 * the catalog/default. `entry.model` already folds ModelOverrides into the base
 * model, so read the ceiling off the resolved model there.
 */
val ModelEntry.effectiveMaxThinkingLevel: ThinkingLevel
    get() = overrides.maxThinkingLevel ?: model.catalogMaxThinkingLevel

/**
 * [T-thinking-effective-level] The level that will ACTUALLY be sent for the
 * current turn, given (requested level, model capability, ceiling).
 *
 * This is the single source of truth the UI must read. Before it existed, three
 * layers answered "what level is in force?" independently and could disagree:
 *   • the navbar badge read the RAW user choice;
 *   • AgentLoopEngine applied `if (supportsReasoning) choice else OFF`;
 *   • the provider layer additionally clamped to the model ceiling.
 * A group rotation onto a non-reasoning member therefore produced a badge
 * claiming "High" while the wire carried OFF, a level sheet with no row
 * ticked, and a picker whose taps were silently swallowed — a split state the
 * user reads as "it turned itself off". Deriving every consumer from one
 * expression makes that disagreement unrepresentable.
 *
 * Pure function on purpose: no Android, no provider, no state — so the whole
 * (requested × supportsReasoning × ceiling) space is JVM-testable.
 */
fun effectiveThinkingLevel(
    requested: ThinkingLevel,
    supportsReasoning: Boolean,
    ceiling: ThinkingLevel,
): ThinkingLevel {
    // A model that cannot reason has no meaningful effort field: the request
    // is sent with thinking OFF regardless of what the user picked. Mirrors
    // the guard AgentLoopEngine used to apply at its call site.
    if (!supportsReasoning) return ThinkingLevel.OFF
    // AUTO expresses no intensity and is never clamped — its appended rank
    // (8) is an artifact of the append-only enum rule, not an intensity.
    if (requested == ThinkingLevel.AUTO) return requested
    return if (requested.rank > ceiling.rank) ceiling else requested
}

/**
 * [T-thinking-effective-level] Whether the user's stored choice is being
 * silently capped by the current model. The picker draws its orange
 * up-arrow cue from this.
 *
 * AUTO never counts as capped: it expresses "let the vendor decide" and its
 * appended rank (8) is an artifact of the append-only enum rule, not an
 * intensity. Reading it as "above the ceiling" made AUTO and a real tier
 * highlight simultaneously (two selected-looking capsules).
 *
 * OFF likewise: it is a switch, not an intensity below the ceiling.
 */
fun isCappedBy(requested: ThinkingLevel, ceiling: ThinkingLevel): Boolean =
    requested.isEnabled && requested != ThinkingLevel.AUTO && requested.rank > ceiling.rank

/**
 * [T-thinking-effective-level] What tapping a picker capsule must DO.
 *
 * The picker's convention is "tap the capsule that is already your setting to
 * switch thinking off; tap any other capsule to select it". That convention
 * has to be keyed off the RAW stored choice, not the highlight:
 *
 *  • Unclamped (`requested == current == level`) — the highlighted capsule is
 *    the user's own setting, so a tap turns thinking off. Unchanged.
 *  • Clamped — the orange up-arrow capsule is the MODEL'S CEILING, not the
 *    user's setting. The arrow invites "use this model's maximum", so a tap
 *    must SELECT it. Keying off the highlight sent that tap to OFF instead:
 *    the user asked for the highest reachable tier and got thinking disabled —
 *    the reported symptom ("调到最高，但会出现关了的情况").
 *
 * OFF stays reachable: tap the just-selected capsule again, or use the OFF row
 * in ThinkingLevelSheet. Pure function so the whole (level × requested) space
 * is JVM-testable — the tap rule lives inside a @Composable, which the sandbox
 * cannot compile.
 */
fun thinkingTapTarget(level: ThinkingLevel, requested: ThinkingLevel): ThinkingLevel =
    if (level == requested) ThinkingLevel.OFF else level
