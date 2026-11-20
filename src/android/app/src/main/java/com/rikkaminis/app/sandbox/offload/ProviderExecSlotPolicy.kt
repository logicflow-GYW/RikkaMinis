package com.rikkaminis.app.sandbox.offload

/**
 * Provider worker concurrency policy for `:modelservice` (2026-09-06,
 * feat/provider-exec-concurrency).
 *
 * ## Background
 * TF-F P0-C (2026-08) serialized ALL provider work behind a single global
 * `executionMutex` in the worker process. The rationale then: one process =
 * one unsafe lifecycle + one native-heap budget, and "until real evidence
 * warrants more, requests execute ONE AT A TIME". That evidence arrived:
 * users running multiple chat sessions observed every other session frozen
 * while one long-thinking stream held the mutex for minutes (up to the
 * 30-minute generation backstop) — session B's request thread was queued on
 * the mutex before HTTP, emitting zero bytes, with no UI signal at all.
 *
 * ## Change
 * The mutex becomes a bounded slot pool ([MAX_CONCURRENT_PROVIDER_RUNS]).
 * Six provider calls may stream concurrently by default; further requests
 * queue (the queue is OBSERVABLE — see [QueueStatus] chunks). Users may
 * configure 1..12 slots through AgentRuntimeLimitsPrefs.
 *
 * ## Memory trade-off
 * The original isolation argument still holds per-process: N concurrent
 * provider streams in one process multiply the native-heap peak (SSE decode
 * buffers, DirectByteBuffer, JSON) by N — the exact leak surface the
 * short-lived worker exists to contain. The default was raised from 2 to 6
 * at the user's request, not as a claim of measured memory safety. Raising
 * the slot count increases memory risk; the worker lifetime and queue
 * admission guards remain unchanged.
 *
 * ## Semantics kept identical
 * The slot pool guards exactly the region the old mutex guarded: the
 * provider network call (streamMessage / sendMessage / generateImage).
 * Everything outside (identity registration, heartbeat, ack barrier,
 * finishRequest) still runs per-request without holding a slot, so a
 * finishing request never blocks a starting one — unchanged from TF-I.
 */
object ProviderExecSlotPolicy {

    /**
     * Maximum provider calls executing concurrently in one `:modelservice`
     * process. 1 reproduces the old serialized behavior; 6 is the shipped
     * default (see class KDoc for the memory trade-off).
     * [feat/runtime-limits-panel] runtime truth is
     * AgentRuntimeLimitsPrefs.providerSlots() (default 6, user-tunable
     * 1..12). Keep this compatibility constant tied to that single source.
     */
    const val MAX_CONCURRENT_PROVIDER_RUNS: Int =
        com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.PROVIDER_SLOTS_DEFAULT

    /**
     * Upper bound on queued (slot-waiting) requests before a new dispatch
     * is rejected. Each queued request costs a Thread + a run dir + a
     * client poller; without a bound, N sessions all running long jobs
     * cascade-queue unboundedly. Rejected requests surface as a typed
     * transient error so the caller's retry path re-dispatches (likely onto
     * a fresher worker by then).
     * [feat/runtime-limits-panel] runtime truth is
     * AgentRuntimeLimitsPrefs.queueAdmission() (default 6 == this, user-
     * tunable 2..12).
     */
    const val MAX_QUEUED_REQUESTS: Int = 6

    /**
     * [feat/runtime-limits-panel] The live slot count for pool sizing. Read
     * when a `:modelservice` worker process sizes its semaphore (process
     * spawn), so a change applies to the NEXT worker — the current one
     * finishes in-flight runs on its old limits (safe handoff).
     */
    fun liveProviderSlots(): Int =
        com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.providerSlots()

    /**
     * [feat/runtime-limits-panel] The live queue-admission bound. Same
     * worker-process scoping as [liveProviderSlots].
     */
    fun liveQueueAdmission(): Int =
        com.rikkaminis.app.data.AgentRuntimeLimitsPrefs.queueAdmission()
}
