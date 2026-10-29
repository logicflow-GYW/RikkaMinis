package com.rikkaminis.app.service

/**
 * [R4-U F-07] Pure core of the process-level trim ladder in `MinisApp`.
 *
 * Lives outside `MinisApp` (an Android `Application`) so the two rules that were
 * wrong can be pinned by real JVM tests instead of source-shape probes:
 *
 *  1. "work in flight" means ANY session streaming **or** a tool executing —
 *     `MinisApp` used to hand `AppMemoryGovernor` only `isToolRunning`, so with
 *     a second session streaming and no tool running the governor ran the
 *     synchronous GC it exists to avoid.
 *  2. one synchronous GC per trim callback, and none while work is in flight —
 *     `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` had two independent GC sites
 *     (the foreground-pressure block and the memory-gate block), so an idle
 *     process collected twice and a busy one still collected once, mid-stream.
 */
internal object TrimReclaimPolicy {

    /**
     * True while a stream is in flight OR a tool is executing. Deliberately
     * does NOT include "the user is present in a chat" (T166 presence): sitting
     * in a chat with nothing running is exactly when a synchronous GC is cheap
     * and safe.
     */
    fun hasActiveWork(activeSessionIds: Set<String>, toolRunning: Boolean): Boolean =
        activeSessionIds.isNotEmpty() || toolRunning

    /**
     * Whether THIS trim callback may run a synchronous `System.gc()`.
     *
     * [reclaimsShells] / [engagesGate] are the two `TrimPolicy` verdicts that
     * used to carry a GC each; their union keeps the older behaviour at
     * `TRIM_MEMORY_RUNNING_MODERATE` (no memory gate, still collects) while
     * collapsing the `TRIM_MEMORY_RUNNING_CRITICAL` double-collect into one.
     */
    fun shouldSyncGc(
        reclaimsShells: Boolean,
        engagesGate: Boolean,
        hasActiveWork: Boolean,
    ): Boolean = !hasActiveWork && (reclaimsShells || engagesGate)
}
