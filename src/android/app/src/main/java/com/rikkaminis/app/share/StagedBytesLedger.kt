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
