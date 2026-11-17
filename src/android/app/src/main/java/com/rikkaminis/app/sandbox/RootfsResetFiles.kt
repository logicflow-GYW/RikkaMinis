package com.rikkaminis.app.sandbox

import com.rikkaminis.app.logging.AppLogger
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
            // [C-F01] A directory whose mode bits deny read/traverse cannot be
            // walked into, so `visitFile`/`postVisitDirectory` never fire for
            // anything inside it. The 1116-CD F-04 fix did reach this branch and
            // restored `u+w` -- enough to unlink an EMPTY directory, not enough
            // for a non-empty one: `Files.delete` then throws
            // DirectoryNotEmptyException, the `runCatching` swallows it, and the
            // reset reports success with the whole subtree still on disk. Hand
            // the subtree to a delete that restores `rwx` and walks it itself.
            // The backing directory keeps its 1116-CD semantics: never entered.
            if (preserveBacking && file == backing) return FileVisitResult.CONTINUE
            if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
                deleteTreeRestoringOwnerAccess(file, skipSubtree = if (preserveBacking) backing else null)
            } else {
                deleteRestoringOwnerWrite(file)
            }
            // Still CONTINUE, never TERMINATE: one entry that survives every
            // attempt must not make the install impossible, because the
            // extraction writes over the leftovers (see the note below).
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
 * walk runs without FOLLOW_LINKS, so a link arrives here as a plain file, and
 * a link is unlinked through its parent without ever being chmod-ed (chmod
 * follows the link, which would reach outside the rootfs).
 *
 * [C-F01] A directory that cannot be READ still needs `u+rx` on top of `u+w`
 * before its entries can be listed and unlinked; [deleteTreeRestoringOwnerAccess]
 * does that, and this helper is the leaf case (a file, or a directory that is
 * already empty).
 *
 * A failure that survives both attempts is not fatal: the extraction writes
 * over the leftovers, and the integrity manifest is rewritten afterwards, so
 * refusing to install at all is the worse outcome. It is not silent either —
 * the entry is logged, so a reset that leaves something behind is traceable
 * instead of looking like a clean run.
 *
 * @return true when [path] no longer exists.
 */
private fun deleteRestoringOwnerWrite(path: Path): Boolean {
    if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) return true
    val isDirectory = Files.isDirectory(path, NOFOLLOW_LINKS)
    restoreOwnerAccess(path, needsTraversal = isDirectory)
    path.parent?.let { parent -> restoreOwnerAccess(parent, needsTraversal = true) }
    if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) return true
    AppLogger.warning(
        RESET_LOG_TAG,
        "reset could not remove $path; the next extraction writes over it",
    )
    return false
}

/**
 * [C-F01] Remove [root] and everything under it, assuming nothing about the
 * permissions inside: a directory reached through `visitFileFailed` is one the
 * walk could not even list, so its children were never visited and it can be
 * arbitrarily non-empty and arbitrarily unreadable.
 *
 * Depth-first with an explicit stack (a rootfs is deep enough that recursion is
 * a liability, and this path only runs on permission damage, so the simple
 * shape wins over anything clever). Directories are deleted after their
 * contents, deepest first, because unlinking an entry needs a writable parent.
 *
 * Symbolic links are never followed: [NOFOLLOW_LINKS] makes a link look like a
 * plain file, it is unlinked as itself, and [restoreOwnerAccess] refuses to
 * chmod it (chmod follows links).
 *
 * [skipSubtree] is the L2S backing directory when it must be preserved; it is
 * [root]'s direct child, and skipping it here keeps the 1116-CD contract that a
 * reset never touches the faked-hard-link targets.
 */
private fun deleteTreeRestoringOwnerAccess(root: Path, skipSubtree: Path?) {
    val directories = ArrayList<Path>()
    val pending = ArrayList<Path>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val current = pending.removeAt(pending.lastIndex)
        if (skipSubtree != null && current == skipSubtree) continue
        if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
            deleteRestoringOwnerWrite(current)
            continue
        }
        restoreOwnerAccess(current, needsTraversal = true)
        val children = runCatching {
            Files.newDirectoryStream(current).use { stream -> stream.toList() }
        }.getOrNull()
        if (children == null) {
            // Not fatal, but never silent: this is the one case where the reset
            // leaves something behind after trying everything it can.
            AppLogger.warning(
                RESET_LOG_TAG,
                "reset could not list $current even after restoring owner access; leaving it to the extraction",
            )
            continue
        }
        directories.add(current)
        for (child in children) pending.add(child)
    }
    // Children were pushed after their parent, so the reverse order removes the
    // deepest directories first.
    for (index in directories.indices.reversed()) deleteRestoringOwnerWrite(directories[index])
}

/**
 * Grant the owner the bits needed to inspect and unlink [path].
 *
 * [needsTraversal] is true for directories, which additionally need `r` to be
 * listed and `x` to be descended into. A symlink is left alone: chmod on a link
 * follows it out of the rootfs, and a link never needs its own bits to be
 * unlinked.
 */
private fun restoreOwnerAccess(path: Path, needsTraversal: Boolean) {
    if (Files.isSymbolicLink(path)) return
    val file = path.toFile()
    runCatching { file.setWritable(true, true) }
    if (!needsTraversal) return
    runCatching { file.setReadable(true, true) }
    runCatching { file.setExecutable(true, true) }
}

private const val RESET_LOG_TAG = "RootfsReset"

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
