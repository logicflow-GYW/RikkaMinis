package com.rikkaminis.app.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Response

/**
 * [FIX-1 / F-209] Blocking `Call.execute()` with a coroutine-cancellation hook.
 *
 * `execute()` is synchronous and has no cancellation hook, so a `fetchModels`
 * whose coroutine is cancelled (user leaves the screen, switches provider,
 * refreshes in a loop) used to run to completion — including `cache.save` —
 * while holding an OkHttp dispatcher thread.
 *
 * `fix/audit0917-b8` added the hook to `OpenAIModelsApi` only, while the other
 * five model-list fetchers kept the bare `execute()`. Rather than copy the
 * five-line idiom four more times (and leave a sixth site free to be added
 * without it), the logic lives here once.
 *
 * Cancellation semantics are preserved deliberately:
 *  - OkHttp reports a cancelled Call as `IOException("Canceled")`, NOT as
 *    `CancellationException`, so callers that catch broad `Exception` would
 *    otherwise swallow a cancellation and return a fallback list. That would
 *    leave the caller running after it was told to stop — so this function
 *    rethrows the cancellation as a real `CancellationException` when *our*
 *    job is the one that died, and lets an ordinary IO failure through
 *    untouched when it is not.
 *
 * Usage:
 * ```
 * val response = try {
 *     client.newCall(request).executeOrCancel()
 * } catch (e: CancellationException) {
 *     throw e              // never fall back on a cancellation
 * } catch (e: Exception) {
 *     return fallback
 * }
 * ```
 */
internal suspend fun Call.executeOrCancel(): Response {
    currentCoroutineContext().job.invokeOnCompletion { cause ->
        if (cause is CancellationException) cancel()
    }
    return try {
        execute()
    } catch (e: Throwable) {
        // Translate "the Call was cancelled because our job died" back into a
        // cancellation. `ensureActive()` throws CancellationException when the
        // current job is no longer active, and returns normally otherwise —
        // so a genuine network failure still surfaces as itself.
        currentCoroutineContext().ensureActive()
        throw e
    }
}
