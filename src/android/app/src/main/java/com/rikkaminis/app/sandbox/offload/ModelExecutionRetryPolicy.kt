package com.rikkaminis.app.sandbox.offload

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * [meta1028-D2 F-01] Retry policy for a NON-STREAMING dispatch into
 * `:modelservice`.
 *
 * ## The defect this exists for
 * `:modelservice` stamps two typed, auto-retryable rejections, and BOTH are
 * written as an ordinary `result.json` before the worker exits:
 *
 *  - `stale_key_cache` — the worker's cached `provider_secrets` map predates a
 *    key the main process just saved (admission gate at
 *    ModelExecutionService.onStartCommand, and the pre-key check inside
 *    executeRun). The process poisons itself and self-reaps after the
 *    concurrent runs drain, so a FRESH process reads the new key.
 *  - `worker_queue_full` — the admission bound (`liveQueueAdmission()`) is
 *    reached; nothing ran, nothing was billed.
 *
 * The streaming client consumes exactly this pair as
 * `ChatStreamErrorPolicyKind.KIND_TRANSIENT` and auto-retries
 * (ui/chat/ChatStreamErrorPolicy). The non-streaming dispatcher did NOT: it
 * retried only on a CONFIRMED worker death (`result == null && workerDied`),
 * and a written `result.json` short-circuited the decision — so after the
 * `feat/provider-exec-concurrency` handoff (worker writes a typed result
 * instead of dying silently) the retry branch became unreachable for exactly
 * the case it was written for. Consequence: `minis-model-use run`, title
 * generation, compaction and QuickTest hard-failed after an API-key change
 * until the stale worker happened to die.
 *
 * ## Why this set may be retried at all (the invariant, not a heuristic)
 * Every code in [RETRYABLE_CODES] is written BEFORE the worker touches a
 * provider: no HTTP request, no generation, no billing. That is what makes
 * re-dispatching safe. A TIMEOUT is deliberately NOT in this set — at timeout
 * the worker may still be mid-generation, so a re-dispatch would start a
 * SECOND provider call (duplicate billing / duplicate image generation); see
 * the [ModelExecutionDispatcher.dispatch] KDoc, which has carried that
 * reasoning since `fix/audit-s3l2`. Extending this set to "any error" would
 * reintroduce exactly that bug.
 *
 * ## Attempt budget
 * - typed retryable rejection: up to [MAX_ATTEMPTS] dispatches, [RETRY_DELAY_MS]
 *   apart. The delay is not padding: a poisoned worker only dies on its
 *   watchdog tick (500ms poll, `activeRequests == 0`), so an IMMEDIATE
 *   re-dispatch is routinely rejected by the very process that is about to
 *   die. Two retries cover the tick boundary plus one lost race.
 * - confirmed worker death (no result at all): unchanged historical shape —
 *   ONE immediate re-dispatch ([WORKER_DEATH_ATTEMPTS] attempts, zero delay).
 * - anything else (timeout, IO, `missing_api_key`, `model_use_failed`,
 *   unparseable result): give up and surface what the worker said.
 */
internal object ModelExecutionRetryPolicy {

    /**
     * Wire literal for the queue-admission rejection. Single source: the
     * worker writes it (via this constant), the policy reads it.
     */
    const val WORKER_QUEUE_FULL = "worker_queue_full"

    /**
     * Codes that are safe to re-dispatch because the worker rejected the
     * request before any provider work. See the class KDoc invariant.
     */
    private val RETRYABLE_CODES = setOf(WorkerKeyFreshness.STALE_KEY_CACHE, WORKER_QUEUE_FULL)

    /** Total dispatches allowed for a typed retryable rejection (initial + 2). */
    const val MAX_ATTEMPTS = 3

    /** Wait before re-dispatching a typed rejection — one poison-watchdog tick. */
    const val RETRY_DELAY_MS = 500L

    /** Total dispatches allowed after a confirmed worker death (initial + 1). */
    const val WORKER_DEATH_ATTEMPTS = 2

    /** What the dispatcher should do after one attempt. */
    sealed class Decision {
        /** Surface this attempt's result (or null) to the caller. */
        object GiveUp : Decision()

        /** Wait [delayMs] (0 = immediately) and dispatch again. */
        data class RetryAfter(val delayMs: Long) : Decision()
    }

    /**
     * The `error` field of a worker result envelope, or null when the envelope
     * has none (success path / `null` result / unparseable text). Unparseable
     * input is NOT retryable: we cannot prove nothing was billed.
     */
    fun errorCodeOf(resultJson: String?): String? {
        if (resultJson.isNullOrEmpty()) return null
        val obj = try { JSONObject(resultJson) } catch (_: Throwable) { return null }
        return obj.optString("error", "").ifEmpty { null }
    }

    fun isRetryableCode(errorCode: String?): Boolean =
        errorCode != null && errorCode in RETRYABLE_CODES

    /**
     * @param attemptsMade dispatches already performed for this request (>= 1),
     *   counted across BOTH budgets — it is the typed-rejection budget's clock.
     * @param errorCode [errorCodeOf] of the last attempt's result.
     * @param workerDied the last attempt ended in a CONFIRMED worker death with
     *   no result at all ([ModelExecutionDispatcher.dispatchOnce] evidence).
     * @param workerDeathAttempts confirmed worker deaths among the dispatches
     *   made so far, INCLUDING the one being decided. Kept separate from
     *   [attemptsMade] on purpose (R8-B B-F01): the two budgets answer
     *   different questions ("may I re-dispatch a pre-provider rejection?" vs
     *   "may I re-dispatch onto a fresh process?") and sharing one counter made
     *   the death budget expire early.
     *
     *   The field failure the split fixes: user replaces a quota-exhausted key →
     *   dispatch #1 writes `stale_key_cache` (typed rejection) → dispatch #2 hits
     *   the poisoned worker while it self-reaps, so it dies with no result. With a
     *   shared counter that second attempt reads `2 < 2 == false` and gives up —
     *   yet dispatch #3 is exactly the one that would have landed on the fresh
     *   process and read the new key. The typed rejection is not a death, so it
     *   must not spend the death budget.
     *
     *   A required parameter, not a defaulted one: a default would let a caller
     *   silently keep the old shared-counter behaviour, which is the bug.
     */
    fun decide(
        attemptsMade: Int,
        errorCode: String?,
        workerDied: Boolean,
        workerDeathAttempts: Int,
    ): Decision {
        if (workerDied) {
            return if (workerDeathAttempts < WORKER_DEATH_ATTEMPTS) Decision.RetryAfter(0L)
            else Decision.GiveUp
        }
        if (!isRetryableCode(errorCode)) return Decision.GiveUp
        return if (attemptsMade < MAX_ATTEMPTS) Decision.RetryAfter(RETRY_DELAY_MS)
        else Decision.GiveUp
    }
}

/** One dispatch attempt's outcome, as the retry loop sees it. */
internal data class ModelDispatchAttempt(val resultJson: String?, val workerDied: Boolean)

/**
 * [meta1028-D2 F-01] The retry loop, factored out of
 * [ModelExecutionDispatcher.dispatch] so the decision table, the attempt
 * count and the wait between attempts are JVM-testable without a Context or a
 * real worker.
 *
 * Termination is structural: [ModelExecutionRetryPolicy.decide] can only
 * answer `RetryAfter` while the counter that guards the answering branch is
 * still under its own cap (`attemptsMade` for typed rejections,
 * `workerDeathAttempts` for confirmed deaths), and each loop iteration
 * advances the one that applies.
 *
 * @param attempt performs one dispatch (real: `dispatchOnce` + Context).
 * @param waitBeforeRetry injected so tests record the delay instead of
 *   sleeping; production uses `kotlinx.coroutines.delay`.
 * @param onRetry called before each retry with the attempt number, the code
 *   that triggered it and the delay — the caller logs it (this file stays
 *   Android-free so it compiles in the JVM test source set).
 */
internal suspend fun dispatchWithRetry(
    attempt: suspend () -> ModelDispatchAttempt,
    waitBeforeRetry: suspend (Long) -> Unit = { if (it > 0L) delay(it) },
    onRetry: (attemptsMade: Int, errorCode: String?, delayMs: Long) -> Unit = { _, _, _ -> },
): ModelDispatchAttempt {
    var attemptsMade = 1
    // Confirmed worker deaths so far (this first attempt included when it died).
    // Separate from [attemptsMade]: see [ModelExecutionRetryPolicy.decide].
    var workerDeaths = 0
    var outcome = attempt()
    if (outcome.workerDied) workerDeaths++
    while (true) {
        val code = ModelExecutionRetryPolicy.errorCodeOf(outcome.resultJson)
        when (
            val d = ModelExecutionRetryPolicy.decide(
                attemptsMade = attemptsMade,
                errorCode = code,
                workerDied = outcome.workerDied,
                workerDeathAttempts = workerDeaths,
            )
        ) {
            is ModelExecutionRetryPolicy.Decision.GiveUp -> return outcome
            is ModelExecutionRetryPolicy.Decision.RetryAfter -> {
                onRetry(attemptsMade, code, d.delayMs)
                waitBeforeRetry(d.delayMs)
                outcome = attempt()
                attemptsMade++
                // Count the death of the attempt we just made — otherwise the
                // death budget would only ever be spent once.
                if (outcome.workerDied) workerDeaths++
            }
        }
    }
}
