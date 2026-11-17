package com.rikkaminis.app.sandbox

import com.rikkaminis.app.logging.AppLogger
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
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

    val backingState = readBackingState(root, backing)
    if (backingState == BackingState.PRESENT &&
        (Files.isSymbolicLink(backing) || !Files.isDirectory(backing, NOFOLLOW_LINKS) || backing.parent != root)
    ) {
        throw IOException("Refusing to reset an unsafe L2S backing path: $backing")
    }
    // Only an authoritative "absent" may let the walk delete the whole tree --
    // see [readBackingState] for why a single `Files.exists` answer cannot be
    // trusted, and for why UNKNOWN is treated as PRESENT.
    val preserveBacking = backingState != BackingState.ABSENT
    if (backingState == BackingState.UNKNOWN) {
        AppLogger.warning(
            RESET_LOG_TAG,
            "reset cannot tell whether $backing exists; preserving it for this run",
        )
    }

    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (preserveBacking && dir == backing) FileVisitResult.SKIP_SUBTREE
            else FileVisitResult.CONTINUE

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            deleteRestoringOwnerWrite(file, scopeRoot = root)
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
                // [C1.2] `file` may BE the scope root: the walk fails on the
                // rootfs directory itself when its own mode bits deny read.
                // The subtree delete is told what the scope root is so it can
                // refuse to delete it (and refuse to chmod anything above it).
                deleteTreeRestoringOwnerAccess(
                    entry = file,
                    scopeRoot = root,
                    skipSubtree = if (preserveBacking) backing else null,
                )
            } else {
                deleteRestoringOwnerWrite(file, scopeRoot = root)
            }
            // Still CONTINUE, never TERMINATE: one entry that survives every
            // attempt must not make the install impossible, because the
            // extraction writes over the leftovers (see the note below).
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (preserveBacking && dir == backing) return FileVisitResult.CONTINUE
            if (dir != root) deleteRestoringOwnerWrite(dir, scopeRoot = root)
            return FileVisitResult.CONTINUE
        }
    })
}

/**
 * What the reset knows about `<rootfs>/.l2s-backing` before it walks.
 *
 * [UNKNOWN] is not a third fact about the filesystem, it is the absence of a
 * fact: the lstat failed for a reason other than ENOENT, so "there is nothing
 * there" has NOT been established.
 */
private enum class BackingState { ABSENT, PRESENT, UNKNOWN }

/**
 * Decide whether the L2S backing directory exists, without ever turning "I
 * could not look" into "it is not there".
 *
 * C1.1: `Files.exists` answers false both for ENOENT and for EACCES, and it
 * swallows the exception that tells them apart. The pre-flight check used to
 * read that single boolean, so a rootfs directory whose own mode bits deny
 * search (`chmod 000`, or a tarball that carried 0700 onto a directory the app
 * then lost the ability to search) made `.l2s-backing` look absent -- and an
 * absent backing is exactly the condition under which the walk deletes the
 * whole tree, i.e. every faked-hard-link target the user owns.
 *
 * The answer therefore has to come from a directory that can actually be
 * searched: on a non-ENOENT failure, grant the scope root the owner bits and
 * ask again. The rootfs directory belongs to the app by construction, so that
 * chmod is legitimate and stays inside the reset scope (see
 * [restoreOwnerAccess]).
 *
 * If the second probe is still inconclusive, the caller treats it as PRESENT:
 * skipping a `.l2s-backing` that turns out not to exist costs nothing, while
 * deleting one that does exist cannot be undone. Refusing the reset would be
 * the other safe answer, but it makes a reset permanently impossible on a tree
 * nobody can inspect, and the extraction would still not run.
 */
private fun readBackingState(root: Path, backing: Path): BackingState {
    val first = probeBacking(backing)
    if (first != BackingState.UNKNOWN) return first
    restoreOwnerAccess(root, needsTraversal = true, scopeRoot = root)
    return probeBacking(backing)
}

/**
 * [Files.readAttributes] instead of [Files.exists]: the exception type is the
 * distinction that matters. ENOENT means absent; anything else means the
 * question could not be answered.
 */
private fun probeBacking(backing: Path): BackingState = try {
    Files.readAttributes(backing, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    BackingState.PRESENT
} catch (_: NoSuchFileException) {
    BackingState.ABSENT
} catch (_: IOException) {
    BackingState.UNKNOWN
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
 * [scopeRoot] bounds every chmod this function can trigger: see
 * [restoreOwnerAccess] for why the parent fallback must not leave the reset
 * scope (the parent of the rootfs directory is a host directory holding far
 * more than the rootfs).
 *
 * @return true when [path] no longer exists.
 */
private fun deleteRestoringOwnerWrite(path: Path, scopeRoot: Path): Boolean {
    if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) return true
    val isDirectory = Files.isDirectory(path, NOFOLLOW_LINKS)
    restoreOwnerAccess(path, needsTraversal = isDirectory, scopeRoot = scopeRoot)
    path.parent?.let { parent -> restoreOwnerAccess(parent, needsTraversal = true, scopeRoot = scopeRoot) }
    if (runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) return true
    AppLogger.warning(
        RESET_LOG_TAG,
        "reset could not remove $path; the next extraction writes over it",
    )
    return false
}

/**
 * [C-F01] Remove [entry] and everything under it, assuming nothing about the
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
 * the scope root's direct child, and skipping it here keeps the 1116-CD
 * contract that a reset never touches the faked-hard-link targets.
 *
 * [scopeRoot] is the rootfs directory, and it is NEVER deleted here even when
 * [entry] IS the scope root -- which happens whenever the walk fails on the
 * rootfs directory itself. That is the same promise `postVisitDirectory` makes
 * on the normal path, and the whole reset contract depends on it: the caller
 * extracts the new rootfs INTO that directory, so deleting it leaves the
 * installer with nothing to write into. There is deliberately no flag to
 * override this; a scope root that must be removed is a different operation,
 * not a parameter of this one.
 *
 * Every path this function chmods is [scopeRoot] or below it (enforced in
 * [restoreOwnerAccess]), so a permission-damaged rootfs can never reach the
 * host directory that contains it.
 */
private fun deleteTreeRestoringOwnerAccess(entry: Path, scopeRoot: Path, skipSubtree: Path?) {
    val directories = ArrayList<Path>()
    val pending = ArrayList<Path>()
    pending.add(entry)
    while (pending.isNotEmpty()) {
        val current = pending.removeAt(pending.lastIndex)
        if (skipSubtree != null && current == skipSubtree) continue
        if (!Files.isDirectory(current, NOFOLLOW_LINKS)) {
            deleteRestoringOwnerWrite(current, scopeRoot)
            continue
        }
        restoreOwnerAccess(current, needsTraversal = true, scopeRoot = scopeRoot)
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
        if (current != scopeRoot) directories.add(current)
        for (child in children) pending.add(child)
    }
    // Children were pushed after their parent, so the reverse order removes the
    // deepest directories first.
    for (index in directories.indices.reversed()) deleteRestoringOwnerWrite(directories[index], scopeRoot)
}

/**
 * Grant the owner the bits needed to inspect and unlink [path].
 *
 * [needsTraversal] is true for directories, which additionally need `r` to be
 * listed and `x` to be descended into. A symlink is left alone: chmod on a link
 * follows it out of the rootfs, and a link never needs its own bits to be
 * unlinked.
 *
 * [scopeRoot] is the only directory this function may ever touch, plus anything
 * below it. That guard is not defensive decoration: the parent fallback in
 * [deleteRestoringOwnerWrite] runs for the entry that could not be removed, so
 * when that entry IS the scope root the fallback would chmod
 * `rootfsDir.parent` -- the app's private files directory, which holds the
 * databases, prefs and every other session's data. A reset of the rootfs has no
 * business rewriting the mode of a directory it does not own, and a damaged
 * rootfs must not be able to escalate into one. Chmod-ing the scope root itself
 * stays allowed: the app owns it by construction and it has to be searchable
 * before anything inside it can be removed.
 *
 * Paths outside the scope are refused silently (not even a warning): they are
 * reached only as a side effect of a retry that has already failed, and the
 * failure itself is logged by the caller.
 */
private fun restoreOwnerAccess(path: Path, needsTraversal: Boolean, scopeRoot: Path) {
    if (Files.isSymbolicLink(path)) return
    if (path != scopeRoot && !path.startsWith(scopeRoot)) return
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
