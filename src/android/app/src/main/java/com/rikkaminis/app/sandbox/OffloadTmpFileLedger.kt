package com.rikkaminis.app.sandbox

import java.io.File

/**
 * [audit-RC7] Bounded ledger for native-offload response tmpfiles.
 *
 * The proot `native_offload` extension rewrites the guest's execve into
 * `/bin/cat <tmpfile>` AFTER our reply is delivered, so a tmpfile stays
 * "not yet consumed" for a short window after the reply. The old
 * single-slot `lastTmpHost` design assumed strictly serial requests
 * ("the next request implies the previous file was already cat'd"), which
 * breaks once two sessions offload concurrently (MAX_CONCURRENT_SHELLS=2):
 * the next request's reclaim could delete a file whose guest had not cat'd
 * it yet — losing that request's output — or, racing on the unsynchronized
 * slot, leak a file forever.
 *
 * This ledger keeps the most recent [capacity] tmpfiles and deletes the
 * oldest when it is evicted. With capacity comfortably above the number of
 * concurrently in-flight requests, an evicted file has survived several
 * full request cycles, making "guest still hasn't cat'd" practically
 * impossible (cat of a tmpfs file is synchronous and fast). All operations
 * are serialized under one lock, so exactly one thread deletes any given
 * file — no leak, no double-delete, no use-after-delete of the slot.
 *
 * Registration is SEPARATE from reclaim ([register] / [reclaim]) rather than
 * one `rotate` step, because the caller has to be able to account for a file
 * whose WRITE then fails — see [writeResponseFile]. With a single rotate
 * entry point the only way to cover that path is to rotate on the exception
 * too, and rotating twice deletes the file the previous response is still
 * carrying ([meta1028-B04 F-11]).
 *
 * Pure JVM (only java.io.File + injectable write/delete functions) so the
 * concurrency invariants are unit-testable without Android.
 */
internal class OffloadTmpFileLedger(
    capacity: Int = DEFAULT_CAPACITY,
    private val deleteFn: (File) -> Boolean = { it.delete() },
) {
    init {
        require(capacity >= 1) { "capacity must be >= 1, was $capacity" }
    }

    private val lock = Any()
    private var capacity: Int = capacity
    private val ring = ArrayDeque<File>(capacity)

    /** Number of tmpfiles currently retained (for tests/observability). */
    val size: Int get() = synchronized(lock) { ring.size }

    /** The most recently registered file, if any. */
    val newest: File? get() = synchronized(lock) { ring.lastOrNull() }

    /**
     * Track [file] as just-written. When the ring exceeds [capacity], the
     * OLDEST entry — which has been through at least [capacity] full
     * request/reply/cat cycles — is dropped from the ledger and returned for
     * the CALLER to delete (deleting inside the lock would let a slow or
     * throwing [deleteFn] hold the lock every other offload thread needs).
     *
     * Returns the evicted file, or null when nothing overflowed.
     */
    fun register(file: File): File? = synchronized(lock) {
        ring.addLast(file)
        if (ring.size > capacity) ring.removeFirst() else null
    }

    /**
     * Forget [file]. Returns true when it was still tracked — i.e. when the
     * CALLER owns the deletion — and false when another thread already
     * evicted it, so a file is never deleted twice.
     */
    fun discard(file: File): Boolean = synchronized(lock) { ring.remove(file) }

    /**
     * Delete [file] (if it is still tracked) and forget it. The failure path
     * of [writeResponseFile]: the partial file is removed and the ledger no
     * longer counts it, so a failed response leaves nothing behind in the
     * rootfs /tmp that is RAM.
     */
    fun reclaim(file: File) {
        if (discard(file)) runCatching { deleteFn(file) }
    }

    /** [register] + delete the overflow, the pre-[meta1028-B04 F-11] shape. */
    fun rotate(file: File) {
        register(file)?.let { old -> runCatching { deleteFn(old) } }
    }

    /**
     * Delete every tracked file and empty the ledger. Safe at server
     * shutdown — no guest will ever cat these again.
     */
    fun clearAll() {
        val all: List<File> = synchronized(lock) {
            val copy = ring.toList()
            ring.clear()
            copy
        }
        all.forEach { old -> runCatching { deleteFn(old) } }
    }

    /**
     * Re-size the ring to [newCapacity], deleting whatever no longer fits.
     *
     * [meta1028-B04 F-11]: the ledger used to be built with DEFAULT_CAPACITY
     * (= 4) while the offload worker pool is sized from
     * [com.rikkaminis.app.data.ConcurrencyPrefs.maxConcurrentSessions()]
     * (1..16). At 16 concurrent offload workers the ring is too small: a
     * file is evicted after TWO newer requests even though FOURTEEN requests
     * are still in flight and have not cat'd their reply yet — the exact
     * "delete a file whose guest hasn't cat'd it" failure the ledger was
     * written to prevent, silently restored by a knob the ledger never
     * looked at. Only the overflow is deleted; already-retained files stay.
     */
    fun resize(newCapacity: Int) {
        require(newCapacity >= 1) { "capacity must be >= 1, was $newCapacity" }
        val overflow: List<File> = synchronized(lock) {
            capacity = newCapacity
            val out = ArrayList<File>()
            while (ring.size > capacity) out.add(ring.removeFirst())
            out
        }
        overflow.forEach { old -> runCatching { deleteFn(old) } }
    }

    companion object {
        /**
         * Comfortably above MAX_CONCURRENT_SHELLS (2): even with both
         * shells offloading back-to-back, a file is only evicted after 3
         * newer requests completed their full reply→cat cycle.
         */
        const val DEFAULT_CAPACITY = 4

        /**
         * [meta1028-B04 F-11] In-flight margin on top of the worker count.
         * Four requests' worth of slack — with `2n + 2` a file survives `n`
         * full cycles at every concurrency from 1 to 16.
         */
        private const val HEADROOM = 2

        /**
         * The ledger capacity for [workers] concurrent offload workers.
         *
         * Kept here (not at the call site) so the GAP between the ledger and
         * the worker pool is one expression the JVM test can pin. Inputs
         * below 1 are floored rather than throwing: a caller that forgot to
         * clamp its knob must not take the offload server down.
         */
        fun capacityFor(workers: Int): Int = 2 * maxOf(2, workers) + HEADROOM
    }
}

/**
 * [meta1028-B04 F-11] Write ONE offload response and account for it — the
 * whole reason this is a function and not two lines at the call site is that
 * the failure path has to delete the file it created.
 *
 * Order: register FIRST ([OffloadTmpFileLedger.register]), then write. The
 * write is the only step that can throw (ENOSPC on the tmpfs, an interrupted
 * thread), and a file registered before it is reclaimed on failure — whereas
 * a file registered after it is leaked by exactly that throw. The old
 * `tmpHost.writeText(...)` + `rotate(...)` pair leaked the partial file, and
 * the ledger could not cover the gap because it never heard about a file
 * whose write failed.
 *
 * [writeFn] is injectable so a failing write is testable off-device.
 *
 * Returns `Result.success(byte count)` or `Result.failure(cause)`; the caller
 * turns a failure into INTERNAL_DELIVERY + exit 1.
 */
internal fun writeResponseFile(
    file: File,
    text: String,
    ledger: OffloadTmpFileLedger,
    writeFn: (File, String) -> Unit = { f, s -> f.writeText(s) },
): Result<Int> {
    ledger.register(file)
    return try {
        writeFn(file, text)
        Result.success(text.length)
    } catch (t: Throwable) {
        // Delete OUTSIDE the ledger's own bookkeeping: the file exists (or
        // half-exists) and is no longer anybody's response, so it must not
        // stay in the ring — that is what turns a write failure into a leak.
        ledger.reclaim(file)
        Result.failure(t)
    }
}
