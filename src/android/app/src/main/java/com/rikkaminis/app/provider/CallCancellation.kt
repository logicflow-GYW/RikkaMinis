package com.rikkaminis.app.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
 * [W1118 D6-F05 / R5-F04] The hook must hang off a CHILD job, not off our own
 * completion. `invokeOnCompletion` on the calling job only runs when that job
 * COMPLETES, and a suspend body cannot complete while it is parked inside the
 * blocking `execute()` — so the old hook could never fire during the read it
 * exists to interrupt (measured: `promptCancelCount=0`, see
 * `provider/voice/VoiceProvider.kt`'s [da1020 ISSUE-05] probe notes, which is
 * why that call site had added its own local watcher). A child coroutine that
 * does nothing but await cancellation completes promptly (it has no blocking
 * body), so its completion handler runs while the read is still in flight and
 * `cancel()` tears the socket down.
 *
 * The handler is also `dispose()`d: the returned [kotlinx.coroutines.DisposableHandle]
 * used to be dropped, so a long-lived caller job kept a strong reference to
 * the finished `Call` (and to its connection) until the job itself completed.
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
internal suspend fun Call.executeOrCancel(): Response = coroutineScope {
    // The scope coroutine is a child of the caller, so it dies with it — that
    // is the signal the watcher's completion handler reads below.
    val scopeJob = currentCoroutineContext().job
    // UNDISPATCHED: the watcher must be armed BEFORE execute() blocks, not on
    // the next dispatch.
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }
    val handle = watcher.invokeOnCompletion {
        // Only an EXTERNAL teardown may kill the call. Our own cleanup cancels
        // the watcher too, and cancelling a Call whose response the caller is
        // still reading would break a perfectly healthy body.
        if (!scopeJob.isActive) cancel()
    }
    try {
        execute()
    } catch (e: Throwable) {
        // Translate "the Call was cancelled because our job died" back into a
        // cancellation. `ensureActive()` throws CancellationException when the
        // current job is no longer active, and returns normally otherwise —
        // so a genuine network failure still surfaces as itself.
        currentCoroutineContext().ensureActive()
        throw e
    } finally {
        // Dispose BEFORE cancelling: with the handle attached, cancelling the
        // watcher would run the handler (harmless while we are still active,
        // but the dispose makes the intent explicit and drops the reference).
        handle.dispose()
        watcher.cancel()
    }
}
