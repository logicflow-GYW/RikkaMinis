package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [surgery-1120 A-01 review] Which staging run dirs this PROCESS is still
 * working on, and which dirs existed when the main process came up.
 *
 * Two separate defects needed the same answer ("is this dir still owned?"):
 *
 *  1. **Late recovery vs. a live stream.** `PartialStreamRecovery.recover()`
 *     runs twice (cold start + once after 90 s). The second pass re-reads the
 *     staging root while this same process may be streaming — and a dir's
 *     mtime does not move when the WORKER appends `stream.jsonl` (only the
 *     file's mtime moves). So a healthy run older than the 30 s grace looked
 *     recoverable, and recovery could insert a DONE prefix as a finished
 *     answer while the live engine was about to append the same text to the
 *     real row (duplicate answer, and the live row loses its spool).
 *  2. **Unowned-before-meta window.** A dir exists from `mkdir` until
 *     `meta.json` lands, and a run that never gets a meta (start failed) has
 *     no owner record either. Recovery used to read such a dir's whole stream
 *     before discovering it had nothing to attribute.
 *
 * The registry answers #1 exactly (this process knows its own live dirs); the
 * boot baseline answers #2 and bounds the retry pass to dirs that existed
 * BEFORE this process could start any stream, so a run created by a later
 * turn is never a recovery candidate at all.
 *
 * Not a security boundary and not cross-process: the worker's liveness is
 * [ModelExecutionRunDir.workerMayWrite]'s job. This only says "the main
 * process itself still writes here".
 */
internal object StreamOwnership {

    /**
     * [surgery-1120 A-01 review] The staging root under `cacheDir`. One
     * definition: recovery, the reaper and the collector all sweep the same
     * directory, and a copy of this literal that drifts turns "nothing is
     * owned" into "everything is garbage".
     */
    const val STAGING_ROOT = "model-exec"

    /** Canonical paths of run dirs whose collector is still attached. */
    private val liveDirs = ConcurrentHashMap.newKeySet<String>()

    /** Staging-root child names that existed before the first stream started. */
    @Volatile
    private var bootBaseline: Set<String>? = null

    private fun key(dir: File): String =
        runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }

    /** Collector: this process is now writing this run dir. */
    fun register(dir: File) {
        runCatching { liveDirs.add(key(dir)) }
    }

    /** Collector: the run dir is no longer ours (any outcome). */
    fun release(dir: File) {
        runCatching { liveDirs.remove(key(dir)) }
    }

    /** True while a collector in THIS process still owns the dir. */
    fun isLive(dir: File): Boolean =
        runCatching { liveDirs.contains(key(dir)) }.getOrDefault(false)

    /**
     * Main-process start: record the dirs that already exist. Called once, in
     * `MinisApp` right after the process-role early returns — i.e. before the
     * first stream of this process can create a dir.
     *
     * Idempotent: the first call wins, so a second caller cannot narrow the
     * baseline after streams started (which would hide the dirs recovery is
     * supposed to see).
     */
    fun recordBootBaseline(stagingRoot: File) {
        if (bootBaseline != null) return
        val names = runCatching {
            stagingRoot.listFiles()
                ?.filter { it.isDirectory }
                ?.mapTo(mutableSetOf()) { it.name }
                ?: emptySet()
        }.getOrDefault(emptySet())
        bootBaseline = names
    }

    /**
     * True when [dir] may be considered by cold-start recovery: either the
     * baseline was never recorded (a caller that does not participate, e.g. a
     * unit test) or the dir predates this process's first stream.
     */
    fun existedAtBoot(dir: File): Boolean {
        val baseline = bootBaseline ?: return true
        return baseline.contains(dir.name)
    }

    /** Test/teardown hook: forget the baseline (production never calls it). */
    internal fun clearForTest() {
        liveDirs.clear()
        bootBaseline = null
    }
}
