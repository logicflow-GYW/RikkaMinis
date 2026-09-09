package com.rikkaminis.app.data.routing

/**
 * Runtime health state for a single group member (model entry).
 *
 * Ephemeral — process-local only, never persisted. A member's health is
 * derived from request outcomes recorded via [GroupRouter.recordResult]; it
 * answers "when can this member be tried again", which is a *time* question,
 * not a configuration question. This is the dimension the old design tried to
 * express as a persisted `recovery` enum on ModelGroup (added as a DB column
 * in migration 4_5, then dropped in 5_6) — it belongs here, at runtime.
 *
 * [isUsable] is the single gate: selection and fallback both filter through it.
 */
sealed class MemberHealth {
    /** Usable normally. */
    object Healthy : MemberHealth()

    /**
     * Rate-limited (HTTP 429). Skip until [untilMs]. `untilMs` comes from the
     * Retry-After header when the provider sends one, otherwise the
     * cooldown default (60s).
     */
    data class Cooling(val untilMs: Long) : MemberHealth()

    /**
     * Circuit breaker: repeated 5xx opened the circuit. Skip until [untilMs]
     * (half-open after expiry — the member gets one probe, success closes,
     * failure re-opens with a fresh window).
     */
    data class OpenCircuit(val untilMs: Long, val failures: Int) : MemberHealth()

    /** Auth failure (401/403) / quota exhausted — never usable until user re-auths. */
    object Dead : MemberHealth()
}

/**
 * True when the member may be selected now. Expired [Cooling] / [OpenCircuit]
 * count as usable — recovery is automatic and needs no explicit promote step.
 */
fun MemberHealth.isUsable(nowMs: Long): Boolean = when (this) {
    MemberHealth.Healthy -> true
    is MemberHealth.Cooling -> nowMs >= untilMs
    is MemberHealth.OpenCircuit -> nowMs >= untilMs
    MemberHealth.Dead -> false
}
