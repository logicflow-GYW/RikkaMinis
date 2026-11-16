package com.rikkaminis.app.tools

import com.rikkaminis.app.sandbox.normalizeLinuxPath

/**
 * [fix/append-only-write-guard-1116] `file_write` must not be able to destroy a
 * ledger or a memory file by accident.
 *
 * Six incidents between 2026-10-17 and 2026-11-16 destroyed
 * `/var/minis/shared/backlog.md` in exactly the same way: a `file_write` call
 * that omitted `append = true` replaced a multi-megabyte, several-hundred-section
 * ledger with a few hundred bytes.
 *
 * | # | when | damage |
 * |---|---|--------|
 * | 1 | 2026-10-17 | 135 sections / ~440 KB overwritten — sections 119–137 and 145–155 had to be regenerated |
 * | 2 | 2026-10-18 | ~209 sections → 25 lines |
 * | 3 | 2026-10-23 | 358 sections → 19 lines |
 * | 4 | 2026-10-24 | 1.5 MB overwritten |
 * | 5 | 2026-10-30 | 494 sections / 1.92 MB → 1 section / 6.3 KB |
 * | 6 | 2026-11-16 | 484 sections / 2.09 MB → 1256 bytes |
 *
 * Every one was recovered from a snapshot, and every one had a written rule
 * saying not to do it. The rules lived in prose the writer had to remember,
 * while the tool's default (`append = false`) pointed the other way — a
 * dedicated `shared_append.sh` was even built after incident 2 and then was
 * simply not used (and later disappeared). Prose is not a guard.
 *
 * The shape used here is the one this repo already applies twice:
 *  - [com.rikkaminis.app.data.OffloadedPayloadGuard] refuses a payload it
 *    recognises as corrupt instead of writing it;
 *  - [com.rikkaminis.app.backup.MemoryRestoreGuard] treats memory as
 *    append-only user data and lets the newer copy win.
 *
 * Same principle: an append-only target cannot be overwritten by default. The
 * refusal message *is* the rule — it reaches the model at the moment it tries,
 * which is the only moment it is actually reading. Replacing such a file is
 * still possible, but it now costs an explicit `overwrite = true` plus a
 * written reason, i.e. the dangerous path is the longer one.
 *
 * Pure by design (no Android, no IO) so the decision table is JVM-testable.
 *
 * Known boundary: matching is lexical, done on the normalized *guest* path. A
 * symlink aimed at an append-only file from an ordinary path is not detected
 * here; the snapshot in [WriteSnapshotPlan] still covers that case.
 */
internal object AppendOnlyWriteGuard {

    /**
     * Files that only ever grow. Deliberately an explicit list, not "every
     * `.md` under `/var/minis/shared/`" — that directory also holds reports and
     * handoff notes that agents legitimately rewrite in place.
     */
    private val EXACT_TARGETS = setOf(
        "/var/minis/shared/backlog.md",
    )

    /**
     * Directories whose contents are append-only user data. `memory_write`
     * (which has no overwrite capability at all, and therefore has never lost
     * an entry) is the intended writer; `file_write` reaching into here is
     * almost always a mistake.
     *
     * `/var/minis/memory/GLOBAL.md` is user-maintained through Settings; an
     * agent asked to edit it must say why. That friction is the point.
     */
    private val TARGET_PREFIXES = listOf(
        "/var/minis/memory/",
    )

    sealed interface Decision {
        data object Allow : Decision

        data class Refuse(
            val reason: RefusalReason,
            val currentBytes: Long,
            val incomingBytes: Long,
        ) : Decision
    }

    enum class RefusalReason {
        /** The target is append-only and `append` was not set. */
        NEEDS_APPEND,

        /** `overwrite = true` was passed without a reason. */
        OVERWRITE_NOT_JUSTIFIED,
    }

    /**
     * True when [path] names one of the append-only targets.
     *
     * Normalized first ([normalizeLinuxPath]) so `/var/minis/shared//backlog.md`,
     * `/var/minis/shared/./backlog.md` and
     * `/var/minis/workspace/../shared/backlog.md` all land on the same answer.
     * An unnormalizable path is not append-only — it will fail path resolution
     * later anyway.
     */
    fun isAppendOnly(path: String): Boolean {
        val normalized = normalizeLinuxPath(path) ?: return false
        if (normalized in EXACT_TARGETS) return true
        return TARGET_PREFIXES.any { normalized.startsWith(it) }
    }

    /**
     * @param append the caller's `append` flag.
     * @param overwrite the caller's explicit `overwrite` flag.
     * @param overwriteReason free-text justification, required with [overwrite].
     * @param exists whether the target is already on disk. A target that does
     *   not exist cannot lose anything, so creating it is always allowed.
     * @param currentBytes size of the file that would be replaced.
     * @param incomingBytes size of the payload about to be written.
     */
    fun decide(
        path: String,
        append: Boolean,
        overwrite: Boolean,
        overwriteReason: String,
        exists: Boolean,
        currentBytes: Long,
        incomingBytes: Long,
    ): Decision {
        if (!isAppendOnly(path)) return Decision.Allow
        // Nothing to lose.
        if (!exists) return Decision.Allow
        if (append) return Decision.Allow
        if (!overwrite) {
            return Decision.Refuse(RefusalReason.NEEDS_APPEND, currentBytes, incomingBytes)
        }
        if (overwriteReason.isBlank()) {
            return Decision.Refuse(RefusalReason.OVERWRITE_NOT_JUSTIFIED, currentBytes, incomingBytes)
        }
        return Decision.Allow
    }

    /**
     * The tool output for a refusal. Deliberately names the damage that would
     * have happened (byte counts) and the two ways out, so the next call can be
     * correct without another round trip.
     */
    fun refusalMessage(displayPath: String, refuse: Decision.Refuse): String {
        val loss = if (refuse.currentBytes > 0) {
            "It currently holds ${refuse.currentBytes} bytes; this call would write ${refuse.incomingBytes}."
        } else {
            "This call would write ${refuse.incomingBytes} bytes."
        }
        val why = when (refuse.reason) {
            RefusalReason.NEEDS_APPEND ->
                "Refusing to replace it: '$displayPath' is an append-only file (a ledger or a memory file), " +
                    "and a plain file_write replaces the whole file. This exact file has been destroyed " +
                    "six times this way, and every recovery needed a snapshot."
            RefusalReason.OVERWRITE_NOT_JUSTIFIED ->
                "Refusing to replace it: 'overwrite' was set but 'overwrite_reason' is empty. " +
                    "Replacing an append-only file requires saying why, in one sentence."
        }
        return "Error: $why $loss\n" +
            "Do this instead: pass append=true to add to the end (that is almost always what you want here).\n" +
            "If you really must replace the whole file, pass overwrite=true and overwrite_reason=\"<why>\"; " +
            "a snapshot of the current bytes is written before the replacement either way."
    }
}
