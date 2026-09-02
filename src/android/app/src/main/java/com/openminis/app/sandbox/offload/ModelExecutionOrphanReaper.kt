package com.openminis.app.sandbox.offload

import android.content.Context
import android.util.Log
import java.io.File

/**
 * TF-G (P1-2): orphan run-directory reaper for [ModelExecutionService] staging.
 *
 * A run dir can be left behind when:
 *   - a streaming worker timed out its client-ack and control got lost;
 *   - the client crashed / force-stopped mid-stream without deleting;
 *   - the worker self-reaped but the client never came back to delete.
 *
 * Reaping is CONSERVATIVE — it only deletes a dir that satisfies ALL of:
 *   1. `terminal.json` exists (the worker's LAST write: stream flushed +
 *      result committed + final state written);
 *   2. [ModelExecutionRunDir.safeToDelete] passes: worker pid confirmed DEAD
 *      for THIS run (or it never wrote a valid ref while a terminal exists);
 *   3. the dir's mtime is older than [ORPHAN_AGE_MS] (no worker/client touch
 *      for a while — never race an active run);
 *   4. the canonical path is confirmed under `[cacheDir]/model-exec/` (never
 *      delete outside the staging root — canonicalize + prefix guard).
 *
 * UNKNOWN liveness, an ALIVE worker, an absent terminal, or anything outside
 * the staging root is NEVER deleted.
 */
object ModelExecutionOrphanReaper {

    private const val TAG = "ModelExecOrphanReaper"
    private const val STAGING_ROOT = "model-exec"
    /** A run dir must be untouched this long before it's deemed an orphan. */
    private const val ORPHAN_AGE_MS = 10 * 60 * 1000L // 10 minutes

    /**
     * Scan the staging root and delete any eligible orphan run dir. Returns
     * the number deleted (for logging). Never throws.
     */
    fun reapOrphans(context: Context): Int {
        val root = File(context.cacheDir, STAGING_ROOT)
        if (!root.isDirectory) return 0
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { root }
        // [fix/audit-s2h3] was `val deleted = 0` — never incremented, so the
        // caller's "reclaimed N run dir(s)" log always printed 0 and hid the
        // real orphan-reap volume.
        var deleted = 0
        val children = runCatching { root.listFiles() ?: emptyArray() }.getOrDefault(emptyArray())
        for (child in children) {
            if (!child.isDirectory) continue
            if (!looksLikeRunDir(child)) continue
            // Path guard (criterion 4): canonicalize child and confirm it is a
            // direct child of the canonical staging root. A stray symlink or
            // `..` cannot escape the root this way.
            val canonicalChild = runCatching { child.canonicalFile }.getOrElse { child }
            if (canonicalChild.parentFile != canonicalRoot) {
                Log.w(TAG, "reaper skipped non-staging path: ${child.absolutePath}")
                continue
            }
            // Criterion 1: terminal barrier present.
            if (!ModelExecutionRunDir.terminalPresent(child)) continue
            // Criterion 2: safeToDelete (terminal + worker pid DEAD / never had ref).
            val runId = ModelExecutionDispatcher.runIdOf(child)
            if (!ModelExecutionRunDir.safeToDelete(child, runId)) continue
            // Criterion 3: mtime older than ORPHAN_AGE_MS.
            val mtime = child.lastModified()
            if (System.currentTimeMillis() - mtime < ORPHAN_AGE_MS) continue
            val ok = runCatching { child.deleteRecursively(); true }.getOrDefault(false)
            if (ok) {
                deleted++
                Log.i(TAG, "reaped orphan run dir: ${child.name} (mtime ${mtime}ms)")
            } else {
                Log.w(TAG, "reaper failed to delete orphan: ${child.name}")
            }
        }
        return deleted
    }

    /** A run dir is a `run-<uuid>` directory (conservative shape check). */
    private fun looksLikeRunDir(f: File): Boolean {
        val name = f.name
        return name.startsWith("run-") && name.length > "run-".length + 8
    }
}