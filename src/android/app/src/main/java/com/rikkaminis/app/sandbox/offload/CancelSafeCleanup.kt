package com.rikkaminis.app.sandbox.offload

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * [WAVE-1118 D3-F02 / D3-F03] Two-step run-dir cleanup that MUST complete even
 * when the calling coroutine is being cancelled: put the wire signal on disk
 * ([writeCancel]) and then run the bounded wait-for-worker step ([cleanup]).
 *
 * WHY this exists (measured, not inferred — `shared/work/incr1118/t2probe/`):
 * a `finally` block that calls a *suspending* cleanup is not a place where
 * cleanup "always runs". When the job is cancelled, the first suspension point
 * inside that cleanup (`delay` in the terminal/worker-exit poll loop) throws
 * `CancellationException` immediately, the enclosing `catch (_: Exception) {}`
 * swallows it, and everything after it is skipped. Both run-dir protocols were
 * one `delay` away from leaking on exactly the path they were written for:
 *
 *  - streaming ([ChatStreamOffloadHandler]): the `cancel` marker is written (it
 *    is not suspending), then `awaitTerminalAndWorkerExitThenDelete` is skipped
 *    → the worker never sees `client.ack` and holds its native heap for the
 *    full ack timeout + drain grace (~45 s), leaving the `run-*` dir behind.
 *  - non-streaming ([ModelExecutionDispatcher]): the poll loop is cancelled
 *    before the post-poll block, so not even `cancel` reaches the worker → it
 *    keeps running the request (and billing it), and the dir is an orphan the
 *    reaper cannot classify (no `terminal.json`, no `cancel`).
 *
 * `withContext(NonCancellable)` replaces the job for the duration of the block,
 * so `delay` here is not cancellable and the poll loops run to their own
 * bounded deadlines. Cancellation is not lost either: the caller's job is
 * already cancelled by definition, and the dispatcher resumes unwinding as soon
 * as this returns.
 *
 * Bounded by construction: both call sites pass budgeted waits
 * (`CANCEL_ACK_TIMEOUT_MS` + `WORKER_EXIT_WAIT_MS` ≈ 11 s worst case on the
 * streaming side, `TERMINAL_WAIT_MS` + `REAP_WAIT_MS` ≈ 8 s on the dispatcher
 * side), and the common case is milliseconds because a live worker reacts to
 * `cancel` immediately. Both run on `Dispatchers.IO` (the stream is
 * `flowOn(IO)`), so this never occupies the main thread.
 *
 * Returns true when [cleanup] completed without throwing, which is the answer
 * the call sites log — a cleanup that is *skipped* and one that *fails* are
 * different defects and must not look alike in the log.
 */
internal suspend fun internalRunCancelCleanup(
    writeCancel: () -> Unit,
    cleanup: suspend () -> Unit,
): Boolean = withContext(NonCancellable) {
    runCatching { writeCancel() }
    runCatching { cleanup() }.isSuccess
}
