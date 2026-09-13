package com.rikkaminis.app.sandbox.offload

/**
 * [audit-cs0913] Back-pressure observability for the native-offload worker pool.
 *
 * The pool is sized from `ConcurrencyPrefs.maxConcurrentSessions()` (default 2)
 * and each worker blocks on `Semaphore.acquire()` with **no timeout**, so a
 * handler that runs long — a slow image generation can hold its slot for up to
 * the provider read timeout — leaves later requests waiting with no signal to
 * the caller. Downstream that shows up as an agent tool call with no output,
 * which the shell-level stall watchdog then kills after 180s, so the failure is
 * indistinguishable from a broken tool.
 *
 * This policy only *decides whether a measurement is worth reporting*. It
 * deliberately does NOT enforce a timeout: the measured data comes first, the
 * guardrail (and its threshold) is a separate decision — same path the memory
 * pressure gate took (probe → data → threshold).
 */
internal object OffloadQueuePolicy {
    /** Waiting this long for a worker slot means the pool is saturated. */
    const val QUEUE_WARN_MS = 2_000L

    /** A single handler occupying a slot this long is a long-running call. */
    const val EXEC_WARN_MS = 60_000L

    fun shouldReportQueue(queueMs: Long): Boolean = queueMs >= QUEUE_WARN_MS

    fun shouldReportExec(execMs: Long): Boolean = execMs >= EXEC_WARN_MS

    fun shouldReport(queueMs: Long, execMs: Long): Boolean =
        shouldReportQueue(queueMs) || shouldReportExec(execMs)

    /** Log/probe shape: which handler, how long queued, how long executing. */
    fun describe(handler: String, queueMs: Long, execMs: Long): String =
        "handler=$handler queue=${queueMs}ms exec=${execMs}ms"
}
