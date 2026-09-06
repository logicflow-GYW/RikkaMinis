package com.openminis.app.sandbox.offload

/**
 * TF-B: explicit terminal-exception taxonomy for the :modelservice streaming
 * protocol, so the caller can classify a remote failure WITHOUT re-sending
 * (duplicate answer) or faking success.
 *
 * Both carry [hadChunks]:
 *   - `hadChunks == false` (worker died / failed before emitting any chunk):
 *     the caller MAY fall back to another provider — nothing was delivered,
 *     re-running produces no duplicate.
 *   - `hadChunks == true` (worker died mid-stream or the stream errored after
 *     content): the caller MUST NOT re-send — the user already saw partial
 *     text; re-running would duplicate it. Surface it as an explicit stream
 *     error instead.
 */
sealed class ModelExecutionStreamException(
    message: String,
    cause: Throwable? = null,
    val hadChunks: Boolean,
) : RuntimeException(message, cause)

/**
 * TF-G: why a worker appeared to die, so diagnostics + the caller can decide
 * weight (retry-able 0-chunk vs fatal) with confidence. UNKNOWN is never
 * produced on the death-classification path — an ambiguous liveness probe
 * must not fabricate a reason.
 */
enum class WorkerDeathReason {
    /** No ready marker, no valid pid ref, past the startup window. */
    NEVER_STARTED,

    /** A pid ref exists but ready never landed and the pid is confirmed dead. */
    DIED_BEFORE_READY,

    /** Ready was seen, pid dead, but no chunk / result / terminal ever arrived. */
    DIED_AFTER_READY_NO_OUTPUT,

    /** Already emitted chunks, then the pid went dead mid-stream. */
    DIED_MID_STREAM,
}

/** Worker process died mid-flight (crash / kill / lost race). */
class ModelWorkerDiedException(
    hadChunks: Boolean,
    cause: Throwable? = null,
    val reason: WorkerDeathReason = if (hadChunks) WorkerDeathReason.DIED_MID_STREAM else WorkerDeathReason.DIED_AFTER_READY_NO_OUTPUT,
    val runId: String? = null,
    val phaseSummary: String? = null,
) : ModelExecutionStreamException(
    buildWorkerDiedMessage(hadChunks, reason, runId, phaseSummary),
    cause,
    hadChunks,
) {
    companion object {
        private fun buildWorkerDiedMessage(
            hadChunks: Boolean,
            reason: WorkerDeathReason,
            runId: String?,
            phase: String?,
        ): String {
            val base = if (hadChunks) "model worker died mid-stream" else "model worker died before any output"
            val sb = StringBuilder(base)
            sb.append(" [reason=").append(reason.name).append(']')
            runId?.let { sb.append(" runId=").append(it) }
            phase?.let { sb.append(" phase=").append(it) }
            return sb.toString()
        }
    }
}

/** The worker completed but wrote an explicit error line in the stream. */
class ModelStreamErrorException(
    message: String,
    hadChunks: Boolean,
    /**
     * Machine-readable failure kind from the worker's error line ("network",
     * "rate_limited", …), or null when the worker didn't classify it (legacy
     * error lines, [ModelExecutionService] untyped paths). Decoded by
     * [ChatStreamErrorPolicy] — the engine never string-matches messages.
     */
    val kind: String? = null,
) : ModelExecutionStreamException(message, null, hadChunks)