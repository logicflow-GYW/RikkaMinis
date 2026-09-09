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
 * Pure JVM (only java.io.File + an injectable delete function) so the
 * concurrency invariants are unit-testable without Android.
 */
internal class OffloadTmpFileLedger(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val deleteFn: (File) -> Boolean = { it.delete() },
) {
    init {
        require(capacity >= 1) { "capacity must be >= 1, was $capacity" }
    }

    private val lock = Any()
    private val ring = ArrayDeque<File>(capacity)

    /** Number of tmpfiles currently retained (for tests/observability). */
    val size: Int get() = synchronized(lock) { ring.size }

    /** The most recently registered file, if any. */
    val newest: File? get() = synchronized(lock) { ring.lastOrNull() }

    /**
     * Register [file] as just-written. When the ring exceeds [capacity],
     * the OLDEST file — which has been through at least [capacity] full
     * request/reply/cat cycles — is deleted.
     */
    fun rotate(file: File) {
        val evicted: File? = synchronized(lock) {
            ring.addLast(file)
            if (ring.size > capacity) ring.removeFirst() else null
        }
        evicted?.let { old -> runCatching { deleteFn(old) } }
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

    companion object {
        /**
         * Comfortably above MAX_CONCURRENT_SHELLS (2): even with both
         * shells offloading back-to-back, a file is only evicted after 3
         * newer requests completed their full reply→cat cycle.
         */
        const val DEFAULT_CAPACITY = 4
    }
}
