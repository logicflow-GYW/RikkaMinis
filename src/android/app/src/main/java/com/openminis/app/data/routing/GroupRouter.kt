package com.openminis.app.data.routing

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy

/**
 * Pure-JVM group routing engine — decides "which member to use now" and "in
 * which order to fall back", owning per-member runtime health.
 *
 * Same pattern as ContextCompactor / ToolFailureHook: no Android dependencies,
 * all time controlled by the injectable [clock] so unit tests advance time
 * deterministically. ChatViewModel instantiates one (like ToolFailureHook) and
 * delegates its inline routing decisions here.
 *
 * Phase 1: pure extraction of the decisions that used to live inline in
 * ChatViewModel (`resolveProviderFromGroup` / `buildFallbackProviders`) —
 * zero behavior change. The health map is consulted but never populated yet,
 * so [isUsable] always returns true. Phase 2 wires [recordResult] into the
 * loop's error classification and the health gate becomes real.
 */
class GroupRouter(
    /** Clock in epoch-millis (default: System.currentTimeMillis). */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val health = mutableMapOf<String, MemberHealth>()

    // ── selection ──────────────────────────────────────────────────────────

    /**
     * Select the member to use for a new resolution.
     *
     * @param group the group being resolved
     * @param members pre-filtered candidates (enabled instances with
     *   credentials — the caller filters, e.g. `enabledMemberEntries`)
     * @param preferredEntryId prior session binding ("user picked this entry
     *   inside the group last time"); honored only when still in [members],
     *   otherwise the first usable member so the session can proceed on a
     *   now-degraded group
     * @param stickyEntryId last-used entry — the loadBalance rotation anchor
     *   (rotates one step past it); ignored by the other strategies
     * @return the chosen entry id, or null when [members] is empty or none is
     *   currently usable
     */
    fun select(
        group: ModelGroup,
        members: List<ModelEntry>,
        preferredEntryId: String? = null,
        stickyEntryId: String? = null,
    ): String? {
        if (members.isEmpty()) return null
        val usable = members.filter { isUsable(it.id) }
        if (usable.isEmpty()) return null
        return when {
            preferredEntryId != null ->
                usable.firstOrNull { it.id == preferredEntryId }?.id ?: usable.first().id
            group.strategy == RoutingStrategy.cheapestFirst ->
                usable.minWithOrNull(compareBy<ModelEntry> { it.costTier ?: Int.MAX_VALUE })!!.id
            group.strategy == RoutingStrategy.loadBalance -> {
                val lastIdx = usable.indexOfFirst { it.id == stickyEntryId }
                usable[(lastIdx + 1) % usable.size].id
            }
            else -> usable.first().id
        }
    }

    /**
     * [T-per-message-load-balance] Pick the member that serves the NEXT new
     * user turn in a loadBalance group — per-message rotation (was:
     * per-session rotation at group-resolution time only).
     *
     * Semantics (see ChatViewModel.rotateForNewTurn for the wiring):
     *  - anchor = the session's actually-active entry (NOT the global
     *    lastUsedEntryId, which a restored old session can leave pointing at
     *    a member outside this group — that would make the rotation skip or
     *    clamp); advances one step past it with wrap-around.
     *  - [pendingEntryId] (one-shot user pick) wins when still usable — the
     *    user explicitly asked for this member for the next turn.
     *  - members whose provider is disabled / cooling / circuit-open / dead
     *    are skipped. If the CURRENT member is itself demoted (or absent
     *    from the group — e.g. a restored old session anchored outside),
     *    the first usable member is chosen instead of resending into a
     *    known-failing member. If EVERY member is unusable, null keeps the
     *    current member (stay put rather than break the session).
     *
     * @return the entry id to serve the next turn, or null to keep the
     *   current member (no group / not loadBalance / single-member group /
     *   every member unusable).
     */
    fun nextLoadBalanceMember(
        group: ModelGroup,
        currentEntryId: String?,
        pendingEntryId: String?,
        members: List<ModelEntry>,
    ): String? {
        if (group.strategy != RoutingStrategy.loadBalance) return null
        if (members.size <= 1) return null
        val usable = members.filter { isUsable(it.id) }
        if (usable.isEmpty()) return null
        // Current member missing from the group or demoted (cooling /
        // circuit-open / dead) → jump to the first usable member instead of
        // sending into a member we already know is failing.
        val currentIdx = usable.indexOfFirst { it.id == currentEntryId }
        if (currentIdx < 0) return usable.first().id
        // One-shot user pick for this turn (still usable, still a member).
        if (pendingEntryId != null && usable.any { it.id == pendingEntryId }) {
            return pendingEntryId
        }
        return usable[(currentIdx + 1) % usable.size].id
    }

    /**
     * Ordered fallback candidates for [group]: starts from the member AFTER
     * [activeEntryId] and cycles the group.
     *
     * [P0-fallback-anchor] semantics — anchor by the ACTUAL active entry id,
     * NOT by model.id — because a group can hold several entries for the SAME
     * modelId behind different instances/endpoints (e.g. deepseek-v4-flash via
     * a dead hub.oaifree.com key + via api.deepseek.com). Matching by modelId
     * returns the FIRST entry with that modelId, which may sit earlier than
     * the entry actually in use — the fallback chain would start from the
     * wrong point and even re-include the current entry itself, causing
     * repeated calls to the failing provider.
     *
     * When [activeEntryId] isn't in the group, falls back to a modelId match
     * via [modelIdOf] (entryId → model.id), or starts from index 1 when no
     * match either.
     *
     * The caller still filters the returned order (disabled instance, missing
     * credential, provider creation failure) — this is pure ordering.
     */
    fun fallbackOrder(
        group: ModelGroup,
        activeEntryId: String?,
        primaryModelId: String,
        modelIdOf: (String) -> String?,
        costTierOf: (String) -> Int? = { null },
    ): List<String> {
        val members = group.memberEntryIds
        // [T-recovery] cheapestFirst: fall back in ascending cost order
        // (cheapest first, unannotated = most expensive), skipping the
        // currently-active member — a failure demotes only the active one,
        // the rest of the chain keeps its cost order.
        if (group.strategy == RoutingStrategy.cheapestFirst) {
            return members
                .filter { it != activeEntryId }
                .sortedBy { costTierOf(it) ?: Int.MAX_VALUE }
        }
        val currentIdx = if (activeEntryId != null && members.contains(activeEntryId)) {
            members.indexOf(activeEntryId)
        } else {
            members.indexOfFirst { modelIdOf(it) == primaryModelId }
        }
        val order = mutableListOf<String>()
        for (offset in 1 until members.size) {
            val idx = if (currentIdx >= 0) (currentIdx + offset) % members.size else offset
            order.add(members[idx])
        }
        return order
    }

    // ── health ─────────────────────────────────────────────────────────────

    companion object {
        /**
         * Circuit breaker: consecutive-ish 5xx failures at which the circuit
         * opens and the member is skipped for [CIRCUIT_OPEN_MS].
         */
        const val CIRCUIT_FAILURE_THRESHOLD = 3

        /** Circuit open duration after the threshold is breached (5 minutes). */
        const val CIRCUIT_OPEN_MS = 5 * 60_000L

        /**
         * Default rate-limit cooldown when the provider doesn't send a usable
         * Retry-After header: 60 seconds. Long enough to let a burst rate
         * limit settle, short enough that a 5-hour free-tier window (the
         * user's real scenario: 500 calls / 5 h on a free key) is not
         * prolonged indefinitely. (Moved from ChatViewModel — this is now the
         * single owner of the constant.)
         */
        const val RATE_LIMIT_COOLDOWN_DEFAULT_MS = 60_000L

        /** Hard cap on health entries — stale members must not leak memory. */
        const val MAX_HEALTH_ENTRIES = 256
    }

    /**
     * Record a request outcome, updating the member's runtime health:
     *
     *  - [RouteOutcome.RateLimited] → [MemberHealth.Cooling]
     *    (`retryAfterMs` when the provider sent Retry-After, else the default)
     *  - [RouteOutcome.ServerError]  → failure counter; at
     *    [CIRCUIT_FAILURE_THRESHOLD] the circuit opens for [CIRCUIT_OPEN_MS]
     *    (below threshold the member stays usable but keeps counting)
     *  - [RouteOutcome.AuthError]    → [MemberHealth.Dead] (until re-auth)
     *  - [RouteOutcome.Success]      → back to [MemberHealth.Healthy] (clears
     *    any demotion; also the half-open probe's close signal)
     *
     * NetworkError / TransientError deliberately never reach here (see
     * RouteOutcome) — transient connectivity is the user's side and would
     * churn the whole group over a wifi blip.
     */
    fun recordResult(entryId: String, outcome: RouteOutcome) {
        val now = clock()
        when (outcome) {
            RouteOutcome.Success -> health[entryId] = MemberHealth.Healthy

            is RouteOutcome.RateLimited -> {
                val untilMs = now + (outcome.retryAfterMs ?: RATE_LIMIT_COOLDOWN_DEFAULT_MS)
                health[entryId] = MemberHealth.Cooling(untilMs)
            }

            RouteOutcome.ServerError -> {
                val current = health[entryId]
                val failures = if (current is MemberHealth.OpenCircuit) current.failures + 1 else 1
                // Below threshold: OpenCircuit(now) is immediately usable but
                // carries the counter. At threshold: circuit opens for real.
                health[entryId] = MemberHealth.OpenCircuit(
                    untilMs = if (failures >= CIRCUIT_FAILURE_THRESHOLD) now + CIRCUIT_OPEN_MS else now,
                    failures = failures,
                )
            }

            RouteOutcome.AuthError -> health[entryId] = MemberHealth.Dead
        }
        // Bounded memory: drop the oldest entries beyond the cap (simple FIFO).
        if (health.size > MAX_HEALTH_ENTRIES) {
            val eldest = health.keys.firstOrNull() ?: return
            health.remove(eldest)
        }
    }

    /**
     * Forget all health state. Called on explicit user selection
     * (selectGroup / selectGroupEntry / selectEntry) — an explicit pick is a
     * "I want to work with this" signal that overrides any cooldown / circuit
     * / dead state (also how a re-authed member becomes usable again without
     * an app restart).
     */
    fun clearHealth() {
        health.clear()
    }

    /** True when the member may be selected now (no entry = healthy). */
    fun isUsable(entryId: String): Boolean = health[entryId]?.isUsable(clock()) ?: true

    /** Current health of a member (no entry = healthy). */
    fun healthOf(entryId: String): MemberHealth = health[entryId] ?: MemberHealth.Healthy
}
