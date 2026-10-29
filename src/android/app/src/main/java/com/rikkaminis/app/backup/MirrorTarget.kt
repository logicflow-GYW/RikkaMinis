package com.rikkaminis.app.backup

import java.io.File

/**
 * [feat/auto-backup-external-mirror] The I/O seam of the external mirror, and
 * its plain-file implementation.
 *
 * Deliberately Android-free (`java.io` only): the JVM harness compiles this
 * file verbatim and pins the byte-level contract (MirrorScopeTest). The
 * MediaStore twin lives in [MirrorStore] as a thin, reviewable adapter — every
 * decision that can be wrong in a harmful way lives HERE, not there.
 */
internal interface MirrorTarget {
    fun read(relPath: String): ByteArray?
    fun write(relPath: String, bytes: ByteArray): Boolean
    fun delete(relPath: String): Boolean

    /** Delete everything under [relDirPrefix] (a directory prefix ending
     *  with '/'). Used only for `previous/` rotation. */
    fun deleteTree(relDirPrefix: String): Boolean

    /** Relative paths of files under [relDirPrefix]; empty when none. */
    fun list(relDirPrefix: String): List<String>
}

/**
 * Plain-file target — the app-specific external dir fallback on API 26–28
 * (public Downloads needs a runtime permission a background sync should not
 * request) and the JVM test target.
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

    override fun deleteTree(relDirPrefix: String): Boolean = runCatching {
        fileOf(relDirPrefix).deleteRecursively()
        true
    }.getOrDefault(false)

    override fun list(relDirPrefix: String): List<String> {
        val root = fileOf(relDirPrefix)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(base).invariantSeparatorsPath }
            .toList()
    }
}
