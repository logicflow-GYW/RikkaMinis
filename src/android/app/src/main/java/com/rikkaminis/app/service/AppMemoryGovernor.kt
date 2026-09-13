package com.rikkaminis.app.service

/**
 * [fix/memory-hardening-governor] App-SELF pressure governor.
 *
 * ## The gap this closes
 *
 * Every existing defence reacts to pressure somewhere else:
 *  - `MemoryPressureGate` / `ExecutionCoordinator` only judge a command
 *    **before** it starts (and the in-flight monitor only exists inside the
 *    shell path);
 *  - `Activity.onTrimMemory` only fires when the **SYSTEM** is short on memory.
 *
 * So an app-process balloon on an otherwise idle device had no reaction path at
 * all. Measured on 2026-09-13 16:54: entirely between shell commands, the app
 * went 232MB → 1762MB RSS (native heap 38MB → **1141MB**) in ~4s while the
 * device still had ~6GB free — nothing fired, nothing reclaimed, and only luck
 * (the allocation being freed again) kept it from the 5–6GB Scudo abort seen
 * earlier the same day.
 *
 * The 1s `MemorySpikeRecorder` sampler already looks at exactly the right
 * numbers every second; this object is the policy that turns a *sustained*
 * reading into action, using the same reclaim actions the trim-memory path
 * already performs.
 *
 * ## Policy
 *
 * - Trigger only on SUSTAINED elevation ([SUSTAINED_TICKS] consecutive ticks at
 *   or above [SUSTAINED_ANON_MB]) so a single transient read never sheds caches.
 * - Cooldown ([COOLDOWN_MS]) so the actions cannot run in a tight loop.
 * - While a tool/session is actively executing, drop the caches but skip the
 *   synchronous GC — the same trade-off (and the same reason) as
 *   `onTrimMemory`: a forced GC pauses every thread and can stall the very work
 *   driving the pressure. Cache drops stay safe: a running shell command does
 *   not read the markdown/KaTeX caches.
 *
 * Android-free by construction (the caller passes `nowMs`), so the whole policy
 * — counters, hysteresis, cooldown — is JVM-testable.
 */
object AppMemoryGovernor {

    /** Sustained anon at/above this acts. Tied to the gate's soft line
     *  (`MemoryPressureGate.ELEVATED_ANON_MB`, 450MB of RssAnon) so the two
     *  layers share one ladder: we shed caches *before* the gate's hard line
     *  (1200MB anon) starts rejecting tool calls. */
    val SUSTAINED_ANON_MB: Long get() = MemoryPressureGate.ELEVATED_ANON_MB

    /** Consecutive 1s ticks at/above the threshold before acting. */
    const val SUSTAINED_TICKS = 5

    /** Minimum spacing between two reclaim actions. */
    const val COOLDOWN_MS = 30_000L

    enum class Action {
        NONE,

        /** Drop rebuildable caches + recycle idle shells (no GC). */
        DROP_CACHES,

        /** [DROP_CACHES] plus a synchronous GC (no tool/session in flight). */
        DROP_CACHES_AND_GC,
    }

    /** Drop rebuildable caches (markdown parse caches, KaTeX bitmaps, idle
     *  WebView tabs) and recycle idle shells. Wired in `MinisApp`. */
    @Volatile
    var dropCachesHook: () -> Unit = {}

    /** Synchronous GC. Wired in `MinisApp` (never called while work is in flight). */
    @Volatile
    var gcHook: () -> Unit = {}

    /** Observability: (action taken, RSS at the time). Wired in `MinisApp`. */
    @Volatile
    var observer: (Action, Long) -> Unit = { _, _ -> }

    private var consecutiveHigh: Int = 0
    private var lastReclaimAtMs: Long = 0L

    /**
     * One sampler tick. Cheap and side-effect-free while healthy; acts only on
     * sustained elevation past the cooldown. [nowMs] is a monotonic clock
     * (`SystemClock.elapsedRealtime()` in production) — tests pass their own.
     */
    fun tick(anonMb: Long, nowMs: Long, toolRunning: Boolean) {
        val decision = internalGovernorTick(
            anonMb = anonMb,
            consecutiveHigh = consecutiveHigh,
            lastReclaimAtMs = lastReclaimAtMs,
            nowMs = nowMs,
            toolRunning = toolRunning,
        )
        consecutiveHigh = decision.consecutiveHigh
        val action = decision.action
        if (action == Action.NONE) return
        lastReclaimAtMs = nowMs
        observer(action, anonMb)
        runCatching { dropCachesHook() }
        if (action == Action.DROP_CACHES_AND_GC) {
            runCatching { gcHook() }
        }
    }

    /** Test/diagnostics hook: reset the counters (production never needs this). */
    internal fun resetForTest() {
        consecutiveHigh = 0
        lastReclaimAtMs = 0L
    }

    /** Current consecutive-high count (observability / tests). */
    internal fun consecutiveHighForTest(): Int = consecutiveHigh
}

/**
 * Pure governor decision. Returns the next counter value and the action to run.
 * Kept top-level (not a method) so tests can exercise the policy without the
 * singleton's state or hooks.
 */
internal fun internalGovernorTick(
    anonMb: Long,
    consecutiveHigh: Int,
    lastReclaimAtMs: Long,
    nowMs: Long,
    toolRunning: Boolean,
    thresholdMb: Long = AppMemoryGovernor.SUSTAINED_ANON_MB,
    ticks: Int = AppMemoryGovernor.SUSTAINED_TICKS,
    cooldownMs: Long = AppMemoryGovernor.COOLDOWN_MS,
): GovernorDecision {
    if (anonMb < thresholdMb) return GovernorDecision(0, AppMemoryGovernor.Action.NONE)
    val next = consecutiveHigh + 1
    if (next < ticks) return GovernorDecision(next, AppMemoryGovernor.Action.NONE)
    // Sustained long enough — but do not re-run the reclaim in a tight loop.
    // `lastReclaimAtMs <= 0` means "never acted", which must NOT be read as
    // "acted at time 0": `nowMs` is a since-boot clock, so a device booted less
    // than the cooldown ago would otherwise have its first reclaim delayed.
    val cooling = lastReclaimAtMs > 0L && (nowMs - lastReclaimAtMs) < cooldownMs
    if (cooling) {
        return GovernorDecision(next, AppMemoryGovernor.Action.NONE)
    }
    // Acted: the counter restarts so the next action needs another full window.
    val action = if (toolRunning) {
        AppMemoryGovernor.Action.DROP_CACHES
    } else {
        AppMemoryGovernor.Action.DROP_CACHES_AND_GC
    }
    return GovernorDecision(0, action)
}

internal data class GovernorDecision(
    val consecutiveHigh: Int,
    val action: AppMemoryGovernor.Action,
)
