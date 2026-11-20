package com.rikkaminis.app.share

import java.util.concurrent.atomic.AtomicLong

/**
 * [§1042-A-07] Pure accounting core for the share-staging byte budget.
 *
 * The counter itself is process-lifetime (see ShareReceiverActivity's
 * companion: it must survive Activity re-creation while the staged files
 * stay on disk), but every mutation now funnels through this JVM-testable
 * class so the clamp semantics — "the balance never goes negative, even on
 * a debit larger than the balance" — are pinned by unit tests instead of
 * living only inside an Activity that cannot run on the JVM.
 *
 * Two defects this core closes (deep-audit-1042 A-07):
 *  1. `debitStagedBytes` clamped a DISCARDED copy
 *     (`totalStagedBytes.addAndGet(-bytes).coerceAtLeast(0L)` written as a
 *     statement), so a double debit — e.g. the same staged file freed by
 *     both the provider-import path and cleanSharedFiles — drove the REAL
 *     counter negative, and the cumulative MAX_TOTAL_BYTES check then
 *     under-counted and admitted shares past the cap.
 *  2. The AttachText branch (long EXTRA_TEXT staged as shared-text-*.txt)
 *     skipped the budget entirely: no per-file / cumulative guard, no
 *     credit — a flood of huge text shares bypassed the 500 MB cap the
 *     binary path enforces.
 */
internal class StagedBytesLedger(initial: Long = 0L) {

    private val total = AtomicLong(initial)

    /** Current staged-byte total. */
    fun current(): Long = total.get()

    /** Credit bytes written to the staging directory. */
    fun credit(bytes: Long) {
        if (bytes > 0L) total.addAndGet(bytes)
    }

    /**
     * Debit bytes freed by deleting a staged file outside
     * [SharedShareStore.cleanSharedFiles]. The floor at zero is the whole
     * point: over-counting would falsely trip MAX_TOTAL_BYTES and reject
     * legitimate shares while nothing is on disk. [updateAndGet] writes the
     * clamped balance back atomically — the clamp must live INSIDE the
     * write, never on a discarded copy.
     */
    fun debit(bytes: Long) {
        total.updateAndGet { (it - bytes).coerceAtLeast(0L) }
    }

    /** Zero the ledger after the whole staged set was deleted. */
    fun reset() {
        total.set(0L)
    }

    /**
     * [FIX-R3-E F-14] Re-align the ledger with the files actually on disk
     * (their summed byte sizes). The old consumers either reset to zero
     * (under-counting when another share's files were still staged) or left
     * freed bytes in place (over-counting until the 500 MB cap falsely tripped
     * with nothing on disk). An absolute reconcile is both clamped and exact:
     * whatever the callers forgot to debit, the balance snaps back to the
     * truth.
     */
    fun set(bytes: Long) {
        total.set(bytes.coerceAtLeast(0L))
    }
}

/**
 * [R5-A3 F-07] Process-lifetime registry of staged files that exist on disk
 * but are not (yet) referenced by the pending share.
 *
 * The keep set in [SharedShareStore.cleanSharedFiles] is "what the pending
 * share references", and the pending share is only written at hand-off
 * (`savePendingShare`, which happens after every attachment has been copied and
 * after the provider-import dialog is answered). Everything a producer writes
 * before that instant therefore looked exactly like an orphan of an earlier
 * share, so a `cleanSharedFiles` triggered by the *previous* share being
 * consumed could delete the *current* share's freshly staged bytes — the user
 * then got ghost attachments pointing at nothing.
 *
 * The producer claims a name BEFORE creating the file ([begin]) and releases it
 * when it is either persisted in the pending share ([endAll] from the hand-off)
 * or deleted ([end] from the discard path). Registered names are protected from
 * cleanup; nothing else changes.
 *
 * Deliberately process-lifetime (like the byte ledger): producers and the
 * consumer run in the same process, and a process death takes the staged files'
 * only reference with it anyway — the survivors are then genuine orphans and
 * must be collectable, which is exactly what an empty registry gives.
 */
internal object StagedInFlightFiles {

    private val lock = Any()
    private val states = mutableMapOf<String, Long>()

    /**
     * Claim [name] before the file is created. An existing committed claim is
     * preserved so Activity recreation cannot turn credited bytes back into
     * pending bytes. Empty names are ignored.
     */
    fun begin(name: String) = synchronized(lock) {
        if (name.isNotEmpty()) states.putIfAbsent(name, -1L)
        Unit
    }

    /** Release [name] (its file is gone, or nothing will reference it again). */
    fun end(name: String) = synchronized(lock) {
        states.remove(name)
        Unit
    }

    /** Release every name in [released] (the hand-off persisted them elsewhere). */
    fun endAll(released: Collection<String>) = synchronized(lock) {
        for (name in released) states.remove(name)
        Unit
    }

    /**
     * Credit a successfully written file once and publish its committed state
     * under the same lock used by cleanup reconciliation. [onCredit] may also
     * checkpoint its extraction slot, making saved-state snapshots atomic with
     * respect to the byte credit.
     */
    fun credit(name: String, bytes: Long, onCredit: () -> Unit): Boolean = synchronized(lock) {
        if (name.isEmpty() || (states[name] ?: -1L) >= 0L) return@synchronized false
        onCredit()
        states[name] = bytes.coerceAtLeast(0L)
        true
    }

    /** Restore an already completed on-disk file after process recreation. */
    fun restoreCommitted(name: String, bytes: Long, onCredit: () -> Unit): Boolean =
        credit(name, bytes, onCredit)

    /** Serialize cleanup with commit and pending/in-flight ownership transfer. */
    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    /** Consistent copy for a cleanup pass — see [shouldKeepStagedFile]. */
    fun snapshot(): Set<String> = synchronized(lock) { states.keys.toSet() }

    /** Names whose successful bytes are already represented in the ledger. */
    fun committedSnapshot(): Set<String> = synchronized(lock) {
        states.filterValues { it >= 0L }.keys.toSet()
    }

    /**
     * Reconcile referenced and completed in-flight files from disk. Holding
     * the registry lock across the byte sum and ledger replacement prevents a
     * concurrent successful copy from being credited and then overwritten by
     * a stale cleanup total.
     */
    fun reconcileLedger(
        directory: java.io.File,
        referenced: Set<String>,
        setLedger: (Long) -> Unit,
    ): Long = synchronized(lock) {
        val namesToCount = referenced + states.filterValues { it >= 0L }.keys
        val bytes = namesToCount.sumOf { name ->
            val file = java.io.File(directory, name)
            if (file.isFile) file.length() else 0L
        }
        setLedger(bytes)
        bytes
    }
}

/**
 * [R5-A3 F-07] Keep rule for [SharedShareStore.cleanSharedFiles]: a staged file
 * survives iff the pending share references it as an attachment (the
 * pre-existing rule) OR a producer still has it in flight. Extracted as a pure
 * function so the two-set decision is pinned by JVM tests instead of living
 * only inside an Android-only file walk.
 *
 * Callers must snapshot [inFlight] AFTER listing the directory: a producer
 * claims its name before creating the file, so any entry that shows up in the
 * listing is already visible to a snapshot taken afterwards. (The other order
 * leaves a window where a file created between the snapshot and `listFiles()`
 * is deleted while its producer is still copying into it.)
 */
internal fun shouldKeepStagedFile(
    fileName: String,
    referenced: Set<String>,
    inFlight: Set<String>,
): Boolean = fileName in referenced || fileName in inFlight

/** [§1042-A-07] UTF-8 byte length of a text attachment — the budget counts bytes, not chars. */
internal fun attachTextByteCount(text: String): Long =
    text.toByteArray(Charsets.UTF_8).size.toLong()

/** [§1042-A-07] Which guard rejected a text attachment, or none did. */
internal sealed class AttachTextAdmission {
    /** Single-text cap (MAX_FILE_BYTES in ShareReceiverActivity) exceeded. */
    object RejectedPerFile : AttachTextAdmission()
    /** Cumulative staging cap (MAX_TOTAL_BYTES in ShareReceiverActivity) exceeded. */
    object RejectedCumulative : AttachTextAdmission()
    /** Within both caps — stage it and credit the ledger after the write. */
    object Admitted : AttachTextAdmission()
}

/**
 * [§1042-A-07] Pure admission decision for a text attachment — the same two
 * guards ShareReceiverActivity.copyUriToStaging applies to binary streams,
 * extracted so the JVM tests can pin the boundary. Strict `>` matches the
 * binary path: a text exactly at either cap is admitted. The caller must
 * credit [attachTextByteCount] bytes to the ledger ONLY after the write
 * succeeded.
 */
internal fun admitAttachText(
    textBytes: Long,
    currentTotalBytes: Long,
    maxFileBytes: Long,
    maxTotalBytes: Long,
): AttachTextAdmission = when {
    textBytes > maxFileBytes -> AttachTextAdmission.RejectedPerFile
    currentTotalBytes + textBytes > maxTotalBytes -> AttachTextAdmission.RejectedCumulative
    else -> AttachTextAdmission.Admitted
}
