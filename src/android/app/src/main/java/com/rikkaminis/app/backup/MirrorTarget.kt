package com.rikkaminis.app.backup

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * [feat/auto-backup-external-mirror] The I/O seam of the external mirror.
 *
 * Deliberately Android-free (`java.io` / `java.nio` only): the JVM harness
 * compiles this file verbatim and pins the byte-level contract — including the
 * overwrite / delete / tree-removal shapes that the deleted MediaStore-backed
 * target could only ever demonstrate on a device. Every decision that can be
 * wrong in a harmful way lives in [MirrorPlan] and [MirrorScope]; this file
 * only moves bytes.
 */
internal interface MirrorTarget {
    fun read(relPath: String): ByteArray?
    fun write(relPath: String, bytes: ByteArray): Boolean
    fun delete(relPath: String): Boolean

    /** Delete everything under [relDirPrefix] (a directory prefix ending with
     *  '/'). Used only for `previous/` rotation and the one-time layout
     *  migration. */
    fun deleteTree(relDirPrefix: String): Boolean

    /** Relative paths of files under [relDirPrefix]; empty when none. */
    fun list(relDirPrefix: String): List<String>
}

/**
 * Plain-file target — the mirror's only writer.
 *
 * History (2026-11-14): this used to be the API 26–28 fallback, with API 29+
 * routed through a `MediaStoreTarget` so the external copy would show up in
 * Downloads as media-owned rows. That target is gone; measured on device:
 * MediaProvider normalises `text/plain` display names (`memory/2026-11-14.md`
 * is stored as `2026-11-14.md.txt`), so every lookup by the logical name
 * missed, and therefore
 *   - overwrite never fired — each insert forked a new `….md (1).txt`,
 *   - `read()` always returned null — `previous/` archived nothing, ever,
 *   - `delete()` reported success while removing nothing.
 * The mirror looked healthy while serving 16-day-old bytes. Plain files have
 * none of those behaviours, and — the deciding factor — this class runs in the
 * JVM harness, so the whole path is testable without a device.
 *
 * Atomic write (temp + rename): a killed run must not leave a half file under
 * the legal name — same rule as [AutoBackupLocalStore]. The double-rename
 * fallback covers the rename-over-existing-file case on filesystems where
 * `renameTo` refuses it.
 */
internal class FileTarget(private val base: File) : MirrorTarget {

    private fun fileOf(relPath: String) = File(base, relPath)

    override fun read(relPath: String): ByteArray? =
        runCatching { fileOf(relPath).takeIf { it.isFile }?.readBytes() }.getOrNull()

    override fun write(relPath: String, bytes: ByteArray): Boolean = runCatching {
        val f = fileOf(relPath)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) return false
        }
        true
    }.getOrDefault(false)

    override fun delete(relPath: String): Boolean =
        runCatching { !fileOf(relPath).exists() || fileOf(relPath).delete() }.getOrDefault(false)

    /**
     * Recursive delete that never walks INTO a link.
     *
     * `File.deleteRecursively()` calls `isDirectory()` on every child, and that
     * follows symlinks: a link to a directory is then emptied through the link
     * and its targets are deleted. The mirror's own tree contains no links, but
     * this directory lives in the user's Downloads — one link placed there (by
     * a sync client, a "move to SD" utility, or a manual `ln -s`) would make
     * the `previous/` rotation destroy files outside the mirror.
     * `Files.walkFileTree` without `FOLLOW_LINKS` visits a link as a FILE, so
     * the link itself is unlinked and its target is untouched. Same rule and
     * same reason as `internalGuestTmpDeleteTreeNoFollow` in the guest sweep.
     */
    override fun deleteTree(relDirPrefix: String): Boolean {
        val root = fileOf(relDirPrefix)
        // `exists()` follows links: a link to a missing target answers false
        // even though the link itself must still be removed.
        if (!root.exists() && !Files.isSymbolicLink(root.toPath())) return true
        var ok = true
        runCatching {
            Files.walkFileTree(
                root.toPath(),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) ok = false
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                        ok = false
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                        if (exc != null) ok = false
                        if (!runCatching { Files.deleteIfExists(dir) }.getOrDefault(false)) ok = false
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }.onFailure { ok = false }
        return ok
    }

    override fun list(relDirPrefix: String): List<String> {
        val root = fileOf(relDirPrefix)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(base).invariantSeparatorsPath }
            .toList()
    }
}
