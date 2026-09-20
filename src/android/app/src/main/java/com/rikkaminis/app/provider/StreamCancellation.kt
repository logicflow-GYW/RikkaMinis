package com.rikkaminis.app.provider

import kotlinx.coroutines.CancellationException

/**
 * [F-177] Provider-side classification of a stream failure.
 *
 * ## The bug this fixes
 *
 * A provider's stream is a `callbackFlow`: the SSE loop runs on the COLLECTOR's
 * dispatcher, so when the worker's collector is torn down mid-stream the
 * producer's `catch (e: Exception)` sees a kotlinx-generated wrapper —
 * `CancellationException: Channel was consumed, consumer had failed`, with the
 * consumer's real exception in `cause` (measured: `f177/exp_f177i`).
 * Production logged it as a hard error for a *user-initiated* cancel:
 *
 * ```
 * [ERROR] [OpenAIProvider] [T321] stream parse exception:
 *   CancellationException: Channel was consumed, consumer had failed
 * ```
 *
 * The worker had thrown `ModelExecutionCancelledException` from its collector
 * when the main process wrote `run-<uuid>/cancel` — i.e. the user tapped stop.
 * The provider then ran `cancel("Stream error", mapError(e))`, which `mapError`
 * resolved to `LLMError.Unknown`: a cancellation was accounted as an unknown
 * provider error, and the log line named the wrapper instead of the cause.
 *
 * ## The rule
 *
 * A `CancellationException` **with no cause** is the coroutine machinery's own
 * signal that this stream was torn down — the enclosing job died / the consumer
 * went away. It is not a stream-parse failure and must not be logged or
 * classified as one.
 *
 * A `CancellationException` **with a cause** is different: kotlinx uses it to
 * carry a *real* downstream failure (`exp_f177i`: a consumer that died of
 * `IOException` also produces `Channel was consumed, consumer had failed`, but
 * with the IOException in `cause`). That must stay an error — hence the
 * `cause == null` guard rather than a bare `is CancellationException` check.
 *
 * This predicate can only ever reclassify the no-cause case, so a genuine
 * stream error cannot be swallowed by it (locked by
 * `ProviderStreamCancellationTest`).
 *
 * Returns the throwable itself when it qualifies, so callers can hand it
 * straight to `ProducerScope.cancel(cause)` without a cast.
 */
internal fun Throwable.asConsumerSideCancellation(): CancellationException? =
    if (this is CancellationException && cause == null) this else null

/**
 * [F-177] Render a throwable's cause chain for diagnostics.
 *
 * The pre-fix log printed only `javaClass.simpleName: message` of the outermost
 * throwable, so the actual failure sat one level down and was never shown —
 * `e.cause` appeared 0 times in `OpenAIProvider.kt` while the worker was logging
 * the real reason (`streamMessage threw: cancelled`) in a different process.
 * Reading a single line could not answer "why did this stream die".
 *
 * Bounded on purpose: the chain of a stream failure is short, and an unbounded
 * walk risks a self-referential chain turning a log line into a hang.
 */
internal fun Throwable.causeChainSummary(maxDepth: Int = 4): String {
    val sb = StringBuilder()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < maxDepth) {
        if (depth > 0) sb.append(" <- ")
        sb.append(current.javaClass.simpleName).append(": ").append(current.message ?: "(no message)")
        current = current.cause
        depth++
    }
    return sb.toString()
}
