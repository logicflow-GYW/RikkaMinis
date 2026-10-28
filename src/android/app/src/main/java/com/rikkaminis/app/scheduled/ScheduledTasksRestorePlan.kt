package com.rikkaminis.app.scheduled

/**
 * [FIX-1028 / A-16 = B-5] The restore decision for the backup's
 * "scheduledTasks" section — the part of a restore that may REFUSE.
 *
 * The defect (found independently by two walkthroughs, same code): restoring a
 * package whose tasks section is present but EMPTY (or whose entries the codec
 * drops one by one) wrote that empty set over this device's live
 * `scheduled-tasks.json`, and reported `scheduledTasksImported = true`. The
 * user's daily automations were deleted by a restore that said it succeeded,
 * and the package's `version` field was write-only — a document from a newer
 * build was parsed as if it were v1.
 *
 * The rule here is the same floor P1-D put under the orphan reclaimer
 * (`SessionFileStore.withEmptyLiveFloor`): an empty incoming set is not
 * evidence that the user wants nothing — it is evidence that we read nothing.
 * "Keep what is on the device" is recoverable; "the tasks are gone" is not.
 *
 * Pure (no Context, no I/O) so the boundary is verifiable in the JVM harness:
 * `ScheduledTasksRestorePlanTest`.
 */
internal object ScheduledTasksRestorePlan {

    /** What the store should do with the incoming document, plus the line the
     *  backup screen shows (through `ImportResult.skipped`) when anything was
     *  refused or dropped. */
    data class Outcome(
        val applied: Boolean,
        val notice: String?,
    )

    /**
     * @param declaredVersion `version` from the backup section, or null when
     *   the key is absent (pre-versioning packages).
     * @param supportedVersion [ScheduledTasksCodec.VERSION] — the only shape
     *   this build knows how to read.
     * @param rawEntries task entries the section actually held (before the
     *   codec's per-entry defensive drops).
     * @param parsedTasks entries that survived parsing.
     * @param liveTasks tasks currently on THIS device.
     */
    fun decide(
        declaredVersion: Int?,
        supportedVersion: Int,
        rawEntries: Int,
        parsedTasks: Int,
        liveTasks: Int,
    ): Outcome {
        // A version we do not recognise is NOT a version to parse optimistically:
        // a newer build may have moved a field that v1 reads as "disabled", or
        // added tasks this build would silently drop. Refuse and say so.
        if (declaredVersion != null && declaredVersion != supportedVersion) {
            return Outcome(
                applied = false,
                notice = "section is format v$declaredVersion, this build reads v$supportedVersion; " +
                    "kept this device's $liveTasks task(s) as-is",
            )
        }
        // Empty incoming set + non-empty local set = the destructive case.
        if (parsedTasks == 0 && liveTasks > 0) {
            return Outcome(
                applied = false,
                notice = "backup carries no usable task ($rawEntries entr${if (rawEntries == 1) "y" else "ies"} " +
                    "in the section); kept this device's $liveTasks task(s) as-is",
            )
        }
        val dropped = rawEntries - parsedTasks
        return Outcome(
            applied = true,
            notice = if (dropped > 0) {
                "$dropped unparseable task entr${if (dropped == 1) "y" else "ies"} in the backup were skipped"
            } else {
                null
            },
        )
    }
}

/**
 * [FIX-1028 / A-16 = B-5] What [ScheduledTasksStore.importDocument] did, handed
 * back to the caller so the restore report can stop claiming success on a
 * refusal.
 */
data class ScheduledTasksRestoreOutcome(
    val applied: Boolean,
    val notice: String?,
)
