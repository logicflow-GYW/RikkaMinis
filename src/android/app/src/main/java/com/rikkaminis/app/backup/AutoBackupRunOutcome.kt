package com.rikkaminis.app.backup

/**
 * [RC-U01] What one run of [AutoBackupManager.runNow] actually did.
 *
 * The defect this replaces: `runNow` returned `Unit` and swallowed four
 * different failures (`Log.w` only), so the settings screen's "back up now"
 * row popped "Backup saved" for every one of them — a concurrent run that was
 * skipped entirely, fields the export could not read, an external mirror that
 * never got its copy, and a WebDAV push that failed. The user only found out
 * on restore day, from a backup that was missing pieces or not there at all.
 *
 * Deliberately NOT a `Boolean`: the four failure shapes are not the same
 * severity and the message the UI owes the user differs per shape ("nothing
 * happened" vs "saved, but N pieces are missing" vs "it failed"). Collapsing
 * them into one bit is how the original defect got made.
 *
 * Kotlin/Android-free on purpose: [AutoBackupRunOutcomePolicy.classify] is
 * driven from JVM tests (`AutoBackupRunOutcomeTest`), because the manager
 * itself needs a `MinisApp` and cannot be instantiated off-device.
 */
sealed interface AutoBackupRunOutcome {
    /** Local snapshot written; external mirror and remote push both landed
     *  (or neither is configured). */
    object Success : AutoBackupRunOutcome

    /**
     * A run was already in flight (`AutoBackupManager.inFlight`), so this call
     * returned without writing anything. Not an error — the overlapping run
     * is doing the work — but definitely not "backup saved" either.
     */
    object Skipped : AutoBackupRunOutcome

    /**
     * The local snapshot landed, but at least one layer degraded. Every flag
     * is reported, not just the first: a user who is told only about the
     * remote push never learns the document itself is missing fields.
     *
     * @param unreadableFields count from `ConfigBackup.exportToWriter`
     *        (documents that lost fields to an unreadable source).
     * @param externalMirrorFailed `MirrorStore.mirrorAutoSnapshot` threw or
     *        returned false — the copy outside `filesDir` is stale.
     * @param remotePushFailed the WebDAV upload (or its prune) threw — the
     *        offsite copy is stale.
     */
    data class Partial(
        val unreadableFields: Int,
        val externalMirrorFailed: Boolean,
        val remotePushFailed: Boolean,
    ) : AutoBackupRunOutcome

    /** The local snapshot itself did not land (export/write threw). Nothing
     *  was backed up this run. */
    data class Failed(val reason: String) : AutoBackupRunOutcome
}

/**
 * The one decision behind [AutoBackupRunOutcome.Partial]: a run is only
 * [AutoBackupRunOutcome.Success] when every layer came back clean.
 *
 * Pure so the classification itself is pinned by a JVM test instead of by the
 * device-only call path — the four `Log.w` sites this replaces were each
 * silent exactly because nothing ever asserted on their combination.
 */
object AutoBackupRunOutcomePolicy {

    fun classify(
        unreadableFields: Int,
        externalMirrorFailed: Boolean,
        remotePushFailed: Boolean,
    ): AutoBackupRunOutcome =
        if (unreadableFields <= 0 && !externalMirrorFailed && !remotePushFailed) {
            AutoBackupRunOutcome.Success
        } else {
            AutoBackupRunOutcome.Partial(
                unreadableFields = unreadableFields.coerceAtLeast(0),
                externalMirrorFailed = externalMirrorFailed,
                remotePushFailed = remotePushFailed,
            )
        }
}
