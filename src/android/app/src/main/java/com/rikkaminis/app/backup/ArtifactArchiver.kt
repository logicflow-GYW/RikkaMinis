package com.rikkaminis.app.backup

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [T-auto-backup-assets] Build a ZIP archive of artifact files WITHOUT loading
 * the whole payload into memory. The automatic backup runs on the app's
 * foreground path (Dispatchers.Default), so a multi-MB artifact set must stream
 * file-by-file into a ZipOutputStream rather than readBytes() the world.
 *
 * Pure JVM (java.util.zip only) so the framing is unit-testable.
 */
object ArtifactArchiver {

    /**
     * Stream [files] (paths relative to [root]) into a NEW zip at [out],
     * each entry prefixed with [entryPrefix] (e.g. "shared/") when non-empty.
     * Source files are read under [root] WITHOUT the prefix; the prefix only
     * decorates the archive entry name so a multi-root archive can route
     * entries back to their own root on restore. Each file is read fully into
     * memory one at a time — bounded by [ArtifactBackupScope.MAX_FILE_BYTES],
     * so the transient peak is ~4MB.
     */
    fun archiveToZip(
        root: File,
        files: List<ArtifactBackupScope.SelectedFile>,
        out: File,
        entryPrefix: String = "",
    ): Long {
        out.parentFile?.mkdirs()
        var totalBytes = 0L
        FileOutputStream(out).use { fos ->
            ZipOutputStream(fos.buffered()).use { zos ->
                totalBytes += writeEntries(zos, root, files, entryPrefix)
            }
        }
        return totalBytes
    }

    /**
     * Append [files] as entries to an already-open [ZipOutputStream] — lets a
     * caller pack several roots into ONE archive ([ConfigBackup] packs
     * shared/ + mcp/ that way). [entryPrefix] decorates entry names only.
     *
     * [transform] is an optional per-entry hook applied to the bytes read from
     * disk BEFORE they are written; its result becomes the archived content.
     * Returning **null DROPS the entry** (fail closed — never a verbatim
     * fallback) and appends its relPath to [droppedSink] when one is supplied,
     * so the caller can keep its file/skip accounting honest. A null
     * [transform] writes every entry verbatim (the pre-[dv20-a1] behaviour).
     *
     * Entry names/prefixes are never touched by the hook — a redacted entry
     * keeps its name, so restore routing and on-disk layout are unchanged.
     */
    fun writeEntries(
        zos: ZipOutputStream,
        root: File,
        files: List<ArtifactBackupScope.SelectedFile>,
        entryPrefix: String = "",
        transform: ((String, ByteArray) -> ByteArray?)? = null,
        droppedSink: MutableList<String>? = null,
    ): Long {
        var totalBytes = 0L
        for (f in files) {
            val src = File(root, f.relPath.replace('/', File.separatorChar))
            val guarded = transform != null
            if (!src.isFile || (guarded && hasSymlinkComponent(root, src))) {
                droppedSink?.add(f.relPath)
                continue
            }
            val raw = try {
                if (guarded) {
                    // Read the same non-link leaf we checked; a replacement
                    // leaf symlink must not be followed after selection.
                    java.nio.file.Files.newInputStream(src.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use {
                        val out = java.io.ByteArrayOutputStream()
                        copyBounded(it, out, ExpansionBudget(maxTotalBytes = ArtifactBackupScope.MAX_FILE_BYTES))
                        out.toByteArray()
                    }
                } else src.readBytes()
            } catch (_: Exception) {
                droppedSink?.add(f.relPath)
                continue
            }
            var bytes = raw
            if (transform != null) {
                val transformed = transform(f.relPath, bytes)
                if (transformed == null) {
                    droppedSink?.add(f.relPath)
                    continue
                }
                bytes = transformed
            }
            zos.putNextEntry(ZipEntry(entryPrefix + f.relPath))
            zos.write(bytes)
            zos.closeEntry()
            totalBytes += bytes.size
        }
        return totalBytes
    }

    /**
     * Inflate a ZIP from [zipFile] into per-prefix destination directories.
     * Entries are routed by their first path segment: `shared/...` lands under
     * [destByPrefix]["shared"], `mcp/...` under ["mcp"]. Entries with an
     * unknown prefix fall back to [fallbackDir] when non-null (single-root
     * callers), otherwise they are rejected (defence in depth: artifact names
     * come from an untrusted file). Returns the number of entries written.
     */
    fun extractZipByPrefix(
        zipFile: File,
        destByPrefix: Map<String, File>,
        fallbackDir: File? = null,
        maxFileBytes: Long = ArtifactBackupScope.MAX_FILE_BYTES,
        maxTotalBytes: Long = 2 * ArtifactBackupScope.MAX_ARCHIVE_BYTES,
    ): Int {
        val budget = ExpansionBudget(maxFileBytes, maxTotalBytes)
        val stages = LinkedHashMap<File, File>()
        val pending = ArrayList<Triple<File, File, File>>()
        val targets = HashSet<String>()
        try {
            java.util.zip.ZipFile(zipFile).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name
                    val slash = name.indexOf('/')
                    val prefix = if (slash > 0) name.substring(0, slash) else ""
                    val destDir = destByPrefix[prefix] ?: fallbackDir
                    val rel = if (slash > 0) name.substring(slash + 1) else name
                    val safeName = name.isNotEmpty() && !name.startsWith("/") &&
                        !name.contains('\\') && !name.contains(':') &&
                        name.split('/').none { it.isEmpty() || it == "." || it == ".." }
                    val target = destDir?.let { File(it, rel) }
                    val accepted = safeName && rel.isNotEmpty() && target != null && destDir != null &&
                        isUnderBase(destDir.canonicalPath, target.canonicalPath)
                    // Charge even excluded / unknown-prefix members; they are
                    // still inflated on this first pass through the archive.
                    if (!accepted) {
                        zf.getInputStream(entry).use { copyBounded(it, null, budget) }
                        continue
                    }
                    checkNotNull(target)
                    checkNotNull(destDir)
                    require(target.canonicalPath != destDir.canonicalPath && !target.isDirectory) {
                        "artifact target is not a file"
                    }
                    require(targets.add(target.canonicalPath)) { "duplicate artifact target" }
                    val relativeTarget = destDir.toPath().toAbsolutePath().normalize()
                        .relativize(target.toPath().toAbsolutePath().normalize()).toString()
                    require(!relativeTarget.split(File.separatorChar).first().startsWith(".artifact-restore-")) {
                        "reserved artifact staging path"
                    }
                    var parent = target.parentFile
                    while (parent != null && parent != destDir) {
                        require(!parent.exists() || parent.isDirectory) { "artifact parent is not a directory" }
                        parent = parent.parentFile
                    }
                    val stageDir = stages.getOrPut(destDir) {
                        require(destDir.isDirectory || destDir.mkdirs()) { "cannot create artifact root" }
                        java.nio.file.Files.createTempDirectory(destDir.toPath(), ".artifact-restore-").toFile()
                    }
                    val staged = File.createTempFile("entry-", ".tmp", stageDir)
                    FileOutputStream(staged).use { output ->
                        zf.getInputStream(entry).use { copyBounded(it, output, budget) }
                        output.fd.sync()
                    }
                    pending.add(Triple(staged, target, destDir.canonicalFile))
                }
            }
            // No existing target is touched until EVERY member is validated
            // and bounded. Each publish is same-volume and never truncates a
            // live file; an I/O failure can leave whole files, not half-files.
            for ((staged, target, root) in pending) {
                require(isUnderBase(root.path, target.canonicalPath) && !target.isDirectory) {
                    "artifact target changed during restore"
                }
                require(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "cannot create artifact parent" }
                java.nio.file.Files.move(staged.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return pending.size
        } finally {
            for (stage in stages.values) stage.deleteRecursively()
        }
    }

    /** Actual expanded bytes, shared by the FIRST filter pass and extraction.
     * Two export roots each have a 12 MiB allowance; no ZIP metadata is trusted. */
    internal class ExpansionBudget(
        val maxFileBytes: Long = ArtifactBackupScope.MAX_FILE_BYTES,
        val maxTotalBytes: Long = 2 * ArtifactBackupScope.MAX_ARCHIVE_BYTES,
    ) {
        var totalBytes: Long = 0
    }

    internal fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream?, budget: ExpansionBudget) {
        val buffer = ByteArray(8192)
        var memberBytes = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            require(n.toLong() <= budget.maxFileBytes - memberBytes) { "artifact entry expanded byte limit exceeded" }
            require(n.toLong() <= budget.maxTotalBytes - budget.totalBytes) { "artifact total expanded byte limit exceeded" }
            memberBytes += n
            budget.totalBytes += n
            output?.write(buffer, 0, n)
        }
    }

    private fun hasSymlinkComponent(root: File, file: File): Boolean {
        if (!isUnderBase(root.canonicalPath, file.canonicalPath)) return true
        var path: File? = file.absoluteFile
        val base = root.absoluteFile
        while (path != null && path != base) {
            if (java.nio.file.Files.isSymbolicLink(path.toPath())) return true
            path = path.parentFile
        }
        return path != base
    }

    /** Legacy single-destination extraction (used by tests / callers with one root). */
    fun extractZip(zipFile: File, destDir: File): Int =
        extractZipByPrefix(zipFile, emptyMap(), destDir)

    /**
     * [fix-R12 D8] Canonical prefix-boundary guard: target must equal base or
     * live under base + File.separator ("shared_evil" must not pass a "shared" check).
     */
    internal fun isUnderBase(baseCanonical: String, targetCanonical: String): Boolean =
        targetCanonical == baseCanonical ||
            targetCanonical.startsWith(baseCanonical + File.separator)
}
