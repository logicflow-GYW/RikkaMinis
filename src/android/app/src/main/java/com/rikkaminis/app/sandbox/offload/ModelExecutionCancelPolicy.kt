package com.rikkaminis.app.sandbox.offload

import com.rikkaminis.app.provider.CallDispatchGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * A single-owner lease for one provider execution slot.
 *
 * The worker has two admission exits: a request can observe [cancelRequested]
 * while queued, or it can acquire a permit and then observe cancellation before
 * entering the provider call. Keeping the permit state in this small object
 * makes the release rule explicit: a request that never acquired a permit can
 * never release one, and every acquired permit is released at most once.
 */
class ModelExecutionSlotLease(
    private val releasePermit: () -> Unit,
) {
    private val held = AtomicBoolean(false)

    val isHeld: Boolean
        get() = held.get()

    fun markAcquired() {
        check(held.compareAndSet(false, true)) { "execution slot acquired twice" }
    }

    fun releaseOnce() {
        if (held.compareAndSet(true, false)) releasePermit()
    }
}

/**
 * Wait for a worker execution slot without making cancellation wait forever.
 *
 * This is deliberately based on JDK [Semaphore] timed acquisition, matching
 * the production pool. The caller owns the returned lease and must release it
 * in its provider-region `finally`; `null` means no provider call may start.
 */
fun acquireModelExecutionSlot(
    slots: Semaphore,
    cancelRequested: () -> Boolean,
    pollMs: Long,
    onQueuePoll: () -> Unit = {},
): ModelExecutionSlotLease? {
    val lease = ModelExecutionSlotLease { slots.release() }
    while (true) {
        if (cancelRequested()) return null
        val acquired = try {
            slots.tryAcquire(pollMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        if (!acquired) {
            onQueuePoll()
            continue
        }

        lease.markAcquired()
        // Cancellation can win immediately after the semaphore handoff. Give
        // the permit back before reporting admission failure; otherwise a
        // queued stop permanently lowers the worker's capacity.
        if (cancelRequested()) {
            lease.releaseOnce()
            return null
        }
        return lease
    }
}

/**
 * Production queue-to-sender funnel. The callback cannot run until a real lease
 * exists and the post-handoff cancellation check has passed; every successful
 * acquisition is released exactly once even when the sender throws.
 */
fun <T> runWithModelExecutionSlot(
    slots: Semaphore,
    cancelRequested: () -> Boolean,
    pollMs: Long,
    onQueuePoll: () -> Unit = {},
    onQueueSettled: () -> Unit = {},
    onCancelled: () -> T,
    sender: () -> T,
): T {
    val lease = try {
        acquireModelExecutionSlot(slots, cancelRequested, pollMs, onQueuePoll)
    } finally {
        onQueueSettled()
    }
    if (lease == null) return onCancelled()
    try {
        if (cancelRequested()) return onCancelled()
        return sender()
    } finally {
        lease.releaseOnce()
    }
}

class ModelExecutionCancellationRace {
    private val state = java.util.concurrent.atomic.AtomicInteger(PENDING)

    fun tryCancel(cancelFile: File): Boolean {
        if (!cancelFile.isFile) return false
        state.compareAndSet(PENDING, CANCELLED)
        // A watcher may have won and cancelled the owner just before the
        // provider-entry check. Report the already-won decision as cancelled;
        // false here must never mean "go ahead and dispatch".
        return state.get() == CANCELLED
    }

    fun tryComplete(cancelFile: File): Boolean {
        if (cancelFile.isFile) {
            state.compareAndSet(PENDING, CANCELLED)
            return false
        }
        return state.compareAndSet(PENDING, COMPLETED)
    }

    val cancellationWon: Boolean
        get() = state.get() == CANCELLED

    val completionWon: Boolean
        get() = state.get() == COMPLETED

    private companion object {
        const val PENDING = 0
        const val CANCELLED = 1
        const val COMPLETED = 2
    }
}

/**
 * Connect the file-based worker cancel marker to a suspend provider call.
 *
 * The watcher is armed undispatched, before [block] is entered. Cancelling the
 * owner job is enough for providers whose blocking HTTP call already has a
 * coroutine cancellation bridge (OpenAI's stream-backed non-streaming path).
 * Providers that still call bare OkHttp `execute()` remain an explicit boundary:
 * coroutine cancellation alone cannot close a synchronous socket read; the
 * caller must not describe this helper as a universal HTTP cancellation API.
 *
 * Completion and cancellation compete on one per-request CAS before the result
 * publisher runs. A cancel marker observed first prevents publishing success;
 * once completion wins, a late marker cannot cancel this request or a sibling.
 */
suspend fun <T> withModelExecutionCancellation(
    cancelFile: File,
    pollMs: Long,
    cancellation: () -> CancellationException,
    race: ModelExecutionCancellationRace,
    block: suspend (ensureBeforeDispatch: suspend () -> Unit) -> T,
    publish: (T) -> Unit,
): T = coroutineScope {
    val owner = currentCoroutineContext()[Job]
        ?: error("worker provider call has no owning Job")
    val watcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        while (true) {
            if (race.tryCancel(cancelFile)) {
                owner.cancel(cancellation())
                return@launch
            }
            if (race.completionWon) return@launch
            delay(pollMs)
        }
    }
    try {
        suspend fun ensureBeforeDispatch() {
            currentCoroutineContext().ensureActive()
            if (race.tryCancel(cancelFile)) throw cancellation()
            currentCoroutineContext().ensureActive()
        }
        ensureBeforeDispatch()
        // Inherit through provider withContext/Call children, never through a
        // thread-local or cached provider field shared by concurrent runs.
        val value = withContext(CallDispatchGuard(::ensureBeforeDispatch)) {
            block(::ensureBeforeDispatch)
        }
        currentCoroutineContext().ensureActive()
        if (!race.tryComplete(cancelFile)) throw cancellation()
        publish(value)
        value
    } finally {
        watcher.cancel()
    }
}


/** This request observed its own explicit cancel marker. */
class ModelExecutionStopException : CancellationException("worker cancellation requested")

/** Only the run-local CAS winner authorizes a cancel ACK/envelope. */
fun isExplicitWorkerCancellation(race: ModelExecutionCancellationRace): Boolean =
    race.cancellationWon

fun nonStreamingFailureResult(
    cause: Throwable,
    race: ModelExecutionCancellationRace,
    imageEndpointResolved: String?,
): String {
    if (isExplicitWorkerCancellation(race)) throw ModelExecutionStopException()
    return JSONObject().apply {
        put("error", "model_use_failed")
        put("message", cause.message ?: "unknown")
        imageEndpointResolved?.let { put("image_endpoint_resolved", it) }
        put("exit_code", 1)
    }.toString()
}


/**
 * Wait for the abandoned caller's real worker ACK + committed result before
 * acknowledging consumption. A cancel ACK by itself never authorizes reclaim.
 */
suspend fun awaitExplicitAbandonmentHandshake(
    abandonAckPresent: () -> Boolean,
    resultPresent: () -> Boolean,
    writeClientAck: () -> Unit,
    timeoutMs: Long,
    pollMs: Long,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (abandonAckPresent() && resultPresent()) {
            writeClientAck()
            return true
        }
        delay(pollMs)
    }
    if (abandonAckPresent() && resultPresent()) {
        writeClientAck()
        return true
    }
    return false
}
