package com.rikkaminis.app.share

import java.io.InputStream
import java.io.OutputStream
import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** At most two unresponsive providers can occupy copy workers process-wide. */
private val sharedCopyWorkers = Semaphore(2)

/** Owns the open request and every descriptor until a copy completes or expires. */
internal class SharedStreamCancellation(private val cancelOpen: () -> Unit = {}) {
    private val lock = Any()
    private val handles = mutableListOf<Closeable>()
    @Volatile var cancelled = false
        private set
    @Volatile private var worker: Thread? = null

    fun checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedIOException("share copy cancelled")
    }

    fun attach(thread: Thread) = synchronized(lock) {
        checkActive()
        worker = thread
    }

    fun <T : Closeable> own(handle: T): T = synchronized(lock) {
        if (cancelled) {
            runCatching { handle.close() }
            throw InterruptedIOException("share copy cancelled")
        }
        handles.add(handle)
        handle
    }

    // The local destination factory is serialized with cancellation: it must
    // never create a new partial file after the timeout owner has deleted it.
    fun destination(factory: () -> OutputStream): OutputStream = synchronized(lock) {
        checkActive()
        own(factory())
    }

    fun cancel() {
        val closing = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            handles.toList().also { handles.clear() }
        }
        runCatching { cancelOpen() }
        worker?.interrupt()
        closing.asReversed().forEach { runCatching { it.close() } }
    }

    fun close() {
        val closing = synchronized(lock) { handles.toList().also { handles.clear() } }
        closing.asReversed().forEach { runCatching { it.close() } }
    }
}

/** Bytes per read in [copySharedStream]. 8 KiB matches the pre-§1044-A08 inline
 *  copy and the rest of the repo's stream loops; large enough that a multi-MB
 *  share is a few hundred syscalls, small enough that an aborted copy never
 *  holds more than one buffer in memory. */
internal const val COPY_BUFFER_BYTES = 8192

/** [§1044-A08] Why a shared stream never reached the staging directory. */
internal enum class SharedStreamFailure {
    /** The resolver returned null — a remote provider refused or died. */
    OpenStreamNull,
    /** The per-file IO deadline elapsed before the source ran dry. */
    TimedOut,
    /** The single-file cap was crossed. */
    ExceededPerFile,
    /** The cumulative staging cap was crossed. */
    ExceededCumulative,
    /** Reading or writing threw; [SharedStreamCopy.Failed.detail] carries it. */
    ReadFailed,
}

/** [§1044-A08] Outcome of one staging copy. */
internal sealed class SharedStreamCopy {
    /** [bytes] were written to the destination and credited to the ledger. */
    data class Staged(val bytes: Long) : SharedStreamCopy()

    /** Nothing usable was staged; the destination was removed if it existed. */
    data class Failed(
        val reason: SharedStreamFailure,
        val detail: String? = null,
    ) : SharedStreamCopy()
}

/**
 * [§1044-A08] Stream-copy core behind [ShareReceiverActivity.copyUriToStaging],
 * extracted so the "a null stream stages NOTHING" decision is pinned by JVM
 * tests instead of living only inside an Activity that cannot run off-device.
 *
 * The defect this closes: the previous inline copy read
 * `contentResolver.openInputStream(uri)?.use { … }` and then returned the staged
 * filename unconditionally. `openInputStream` may return null WITHOUT throwing
 * (a remote provider that refuses the read, or whose process was reaped), so the
 * `use` block never ran, no file was created, and the caller still produced a
 * `PendingShare.Item` pointing at a nonexistent file — a ghost attachment that
 * reached the composer, skipped the "Could not read shared file" toast, and only
 * surfaced later as a silently dropped attachment.
 *
 * The ORDER is the whole point: the source is null-checked BEFORE [openDest] is
 * ever called, so a null stream cannot leave a 0-byte file behind.
 *
 * The destination is injected as a FACTORY, not as a path: the caller owns the
 * file (and therefore where it lives); this core only decides when to open it
 * and when to remove it. That keeps the write target exactly where it was
 * before the extraction — the app-private staging directory — and keeps this
 * function free of any caller-supplied path.
 *
 * Guards that MUST survive any refactor here (all preserved from the inline
 * version, and each one pinned by a test):
 *  * the source is null-checked BEFORE the destination is opened — opening the
 *    destination first is what turned "no stream" into a 0-byte ghost file;
 *  * [ioTimeoutMs] aborts a stalled provider and removes the partial file;
 *  * [maxFileBytes] / [maxTotalBytes] reject oversized shares and remove the
 *    partial file they already wrote;
 *  * the ledger is credited ONLY after the copy finished ([credit] with the
 *    exact byte count), so a rejected share never counts against the cap;
 *  * any exception removes the partial file and reports
 *    [SharedStreamFailure.ReadFailed].
 *
 * All effects are injected ([openStream] / [openDest] / [deleteDest] /
 * [stagedBytesNow] / [credit] / [now]) so the caller keeps ownership of the
 * ContentResolver and the process-lifetime ledger; this function touches no
 * Android API.
 */
internal fun copySharedStream(
    openStream: () -> InputStream?,
    openDest: () -> OutputStream,
    deleteDest: () -> Unit,
    stagedBytesNow: () -> Long,
    credit: (Long) -> Unit,
    maxFileBytes: Long,
    maxTotalBytes: Long,
    ioTimeoutMs: Long,
    now: () -> Long,
    cancellation: SharedStreamCancellation = SharedStreamCancellation(),
): SharedStreamCopy {
    if (!sharedCopyWorkers.tryAcquire()) {
        runCatching { deleteDest() }
        return SharedStreamCopy.Failed(SharedStreamFailure.ReadFailed, "share copy workers busy")
    }
    val task = FutureTask(Callable {
        try {
            copySharedStreamOwned(openStream, openDest, stagedBytesNow, maxFileBytes, maxTotalBytes,
                ioTimeoutMs, now, cancellation)
        } finally {
            cancellation.close()
        }
    })
    val worker = Thread({
        try { task.run() } finally { sharedCopyWorkers.release() }
    }, "ShareStreamCopy").apply { isDaemon = true }
    var success = false
    try {
        cancellation.attach(worker)
        worker.start()
        val outcome = task.get(ioTimeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        if (outcome is SharedStreamCopy.Staged) {
            cancellation.checkActive()
            synchronized(sharedCopyWorkers) {
                if (stagedBytesNow() + outcome.bytes > maxTotalBytes) {
                    return SharedStreamCopy.Failed(SharedStreamFailure.ExceededCumulative)
                }
                credit(outcome.bytes)
            }
            success = true
        }
        return outcome
    } catch (_: TimeoutException) {
        return SharedStreamCopy.Failed(SharedStreamFailure.TimedOut)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        return SharedStreamCopy.Failed(SharedStreamFailure.ReadFailed, "share copy interrupted")
    } catch (e: Exception) {
        return SharedStreamCopy.Failed(SharedStreamFailure.ReadFailed,
            (if (e is ExecutionException) e.cause else e)?.message)
    } finally {
        if (!success) {
            cancellation.cancel()
            task.cancel(true)
            runCatching { deleteDest() }
        }
        // Cooperative open cancellation/close reclaims the worker. A hostile
        // provider ignoring both cannot grow an unbounded pool (two permits).
        if (worker.state == Thread.State.NEW) {
            sharedCopyWorkers.release()
        } else if (!Thread.currentThread().isInterrupted) {
            try { worker.join(100L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }
}

private fun copySharedStreamOwned(
    openStream: () -> InputStream?,
    openDest: () -> OutputStream,
    stagedBytesNow: () -> Long,
    maxFileBytes: Long,
    maxTotalBytes: Long,
    ioTimeoutMs: Long,
    now: () -> Long,
    cancellation: SharedStreamCancellation,
): SharedStreamCopy {
    var total = 0L
    fun abort(reason: SharedStreamFailure, detail: String? = null): SharedStreamCopy {
        // The destination may already exist (partial write, or a name reuse) —
        // every failure path leaves the staging dir exactly as it found it.
        return SharedStreamCopy.Failed(reason, detail)
    }
    return try {
        val deadline = now() + ioTimeoutMs
        cancellation.checkActive()
        val input = openStream()
            ?: return abort(SharedStreamFailure.OpenStreamNull)
        cancellation.own(input).use { source ->
            cancellation.destination(openDest).use { output ->
                val buf = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    cancellation.checkActive()
                    if (now() > deadline) return abort(SharedStreamFailure.TimedOut)
                    val n = source.read(buf)
                    cancellation.checkActive()
                    if (now() > deadline) return abort(SharedStreamFailure.TimedOut)
                    if (n < 0) break
                    total += n
                    if (total > maxFileBytes) return abort(SharedStreamFailure.ExceededPerFile)
                    if (stagedBytesNow() + total > maxTotalBytes) {
                        return abort(SharedStreamFailure.ExceededCumulative)
                    }
                    output.write(buf, 0, n)
                }
            }
        }
        cancellation.checkActive()
        SharedStreamCopy.Staged(total)
    } catch (e: Exception) {
        abort(SharedStreamFailure.ReadFailed, e.message)
    }
}
