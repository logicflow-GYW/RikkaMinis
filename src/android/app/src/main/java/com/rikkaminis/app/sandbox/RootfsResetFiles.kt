package com.rikkaminis.app.sandbox

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Clear a rootfs while retaining L2S targets named by host-side bind mounts. */
internal fun clearRootfsForReinstall(rootfsDir: File) {
    val root = rootfsDir.absoluteFile.toPath().normalize()
    val backing = root.resolve(L2S_BACKING_DIR_NAME)
    if (Files.isSymbolicLink(root)) throw IOException("Rootfs path is a symlink: $rootfsDir")
    if (!Files.exists(root, NOFOLLOW_LINKS)) {
        if (!rootfsDir.mkdirs() && !rootfsDir.isDirectory) {
            throw IOException("Could not create rootfs directory: $rootfsDir")
        }
        return
    }
    if (!Files.isDirectory(root, NOFOLLOW_LINKS)) {
        throw IOException("Rootfs path is not a directory: $rootfsDir")
    }

    val preserveBacking = Files.exists(backing, NOFOLLOW_LINKS)
    if (preserveBacking &&
        (Files.isSymbolicLink(backing) || !Files.isDirectory(backing, NOFOLLOW_LINKS) || backing.parent != root)
    ) {
        throw IOException("Refusing to reset an unsafe L2S backing path: $backing")
    }

    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (preserveBacking && dir == backing) FileVisitResult.SKIP_SUBTREE
            else FileVisitResult.CONTINUE

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            deleteRestoringOwnerWrite(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            // A directory whose mode bits deny read cannot be walked into, but
            // it can still be removed by name once its parent is writable.
            deleteRestoringOwnerWrite(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (preserveBacking && dir == backing) return FileVisitResult.CONTINUE
            if (dir != root) deleteRestoringOwnerWrite(dir)
            return FileVisitResult.CONTINUE
        }
    })
}

/**
 * Delete [path], restoring the owner-write bit when the tree says no.
 *
 * [incr1116-CD F-04] The reset walk replaced `deleteRecursively()` (which
 * returned false and moved on) with `Files.delete`, which THROWS — so a single
 * read-only directory that came out of an archive (`chmod -R a-w` in a build,
 * or a tarball that preserved 0555) aborted the walk half-way AND failed the
 * install. Retrying the install could never get past it: the tree is still
 * there, still read-only, and the app owns every inode — nothing else will
 * ever make it writable.
 *
 * The app owns this tree by construction, so adding `u+w` to the parent (or to
 * the entry itself, for a directory whose children must be unlinked) is
 * legitimate; deleting through a symlink is not needed and is not done — the
 * walk runs without FOLLOW_LINKS, so a link arrives here as a plain file.
 *
 * A failure that survives both attempts is not fatal: the extraction writes
 * over the leftovers, and the integrity manifest is rewritten afterwards, so
 * refusing to install at all is the worse outcome.
 */
private fun deleteRestoringOwnerWrite(path: Path) {
    if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) return
    runCatching { path.toFile().setWritable(true, true) }
    path.parent?.let { parent -> runCatching { parent.toFile().setWritable(true, true) } }
    runCatching { Files.deleteIfExists(path) }
}

internal const val L2S_BACKING_DIR_NAME = ".l2s-backing"

/**
 * [incr1116-CD F-01] The name prefix link2symlink gives the file it keeps
 * behind a faked hard link (`PREFIX` in `link2symlink.c`, non-USERLAND build).
 */
internal const val L2S_FILE_PREFIX = ".l2s."

/**
 * True when [resolvedPath] is one of the extension's own backing files, i.e.
 * `<something>/.l2s-backing/.l2s.<name><n>.<n>`.
 *
 * This is the shape a faked hard link RESOLVES to. `safeResolveWithin` refuses
 * anything that canonicalises outside its base, which is right for an escape
 * and wrong for these: a guest file the shell hard-linked is a symlink straight
 * into this directory, so every host-side tool that resolved guest paths
 * (`file_read`/`file_edit`, the browser, chat link previews) turned the file
 * into "not found" while the guest could still `cat` it.
 *
 * Structural, not a config lookup: both segments are the extension's own
 * constants, and the check stays testable without a Context.
 */
internal fun isL2sBackingTarget(resolvedPath: String): Boolean {
    if (resolvedPath.isEmpty()) return false
    val separator = java.io.File.separatorChar
    val name = resolvedPath.substringAfterLast(separator)
    if (!name.startsWith(L2S_FILE_PREFIX)) return false
    val parent = resolvedPath.substringBeforeLast(separator, "")
    return parent.substringAfterLast(separator) == L2S_BACKING_DIR_NAME
}
