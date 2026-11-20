package com.rikkaminis.app.data.repository

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Shared transaction/atomic-publish boundary for MCPRepository and guest
 * `utils/config.py`. The stable `servers.json.lock` inode uses POSIX record
 * locks (Python lockf, NOT flock); the JSON inode itself is replaced per write.
 * The caller must re-read, apply its delta, publish and update memory inside
 * [withTransaction], never publish a cached whole-list snapshot.
 * Plain java.io/java.nio so the real boundary is JVM-testable.
 */
internal object ServersJsonIo {

    // File locks do not exclude other threads of the same JVM (they throw an
    // OverlappingFileLockException instead). Serialize local transactions too.
    private val transactionMonitor = Any()

    fun <T> withTransaction(dir: File, baseName: String, action: () -> T): T =
        synchronized(transactionMonitor) {
            dir.mkdirs()
            RandomAccessFile(File(dir, "$baseName.lock"), "rw").use { file ->
                file.channel.use { channel ->
                    channel.lock().use { action() }
                }
            }
        }

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
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            publish(tmp, File(dir, baseName))
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        } finally {
            pruneStaleTemps(dir, baseName)
        }
    }

    /** Publish only by rename; a failed rename must never truncate the live JSON. */
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
        // rename(2) is still atomic on local filesystems without ATOMIC_MOVE.
        if (tmp.renameTo(target)) return true
        tmp.delete()
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
