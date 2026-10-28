package com.rikkaminis.app.data.repository

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * [E2-F14] Atomic writer for `mcp/servers.json`, the one file written by **two
 * independent processes**:
 *
 *   - this app ([MCPRepository.save], from the Settings form, the agent's CLI
 *     bridge, backup import, and the createdAt back-fill on load);
 *   - `minis-mcp-cli` inside the guest (`utils/config.py::save_config`, i.e. the
 *     agent running `minis-mcp-cli add/enable/...` while the user has the sheet
 *     open).
 *
 * Both used a FIXED staging name (`servers.json.tmp`). Two writers therefore
 * raced on one file: writer A truncated and wrote it, writer B truncated and
 * wrote it again, then A's rename published a file whose bytes belong to B (or,
 * if the writes interleaved, a mix of both), while B's rename found no source
 * left and — in the Kotlin fallback — rewrote the live file non-atomically. Both
 * sides are fixed: the staging name is unique per write here and in
 * `config.py`, so each writer only ever renames its own file, and `ATOMIC_MOVE`
 * makes the publication a single rename.
 *
 * Everything here is plain `java.io`/`java.nio` so a JVM probe can drive it
 * concurrently without an Android runtime.
 */
internal object ServersJsonIo {

    /** Staging files older than this are treated as debris from a killed writer. */
    private const val STALE_TEMP_MS = 60L * 60L * 1000L

    /**
     * A staging file nobody else can be writing: the base name is kept (so the
     * file is recognisable next to its target) plus a random suffix.
     */
    fun tempFileFor(dir: File, baseName: String): File =
        File(dir, "$baseName.${UUID.randomUUID()}.tmp")

    /**
     * Write [text] to `dir/baseName` atomically.
     *
     * Returns false when the bytes never reached the target. Debris staging
     * files left behind by a killed writer (stricter than an hour old) are
     * removed on the way through; a fresh one is never touched, because it may
     * belong to the other process mid-write.
     */
    fun writeAtomic(dir: File, baseName: String, text: String): Boolean {
        dir.mkdirs()
        val tmp = tempFileFor(dir, baseName)
        return try {
            tmp.writeText(text)
            publish(tmp, File(dir, baseName))
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        } finally {
            pruneStaleTemps(dir, baseName)
        }
    }

    /** Publish [tmp] over [target]: one atomic rename, with progressively weaker fallbacks. */
    private fun publish(tmp: File, target: File): Boolean {
        runCatching {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            return true
        }
        // Some filesystems refuse ATOMIC_MOVE / rename onto an existing path.
        if (tmp.renameTo(target)) return true
        runCatching {
            target.writeText(tmp.readText())
            tmp.delete()
            return true
        }
        return false
    }

    /**
     * Delete staging files for [baseName] that a killed writer left behind.
     * Age-gated on purpose: a *live* writer's staging file looks exactly like
     * debris, and deleting it would break that writer's publish.
     */
    fun pruneStaleTemps(dir: File, baseName: String, nowMs: Long = System.currentTimeMillis()): Int {
        val candidates = dir.listFiles { f ->
            f.isFile && f.name.startsWith("$baseName.") && f.name.endsWith(".tmp")
        } ?: return 0
        var removed = 0
        for (f in candidates) {
            if (nowMs - f.lastModified() > STALE_TEMP_MS && f.delete()) removed++
        }
        return removed
    }
}
