package com.openminis.app.sandbox.offload

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
 * Two sessions may now stream concurrently; a third queues (and the queue
 * is now OBSERVABLE — see [QueueStatus] chunks).
 *
 * ## Why 2 and not more
 * The original isolation argument still holds per-process: N concurrent
 * provider streams in one process multiply the native-heap peak (SSE decode
 * buffers, DirectByteBuffer, JSON) by N — the exact leak surface the
 * short-lived worker exists to contain. 2 slots doubles throughput (the
 * "two sessions" case) while keeping the native budget within the range
 * the self-reap design already tolerates (a stream + a title/compact
 * request already coexisted legally during the ack-barrier window).
 * Raising this further should be driven by memory measurements, not
 * optimism.
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
     * process. 1 reproduces the old serialized behavior; 2 is the shipped
     * default (see class KDoc for the sizing rationale).
     */
    const val MAX_CONCURRENT_PROVIDER_RUNS: Int = 2

    /**
     * Upper bound on queued (slot-waiting) requests before a new dispatch
     * is rejected. Each queued request costs a Thread + a run dir + a
     * client poller; without a bound, N sessions all running long jobs
     * cascade-queue unboundedly. Rejected requests surface as a typed
     * transient error so the caller's retry path re-dispatches (likely onto
     * a fresher worker by then).
     */
    const val MAX_QUEUED_REQUESTS: Int = 6
}
