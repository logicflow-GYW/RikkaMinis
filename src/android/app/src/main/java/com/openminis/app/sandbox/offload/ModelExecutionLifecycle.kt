package com.openminis.app.sandbox.offload

/**
 * Pure state machine for the `:modelservice` worker lifecycle (TF-B: reliable
 * worker lifecycle, replacing the old "stopSelf == reclamation proof" model
 * and the earlier reverted global 30s idle-kill).
 *
 * The worker owns its own lifecycle. The main process may only REQUEST a
 * shutdown (via [ModelExecutionMailbox]); it can never kill the worker process
 * based on its own counters. The worker only leaves STOPPING (calls
 * Process.killProcess — self-reap) when it has confirmed quiescence via
 * [ModelExecutionQuiescenceInput]: active==0, queue==0, no un-acked response,
 * stream file flushed.
 *
 * Transition semantics (pure, JFVM-testable):
 *   - ANY non-dead state with in-flight work (active/queued/unacked/unflushed)
 *     → ACTIVE; a concurrent stream completing while another runs must NOT kill.
 *   - Fully quiescent ACTIVE/QUIESCE_PENDING/DRAINED → STOPPING immediately.
 *     There is deliberately NO idle window: the worker is a short-lived
 *     process — "run → die, native heap returned to OS" — so a later stream
 *     can never be severed by a stale idle timer.
 *   - STOPPING + still busy → stays ACTIVE (revived work re-arms; the kill
 *     decision is re-taken after every event, so a revived worker is never
 *     killed out from under its new request).
 *   - STOPPING + quiescent → DEAD (killProcess).
 *   - DEAD → terminal, never revived/killed.
 *
 * [shutdownRequested] is the main process's EXPLICIT drain request (memory
 * pressure). It never forces a kill; in the current implementation a fully
 * quiescent worker self-reaps to DEAD regardless (QUIESCE_PENDING/DRAINED are
 * declared for API stability but are not entered — a quiescent worker goes
 * straight ACTIVE/QUIESCE_PENDING/DRAINED → STOPPING → DEAD). The parameter
 * is plumbed through for observability and future drain semantics but does
 * not change the transition outcome today.
 *
 * All functions are pure — no Android dependencies, JVM testable.
 */
enum class ModelExecutionWorkerState {
    /** A request is being executed (stream in flight, or a run dispatched). */
    ACTIVE,

    /** Service asked to drain: stop accepting work, finish in-flight work. */
    QUIESCE_PENDING,

    /** No active work, no queued work, no un-acked response. */
    DRAINED,

    /** Shutdown requested AND quiescence confirmed — about to die. */
    STOPPING,

    /** Terminal: process has been killed / already recycled. */
    DEAD,
}

/** Pure, JVM-testable quiescence confirmation inputs (see [ModelExecutionLifecycle]). */
data class ModelExecutionQuiescenceInput(
    val activeRequests: Int,
    val queuedRequests: Int,
    val unackedResponses: Int,
    val streamFileFlushed: Boolean,
)

/**
 * Pure decision helper: given worker state + quiescence signals, decide the
 * next lifecycle state. Public top-level helpers (K2 rule: cross-file
 * internals can fail to resolve under CI).
 */
object ModelExecutionLifecycle {

    /** True when the worker can safely kill itself (all work drained). */
    fun isQuiescent(input: ModelExecutionQuiescenceInput): Boolean =
        input.activeRequests == 0 &&
            input.queuedRequests == 0 &&
            input.unackedResponses == 0 &&
            input.streamFileFlushed

    /**
     * Core transition [ModelExecutionWorkerState]. Pure — never touches Android.
     *
     * Semantics: ANY non-dead state with in-flight work stays ACTIVE; a fully
     * quiescent worker always proceeds toward STOPPING; STOPPING + quiescent
     * is the only path to DEAD; DEAD is terminal.
     */
    fun transition(
        current: ModelExecutionWorkerState,
        quiescence: ModelExecutionQuiescenceInput,
        shutdownRequested: Boolean,
    ): ModelExecutionWorkerState {
        if (current == ModelExecutionWorkerState.DEAD) return ModelExecutionWorkerState.DEAD
        // ANY state with un-finished work stays ACTIVE (except DEAD). This
        // includes streamFileFlushed=false — an un-flushed stream file means
        // the worker may still be appending chunks, so it must not die.
        if (!isQuiescent(quiescence)) {
            return ModelExecutionWorkerState.ACTIVE
        }
        return when (current) {
            ModelExecutionWorkerState.ACTIVE,
            ModelExecutionWorkerState.QUIESCE_PENDING,
            ModelExecutionWorkerState.DRAINED,
            -> ModelExecutionWorkerState.STOPPING
            ModelExecutionWorkerState.STOPPING -> ModelExecutionWorkerState.DEAD
            ModelExecutionWorkerState.DEAD -> ModelExecutionWorkerState.DEAD
        }
    }

    /**
     * Decide whether the worker should proceed to killProcess right now.
     * Only true from STOPPING while fully quiescent.
     */
    fun shouldKill(
        current: ModelExecutionWorkerState,
        quiescence: ModelExecutionQuiescenceInput,
    ): Boolean = current == ModelExecutionWorkerState.STOPPING && isQuiescent(quiescence)
}