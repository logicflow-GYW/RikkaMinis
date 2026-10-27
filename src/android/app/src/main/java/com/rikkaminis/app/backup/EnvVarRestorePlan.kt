package com.rikkaminis.app.backup

/**
 * [T-envvar-restore-deadlock] What Stage 4 (environment variables) should do
 * with a backup entry whose key already exists on this install.
 *
 * ## Why this exists
 *
 * Stage 4 used to `continue` on a duplicate key — the backup's value, note and
 * group were all dropped. That is a *deadlock*, not a conservative choice:
 *
 *   swap the signing key → `EncryptedSharedPreferences` is re-bound to a
 *   keystore that no longer holds the old master key → every stored value is
 *   gone → the first restore is a secret-free backup, which recreates the keys
 *   as *empty shells* (metadata only, by design) → every later restore, this
 *   time with secrets, hits the duplicate-key guard and is skipped → the values
 *   can never come back through the restore path.
 *
 * Observed on this install: two imports in a row reported `envVars=0
 * skipped=21`, each line "already exists, left as-is", the second one with
 * `hadSecrets=true`. Deleting the 21 shells by hand and importing again gave
 * `envVars=21 skipped=0` with byte-identical values — the secrets were in the
 * backup all along; the import path was refusing to write them.
 *
 * ## The three branches (and where this deliberately differs from providers)
 *
 * 1. local value empty, backup carries one → **write the backup's value.**
 *    This is the reinstall case the fix is about.
 * 2. local value non-empty and different → **keep the local value** and say so
 *    in the restore report.
 * 3. backup carries no value (secret-free export) → nothing to write; the
 *    entry stays as it is (a missing entry is still created as an empty shell
 *    by the caller, unchanged).
 *
 * Branch 2 is the one place where Stage 4 does NOT follow the
 * restore-is-restore (backup-wins) rule that
 * [com.rikkaminis.app.data.repository.ProviderRepository.mergeImportInstanceJSON]
 * and the model-group merge follow, and the asymmetry is intentional: a
 * provider's configuration is rendered in the UI field by field, so a wrong
 * overwrite is visible and re-editable, while an env var *value* is a masked
 * field — once a stale backup value silently replaces the live credential the
 * user has no way to notice which one is stored, and no way to get the live
 * one back (the only copy was the value that just got overwritten; the
 * pre-restore snapshot is the sole escape hatch). A visible "local value kept"
 * line is recoverable; an invisible overwrite is not.
 *
 * note/group are plain metadata (never masked, never secret — they ride the
 * export unconditionally), so a blank local one is filled from the backup and
 * a non-blank local one is never overwritten, in every branch.
 *
 * Pure on purpose: no Android types, so the decision is exercised by a plain
 * JVM unit test against this production file (see
 * `ConfigBackupEnvvarsAndOverlayTest`).
 */
internal data class EnvVarRestorePlan(
    /** Value to store, or null to leave the stored value untouched. */
    val value: String? = null,
    /** Note to store, or null to leave the local note untouched. */
    val note: String? = null,
    /** Group to store, or null to leave the local group untouched. */
    val group: String? = null,
    /** Line for the restore report, or null when there is nothing to say. */
    val report: String? = null,
) {
    /** True when the backup's value is the one that ends up stored. */
    val writesValue: Boolean get() = value != null

    /** True when the caller has to call `update()` at all. */
    val touchesAnything: Boolean get() = value != null || note != null || group != null
}

/**
 * Decides one Stage 4 entry for a key that already exists locally.
 *
 * [key] is the *existing* entry's key (already normalised by the repository) so
 * the report line names what the user will actually see in Settings.
 * [localValue] is null or "" when the entry has no stored value — after a
 * keystore re-bind `getValue` returns null for every key, which is exactly
 * branch 1.
 */
internal fun planEnvVarRestore(
    key: String,
    localValue: String?,
    localNote: String,
    localGroup: String,
    backupValue: String,
    backupNote: String,
    backupGroup: String,
): EnvVarRestorePlan {
    val backupHasValue = backupValue.isNotEmpty()
    val localHasValue = !localValue.isNullOrEmpty()

    val backupNoteTrimmed = backupNote.trim()
    val backupGroupTrimmed = backupGroup.trim()
    val noteToWrite = backupNoteTrimmed.takeIf { it.isNotEmpty() && localNote.trim().isEmpty() }
    val groupToWrite = backupGroupTrimmed.takeIf { it.isNotEmpty() && localGroup.trim().isEmpty() }

    return when {
        // Branch 1: the reinstall case — nothing stored locally, backup has it.
        backupHasValue && !localHasValue ->
            EnvVarRestorePlan(value = backupValue, note = noteToWrite, group = groupToWrite)

        // Branch 2: two different live values — keep the local one, say why.
        backupHasValue -> EnvVarRestorePlan(
            note = noteToWrite,
            group = groupToWrite,
            report = if (backupValue == localValue) {
                null
            } else {
                "env var \"$key\": local value kept (backup differs) — " +
                    "replace it manually if the backup is right"
            },
        )

        // Branch 3: a secret-free backup carries no value — metadata only.
        else -> EnvVarRestorePlan(note = noteToWrite, group = groupToWrite)
    }
}
