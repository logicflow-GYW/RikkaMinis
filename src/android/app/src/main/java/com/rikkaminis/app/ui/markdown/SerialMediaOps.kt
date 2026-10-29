package com.rikkaminis.app.ui.markdown

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * [r3-c / B09 F-12, P2] Serialises the two `MediaPlayer` lifecycle calls that
 * used to race: `setDataSource()` + blocking `prepare()` ran on
 * `Dispatchers.IO`, while `release()` ran on the main thread the instant the
 * composable left composition. Coroutine cancellation cannot interrupt a thread
 * already inside native `prepare()`, so scrolling past an audio block right as
 * it prepared released the same native handle from a second thread.
 *
 * Both calls now go through ONE single-thread executor: `runPreparing` executes
 * inline (the caller blocks on `Dispatchers.IO`, never on main), and `enqueue`
 * returns immediately but its task runs only after whatever is already queued —
 * so a release submitted during preparation is ordered AFTER `prepare()` returns
 * instead of racing it.
 *
 * Deliberately Android-free (`java.util.concurrent` only) so the ordering
 * contract is unit-testable on a plain JVM; `SerialMediaOpsTest` drives it.
 */
internal class SerialMediaOps(private val threadName: String = "minis-media-ops") {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply {
            // Never keep the process alive for a released player.
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    /**
     * Run [task] on the serial worker and wait for its result. Call from a
     * background dispatcher — this blocks the calling thread by design (the
     * blocking call it wraps must not run on main). An exception thrown by
     * [task] is rethrown as-is so the caller can decide what a failed
     * `prepare()` means.
     */
    fun runPreparing(task: () -> Boolean): Boolean {
        val future = executor.submit(Callable { task() })
        return try {
            future.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Queue [task] behind everything already submitted and return immediately.
     * Exceptions are swallowed: the only caller is `release()` on a player whose
     * state is already undefined (that is what the latch is for).
     */
    fun enqueue(task: () -> Unit) {
        try {
            executor.execute {
                try {
                    task()
                } catch (_: Throwable) {
                    // A released player throws IllegalStateException; nothing to do.
                }
            }
        } catch (_: RejectedExecutionException) {
            // Already shut down — the queued work would have been release() too.
        }
    }

    /** Stop accepting work; already-queued tasks still run. Idempotent. */
    fun shutdown() {
        executor.shutdown()
    }

    /** True once every queued task finished (only meaningful after [shutdown]). */
    fun awaitIdle(timeoutMs: Long): Boolean =
        try {
            executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
}
