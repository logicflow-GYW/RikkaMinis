package com.rikkaminis.app.sandbox.offload

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
 *
 * [fix/sweep1014-d2a] Cancelled-orphan branch: a run dir with a `cancel`
 * marker but NO terminal.json used to be skipped forever (the terminal
 * barrier requires the worker's last write, and a worker that crashed /
 * was killed after the client wrote `cancel` never wrote one) while the
 * cold-start recovery policy also skips cancelled dirs
 * ([PartialStreamRecoveryPolicy] `SKIP_CANCELLED` — the content was
 * discarded by design, there is nothing to recover). Such a dir — request.json
 * routinely holds MBs of Base64 image/context — was therefore permanently
 * unreclaimable. The extra branch reaps it ONLY when the client's cancel is
 * present (content value already discarded), the worker is not provably
 * alive for this run, and the dir has aged past [ORPHAN_AGE_MS]. Liveness is
 * checked with BOTH signals, skipping on either: the heartbeat file (a fresh
 * beat proves the worker alive even where /proc is unreadable — TF-J2
 * hidepid) and the pid probe ([ModelExecutionRunDir.probeLiveness] — the only
 * reliable-POSITIVE signal; its DEAD on hidepid devices is not trusted here).
 */
object ModelExecutionOrphanReaper {

    private const val TAG = "ModelExecOrphanReaper"
    private const val STAGING_ROOT = "model-exec"
    /** A run dir must be untouched this long before it's deemed an orphan. */
    private const val ORPHAN_AGE_MS = 10 * 60 * 1000L // 10 minutes

    /**
     * [fix/sweep1014-d2a] Pure decision for the cancelled-orphan reclaim
     * branch (the run dir has NO terminal — the caller only reaches this
     * branch after the terminal check failed). JVM-testable.
     *
     * SAFE only when ALL of:
     *   - a `cancel` marker is present: the CLIENT explicitly discarded this
     *     run's content (ChatStreamOffloadHandler / ModelExecutionDispatcher
     *     write cancel only when the run is not terminal), and the recovery
     *     policy already refuses to recover cancelled dirs (SKIP_CANCELLED);
     *   - the liveness heartbeat is NOT fresh ([ModelExecutionRunDir.beatAlive]
     *     is false) — a worker that is beating is provably alive for THIS run;
     *   - the pid probe does not say ALIVE — the only reliable-positive
     *     liveness signal; a probe DEAD/UNKNOWN on hidepid devices is not
     *     trusted as proof of death, the stale beat + age carry that weight;
     *   - the dir has aged past [ORPHAN_AGE_MS]. Any worker/client write into
     *     the dir (stream appends, state/terminal, beat tmp+rename, the cancel
     *     marker itself) refreshes the dir mtime, so this is a reliable
     *     "nothing has touched this run for a while" proxy — 10 minutes past
     *     the cancel write is far beyond any worker's finish path (the alive
     *     path acks the cancel and writes terminal within seconds).
     */
    internal fun shouldReapCancelledOrphan(
        cancelPresent: Boolean,
        beatAlive: Boolean,
        workerLivenessAlive: Boolean,
        ageMs: Long,
        cancelDeathPresent: Boolean = false,
    ): Boolean {
        if (!cancelPresent) return false // existing skip: no cancel → never reclaim
        // [LOOP-1030 R2 M2] `cancel.death` = the cancel came from the client's
        // death cleanup, so stream.jsonl is a RECOVERABLE half answer, not
        // content "discarded by design". Reclaiming it here would delete the
        // answer ~10 min after the crash — before cold-start recovery gets a
        // chance. Such dirs are reclaimed by recovery itself (SKIP_EMPTY /
        // SKIP_NO_META delete, RECOVER appends then deletes) at next start.
        if (cancelDeathPresent) return false
        if (beatAlive) return false // worker provably alive for THIS run
        if (workerLivenessAlive) return false // pid probe says alive
        return ageMs >= ORPHAN_AGE_MS
    }

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
            // Criterion 3 first (shared by both branches): mtime older than
            // ORPHAN_AGE_MS — no worker/client touch for a while. Checking it
            // before the liveness probes avoids probing dirs that are too
            // young to reap anyway (all-AND semantics, order is an optimization).
            val mtime = child.lastModified()
            val ageMs = System.currentTimeMillis() - mtime
            if (ageMs < ORPHAN_AGE_MS) continue
            val runId = ModelExecutionDispatcher.runIdOf(child)
            val reason: String
            if (ModelExecutionRunDir.terminalPresent(child)) {
                // Criterion 2 (terminal branch): safeToDelete (terminal +
                // worker pid DEAD / never had ref).
                if (!ModelExecutionRunDir.safeToDelete(child, runId)) continue
                reason = "terminal"
            } else {
                // [fix/sweep1014-d2a] Cancelled-orphan branch: no terminal,
                // but the client's cancel is present and the worker is not
                // provably alive — the content was discarded by the cancel
                // semantics and the recovery policy skips cancelled dirs, so
                // the only thing left to do is reclaim the disk.
                val eligible = shouldReapCancelledOrphan(
                    cancelPresent = File(child, ModelExecutionMailbox.FILE_CANCEL).isFile,
                    // [LOOP-1030 R2 M2] death-cancelled dirs hold a recoverable
                    // half answer — never reclaim them here.
                    cancelDeathPresent = File(child, ModelExecutionMailbox.FILE_CANCEL_DEATH).isFile,
                    beatAlive = ModelExecutionRunDir.beatAlive(child),
                    workerLivenessAlive = ModelExecutionRunDir.probeLiveness(child, runId) ==
                        ModelExecutionRunDir.WorkerLiveness.ALIVE,
                    ageMs = ageMs,
                )
                if (!eligible) continue
                reason = "cancelled-orphan"
            }
            val ok = runCatching { child.deleteRecursively(); true }.getOrDefault(false)
            if (ok) {
                deleted++
                Log.i(TAG, "reaped $reason orphan run dir: ${child.name} (mtime ${mtime}ms)")
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