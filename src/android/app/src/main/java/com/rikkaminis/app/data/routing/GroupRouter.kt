package com.rikkaminis.app.data.routing

import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelGroup
import com.rikkaminis.app.data.model.RoutingStrategy

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

    // ── composite route identity (T-multi-api-key) ─────────────────────────

    /**
     * [T-multi-api-key] Composite health key for ONE credential of ONE entry:
     * `"<entryId>#<keyIndex>"`.
     *
     * This is the whole trick that lets a multi-key instance reuse the group
     * router's existing circuit breaker / cooldown / half-open-probe machinery
     * WITHOUT touching it: a credential is just another routable member as far
     * as this map is concerned. A spent key cools alone, its siblings keep
     * serving, and the model/endpoint never even notices.
     *
     * `keyIndex == 0` maps to the bare [entryId] so every pre-existing health
     * record — and every single-key instance — keeps its historical key shape
     * and semantics. Single-credential providers are therefore byte-for-byte
     * unchanged, which is what keeps this change safe to land on main.
     */
    fun routeId(entryId: String, keyIndex: Int): String =
        if (keyIndex <= 0) entryId else "$entryId#$keyIndex"

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
        // [audit-0917] The one-shot user pick is checked FIRST. It used to sit
        // behind the `currentIdx < 0` early return, so whenever the current
        // member was demoted/absent the explicit pick was silently discarded
        // and the turn went to an arbitrary usable member — the opposite of the
        // KDoc's "wins when still usable".
        if (pendingEntryId != null && usable.any { it.id == pendingEntryId }) {
            return pendingEntryId
        }
        // Current member missing from the group or demoted (cooling /
        // circuit-open / dead) → jump to the first usable member instead of
        // sending into a member we already know is failing.
        val currentIdx = usable.indexOfFirst { it.id == currentEntryId }
        if (currentIdx < 0) return usable.first().id
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
         * opens for a while, so a relay that is down for everyone stops
         * burning requests. Deliberately NOT a per-key setting — the breaker
         * is a fact about the ENDPOINT, not about any one credential, and
         * keeping it short means a transient upstream blip (the user's real
         * scenario: 500 calls / 5 h on a free key) is not prolonged
         * indefinitely. (Moved from ChatViewModel — this is now the single
         * owner of the constant.)
         *
         * [T-multi-api-key] With composite route ids this cooldown is now
         * PER CREDENTIAL, not per instance — a 429 on one key parks that key
         * for 60s while its siblings keep serving, where previously the whole
         * entry went dark for a minute. No key-count scaling is applied
         * deliberately: a provider-supplied Retry-After is authoritative and
         * honored verbatim, and when the provider sends nothing the local
         * cooldown is a guess that a shorter value would only make less
         * conservative. Capacity under multi-key comes from rotating
         * ([rotateCredential]), not from shrinking the penalty.
         */
        const val RATE_LIMIT_COOLDOWN_DEFAULT_MS = 60_000L

        /**
         * Circuit breaker: consecutive-ish 5xx failures at which the circuit
         * opens and the member is skipped for [CIRCUIT_OPEN_MS].
         */
        const val CIRCUIT_FAILURE_THRESHOLD = 3

        /** Circuit open duration after the threshold is breached (5 minutes). */
        const val CIRCUIT_OPEN_MS = 5 * 60_000L

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

            // [T-multi-api-key] Terminal, and deliberately NOT a cooldown: the
            // spend is a fact about the credential, not a clock. Retrying on a
            // timer (the old ServerError path) was the bug — a top-up is the
            // only thing that clears it, so the state must persist until then.
            RouteOutcome.QuotaExhausted -> health[entryId] = MemberHealth.Exhausted
        }
        evictIfOverCapacity(clock())
    }

    /**
     * [T-multi-api-key] Bounded memory, with a fairness guard.
     *
     * The cap exists so stale members can't leak memory, but a naive FIFO drop
     * is now actively harmful: composite keys (`entry#0`, `entry#1`, ...) make
     * the map grow with credentials, so a busy instance could evict a
     * *legitimately cooling* key and hand it straight back to the rotation —
     * the exact thrash the cooldown was meant to prevent.
     *
     * So: drop expired demotions first (they carry no information a fresh
     * request wouldn't rediscover), and only fall back to FIFO when every
     * entry is still live. Terminal states (Dead / Exhausted) are the most
     * valuable records in the map and are evicted last.
     */
    private fun evictIfOverCapacity(now: Long) {
        if (health.size <= MAX_HEALTH_ENTRIES) return
        val overBy = health.size - MAX_HEALTH_ENTRIES
        // Pass 1: expired demotions, pure derived state, most-information-free
        // first. A lapsed COOLING window is the purest no-information record
        // (the demotion is over, nothing else is kept), so it goes first. A
        // below-threshold OpenCircuit is ALSO past its window, but it carries
        // the failure counter the circuit breaker accumulates on — dropping it
        // would silently reset the breaker and make it strictly less likely to
        // open on the next failure, so it is only spent once the lapsed
        // cooldowns are gone.
        val lapsedCooling = health.entries
            .filter { (_, h) -> h is MemberHealth.Cooling && h.isUsable(now) }
            .map { it.key }
        val lapsedCircuits = health.entries
            .filter { (_, h) -> h is MemberHealth.OpenCircuit && h.isUsable(now) && h.untilMs < now }
            .map { it.key }
        val expired = (lapsedCooling + lapsedCircuits).take(overBy)
        for (k in expired) health.remove(k)
        // Pass 2: oldest insertion order, but never a terminal record while a
        // live one is still present.
        while (health.size > MAX_HEALTH_ENTRIES) {
            val victim = health.keys.firstOrNull { health[it] !is MemberHealth.Dead && health[it] !is MemberHealth.Exhausted }
                ?: health.keys.firstOrNull()
                ?: break
            health.remove(victim)
        }
    }

    // ── credential rotation (T-multi-api-key) ──────────────────────────────

    /**
     * [T-multi-api-key] Pick the next credential index to try on the SAME
     * entry after the one at [fromIndex] failed, or null when no sibling
     * credential is currently usable.
     *
     * Rotation is round-robin starting one step past [fromIndex], skipping
     * every index whose composite route id is demoted. This is the "spend
     * another key" rung of the escalation ladder — it changes nothing but the
     * `Authorization` header, so it is always cheaper than group fallback
     * (which re-resolves a member, rebuilds a provider, and may visibly switch
     * the model in the top bar).
     *
     * @param entryId the group entry whose instance owns the credentials
     * @param fromIndex index that just failed (rotation starts after it)
     * @param keyCount number of credentials on the instance (1 = no rotation)
     */
    fun rotateCredential(entryId: String, fromIndex: Int, keyCount: Int): Int? {
        if (keyCount <= 1) return null
        val now = clock()
        for (offset in 1 until keyCount) {
            val idx = (fromIndex + offset) % keyCount
            val route = routeId(entryId, idx)
            if (health[route]?.isUsable(now) ?: true) return idx
        }
        return null
    }

    // ── sticky credential memory (T-key-affinity) ──────────────────────────

    /**
     * [T-key-affinity] entryId → the credential index that last served it
     * successfully. Process-local here, persisted by the caller
     * ([com.rikkaminis.app.data.repository.ProviderRepository.loadStickyKeys])
     * so the memory survives an app restart — which is the entire point (the
     * user's ask is "whoever worked last time works next time", and a memory
     * that dies with the process answers no future request).
     */
    private val stickyKey = mutableMapOf<String, Int>()

    /**
     * Adopt a persisted memory map (app start). Values are taken as-is;
     * [preferredCredential] re-validates them against the live credential
     * count and health, so an index recorded before the user deleted keys
     * degrades to the default rather than addressing a nonexistent slot.
     */
    fun restoreStickyKeys(entries: Map<String, Int>) {
        stickyKey.clear()
        stickyKey.putAll(entries)
    }

    /** Snapshot for persistence. */
    fun stickyKeysSnapshot(): Map<String, Int> = stickyKey.toMap()

    /**
     * Record that credential [keyIndex] served [entryId] successfully, so the
     * next request on this entry starts there.
     *
     * Called on turn success AND on every rotation. Recording the rotation is
     * what makes the memory a *migrating* preference rather than a sticky trap
     * that has to be cleared by hand: without it, a key that died a week ago
     * would stay "preferred" forever, and every request would pay one doomed
     * attempt before rotation found the working one.
     */
    fun rememberCredential(entryId: String, keyIndex: Int, keyCount: Int) {
        // [T-key-affinity-single-key-noop] Single-credential instances record
        // NOTHING. Index 0 is the only choice they have, so a memory entry
        // carries zero information — and writing one would grow the persisted
        // map (and the prefs blob it serializes to) by every plain provider
        // the user ever touches, for no behavioural gain. The feature is
        // scoped to instances that actually hold more than one key.
        if (keyCount <= 1) return
        stickyKey[entryId] = keyIndex
    }

    /**
     * The credential index a NEW request on [entryId] should start with: the
     * remembered one when it still exists and is usable, else index 0.
     *
     * Falls back to any usable sibling when the remembered key is cooling —
     * the memory is a preference, not an obligation, and honouring it past a
     * 429 would just re-create the failure the rotation ladder is designed to
     * escape.
     *
     * There is deliberately no explicit "forget" path: every way a memory can
     * go stale — index out of range after a deletion, the key parked by an
     * exhaustion, a cooling window — is re-validated here at use time, so a
     * stale record degrades to the first usable slot instead of needing to be
     * erased by whoever changed the credential list.
     */
    fun preferredCredential(entryId: String, keyCount: Int): Int {
        if (keyCount <= 1) return 0
        val now = clock()
        stickyKey[entryId]?.let { remembered ->
            if (remembered in 0 until keyCount &&
                (health[routeId(entryId, remembered)]?.isUsable(now) ?: true)
            ) {
                return remembered
            }
        }
        // Memory absent or stale/unusable: take the first usable slot.
        for (idx in 0 until keyCount) {
            if (health[routeId(entryId, idx)]?.isUsable(now) ?: true) return idx
        }
        return 0
    }

    /**
     * [T-multi-api-key] Order the credentials of one entry for a fresh request:
     * usable ones first (round-robin from [fromIndex], so load spreads), then
     * demoted ones as last-resort probes.
     *
     * Demoted credentials are kept in the list rather than dropped: when every
     * key is cooling, attempting a still-cooling key is strictly better than
     * hard-failing the turn — the provider's own error is more informative
     * than "no credential available", and a half-open probe is how a cooling
     * key gets rediscovered after its window lapses.
     */
    fun credentialOrder(entryId: String, keyCount: Int, fromIndex: Int = 0): List<Int> {
        if (keyCount <= 0) return emptyList()
        if (keyCount == 1) return listOf(0)
        val now = clock()
        val rotated = (0 until keyCount).map { (fromIndex + it) % keyCount }
        val (usable, demoted) = rotated.partition { health[routeId(entryId, it)]?.isUsable(now) ?: true }
        return usable + demoted
    }

    /** True when ANY credential of [entryId] may be used now. */
    fun isEntryUsable(entryId: String, keyCount: Int): Boolean {
        if (keyCount <= 1) return isUsable(entryId)
        val now = clock()
        return (0 until keyCount).any { health[routeId(entryId, it)]?.isUsable(now) ?: true }
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

    /**
     * Test/diagnostics accessor: does a health record exist for [routeId]?
     * [isUsable] cannot distinguish "parked (cooling, not yet expired)" from
     * "evicted", and the capacity test needs that distinction.
     */
    internal fun healthContains(routeId: String): Boolean = health.containsKey(routeId)

    /** Test/diagnostics accessor: current health-map size. */
    internal fun healthSize(): Int = health.size

    /** Current health of a member (no entry = healthy). */
    fun healthOf(entryId: String): MemberHealth = health[entryId] ?: MemberHealth.Healthy
}
