package com.rikkaminis.app.share

import android.content.Context
import com.rikkaminis.app.logging.AppLogger
import org.json.JSONObject
import java.io.File

/**
 * Mirrors iOS Shared/SharedContainerStore.swift. SharedPreferences-backed
 * read/write of [PendingShare]; staged binary attachments live under
 * `filesDir/share_extension/` so the producer (ShareReceiverActivity)
 * can copy ContentResolver streams into a path the consumer can read
 * after the producer activity finishes.
 */
object SharedShareStore {
    private const val TAG = "SharedShareStore"
    private const val PREFS_NAME = "share_prefs"
    private const val KEY_PENDING_SHARE = "pending_share"
    private const val SHARE_DIR = "share_extension"

    fun sharedFileDirectory(context: Context): File =
        File(context.filesDir, SHARE_DIR).apply { mkdirs() }

    fun savePendingShare(context: Context, share: PendingShare) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PENDING_SHARE, share.toJson().toString()).apply()
        AppLogger.info(TAG, "saved pending share with ${share.items.size} item(s)")
    }

    fun loadPendingShare(context: Context): PendingShare? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PENDING_SHARE, null) ?: return null
        return try {
            PendingShare.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            AppLogger.warning(TAG, "failed to decode pending share: ${e.message}")
            null
        }
    }

    fun clearPendingShare(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_PENDING_SHARE).apply()
    }

    /**
     * Delete staged files that nothing is waiting for, and account for the
     * rest.
     *
     * [FIX-R3-E F-14] This used to walk the whole staging directory and delete
     * EVERY file, then zero the byte counter. Two things were wrong with that:
     *
     *  1. **It deleted files a newer share had just staged.** The consumer's
     *     `cleanSharedFiles` runs after the previous share is injected, while
     *     the next share's `ShareReceiverActivity` may already have written
     *     its files. The keep set is therefore two-part: the pending share's
     *     references PLUS [StagedInFlightFiles], the names a live producer has
     *     claimed but not yet handed off ([R5-A3 F-07] — the pending-share
     *     reference alone still left the whole copy/dialog window unprotected).
     *     Everything outside both sets is an orphan from an earlier share and
     *     goes.
     *  2. **It zeroed the ledger unconditionally**, which under-counted the
     *     bytes that survive — the counter is process-lifetime and the keep
     *     set keeps occupying the budget. The ledger is now reconciled to the
     *     summed size of what is actually left on disk (see
     *     [StagedBytesLedger.set]), so neither direction can drift.
     */
    fun cleanSharedFiles(context: Context) {
        val dir = sharedFileDirectory(context)
        // [R5-A3 F-07] List FIRST, then snapshot the keep sets. A producer
        // claims its staged name before it creates the file, so every name that
        // appears in this listing is already visible to the in-flight snapshot
        // taken below. Reading the listing afterwards would leave a window where
        // a file created in between is deleted while its producer is still
        // copying into it.
        val listed = dir.listFiles().orEmpty()
        val referenced = loadPendingShare(context)?.items.orEmpty()
            .filter { it.kind == PendingShare.Item.Kind.ATTACHMENT }
            .map { it.value }
            .toSet()
        // Files a live producer is still writing (or wrote but has not handed
        // off yet). They are NOT orphans — see [StagedInFlightFiles].
        val inFlight = StagedInFlightFiles.snapshot()
        var keptBytes = 0L
        var deleted = 0
        for (f in listed) {
            if (shouldKeepStagedFile(f.name, referenced, inFlight)) {
                // Only referenced files are counted: an in-flight file's bytes
                // are either already in the process-lifetime ledger (a restored
                // staged set) or will be credited when its copy completes —
                // adding the partial length here would double-count them.
                if (f.name in referenced) keptBytes += f.length()
            } else if (runCatching { f.delete() }.getOrDefault(false)) {
                deleted++
            }
        }
        ShareReceiverActivity.setStagedBytes(keptBytes)
        AppLogger.info(
            TAG,
            "cleaned $deleted stale staged file(s); kept ${referenced.size} referenced + " +
                "${inFlight.size} in flight, ${keptBytes}B accounted on disk",
        )
    }
}
