package com.rikkaminis.app.backup

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * [FIX-P1B / FINDING-14] Local auto-backup file lifecycle: the naming rule that
 * decides "this is a finished backup", the atomic (temp + rename) streaming
 * write, and the cleanup of temp files a killed run left behind.
 *
 * Why this lives in its own file: [AutoBackupManager] is an Android object
 * (Context / Log / MinisApp) and its code cannot be compiled by the JVM test
 * harness, while everything below is plain `java.io`. Same split as
 * `KeystoreRecoveryPlan` (decisions vs I/O): the CALLER keeps the Context work,
 * this object keeps the invariants and is verifiable byte for byte off-device.
 *
 * The defect this exists for (world W6, disk full): the nightly run used to
 * stream the export straight into `rikkaminis-auto-<stamp>.json`. An `ENOSPC`
 * or a kill mid-export left a TRUNCATED document under a fully legal name —
 * invisible damage, because
 *  - the settings list shows it as the newest backup,
 *  - rotation counts it as one of the three kept copies and evicts a good
 *    older one,
 *  - and only "restore" reveals it, as `Malformed JSON`.
 */
internal object AutoBackupLocalStore {

    /** Extension of a complete local backup. */
    const val SUFFIX = ".json"

    /**
     * Extension of an in-flight write. Deliberately does NOT end with [SUFFIX],
     * so [isBackupName] cannot see a half-written document — the whole point of
     * routing every write through [writeAtomically].
     */
    const val TMP_SUFFIX = ".json.tmp"

    /**
     * The single place that builds a local backup's file name
     * (`<prefix><stamp>.json`). Paired with [isBackupName] by
     * `AutoBackupLocalStoreTest`, which asserts the pair agrees — a writer that
     * invents a third shape would otherwise be silently invisible to the
     * lister (or, worse, visible but half-written).
     */
    fun fileName(prefix: String, stamp: String): String = "$prefix$stamp$SUFFIX"

    /**
     * The single "is this a finished local backup" rule behind the settings
     * list, [AutoBackupManager.listLocal] and rotation.
     *
     * It used to be `startsWith(prefix)` alone, which also matched
     * `…json.tmp`: the truncated temp file became the newest "backup".
     */
    fun isBackupName(name: String, prefix: String): Boolean =
        name.startsWith(prefix) && name.endsWith(SUFFIX)

    /** The sibling temp path for [target] (`<name>.json.tmp`). */
    fun tempFor(target: File): File = File(target.parentFile, target.name + TMP_SUFFIX)

    /**
     * Stream a document into [target] the only way a file that a later restore
     * will trust may be written: fill the sibling temp, fsync it, then rename
     * it onto the final name. Either the old bytes or the complete new document
     * is on disk — never a truncated mixture under a legal name.
     *
     * Why not the repo's existing [com.rikkaminis.app.tools.AtomicFileWrite]: it
     * takes the whole document as a `String`, and materializing tens of MB in
     * UTF-16 before the first byte lands is exactly the OOM shape the streaming
     * export removed ([fix/da1023-backup-streaming-fetch]). Same idiom,
     * streaming signature — the lambda receives the writer.
     *
     * Failure policy: a rename that will not take (cross bind-mount boundary,
     * or a target already on disk — the stamp has second resolution, so two
     * runs inside one second do collide) does NOT fall back to writing the
     * final name directly. That fallback is precisely the shape this guard
     * exists to kill. The temp file is deleted and [IOException] rethrown, so
     * the caller treats the whole run as failed (production: `KEY_LAST_RUN`
     * stays unset and the next foreground beat retries instead of waiting a
     * day).
     *
     * Inline so a `suspend` body (the production export) can be passed without
     * this object knowing anything about coroutines.
     *
     * @return whatever [body] returned — the unreadable-field count in
     *   production.
     */
    inline fun <T> writeAtomically(target: File, body: (BufferedWriter) -> T): T {
        val tmp = tempFor(target)
        try {
            val out = FileOutputStream(tmp).use { fos ->
                val writer = fos.bufferedWriter()
                // NOT `writer.use {}`: that closes the stream, and the fd has
                // to stay open for the fsync below. `close()` alone does not
                // push the page cache to the device — a power loss / hard
                // reboot between rename and writeback would otherwise leave a
                // complete-looking name over zeroed blocks.
                val result = body(writer)
                writer.flush()
                runCatching { fos.fd.sync() }
                result
            }
            // Same-second collision: renameTo() does not reliably overwrite on
            // every filesystem (f2fs/ext4 replace the target, some FUSE mounts
            // refuse), so drop the old copy explicitly and retry once.
            if (!tmp.renameTo(target) && !(target.delete() && tmp.renameTo(target))) {
                throw IOException("auto backup rename failed: ${tmp.name} -> ${target.name}")
            }
            return out
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
    }

    /**
     * Delete `<prefix><stamp>.json.tmp` leftovers from a run that was killed
     * mid-write (SIGKILL / process recycle — no `catch` runs, so
     * [writeAtomically]'s cleanup never happens).
     *
     * They are invisible to [isBackupName] by design, so nothing else would
     * ever reclaim the bytes; on an already-full disk that is a self-inflicted
     * leak that grows by one document per killed run.
     *
     * @return how many were removed (best-effort; a failure to delete is not an
     *   error — the file stays invisible either way).
     */
    fun deleteStaleTemps(dir: File, prefix: String): Int {
        var removed = 0
        dir.listFiles { f ->
            f.isFile && isTempName(f.name, prefix)
        }?.forEach { if (it.delete()) removed++ }
        return removed
    }

    /** True for an in-flight write left behind by a killed run. */
    fun isTempName(name: String, prefix: String): Boolean =
        name.startsWith(prefix) && name.endsWith(TMP_SUFFIX)
}
