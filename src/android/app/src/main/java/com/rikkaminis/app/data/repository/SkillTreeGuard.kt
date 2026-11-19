package com.rikkaminis.app.data.repository

import java.io.File
import java.nio.file.Files

/**
 * [fix/r3-data-1119 F-08] Deleting a skill must not follow a symlink.
 *
 * The skills directory is a shared mount the user and the agent can write
 * into (`git clone` inside it, a hand-made link). `delete()` used
 * `File.deleteRecursively()`, which follows a directory symlink and deletes
 * the tree it points at — so deleting a skill holding `ref -> ../../memory`
 * wiped the user's real memory directory, irreversibly.
 *
 * Rule: a symlink is deleted AS a link. The file it points at is never
 * touched, no matter where it lives.
 *
 * Deliberately Android-free (`java.io` / `java.nio.file` only) so it is
 * unit-testable on a plain JVM.
 */
internal object SkillTreeGuard {

    /** True when [file] itself is a symbolic link (never its target). */
    fun isSymbolicLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * Delete [root]'s tree WITHOUT following symlinks.
     *
     * A symlink is deleted as a link — the file it pointed at is never
     * touched. A regular file is deleted directly. A directory is emptied
     * first (children first, post-order) and then removed.
     *
     * Returns true when the tree is gone (or was never there).
     */
    fun deleteTree(root: File): Boolean {
        if (isSymbolicLink(root)) {
            // The link itself, never its target.
            return runCatching { root.delete() }.getOrDefault(false)
        }
        if (!root.exists()) return true
        if (!root.isDirectory) {
            return runCatching { root.delete() }.getOrDefault(false)
        }
        var ok = true
        val children = root.listFiles() ?: return false
        for (child in children) {
            if (!deleteTree(child)) ok = false
        }
        if (!runCatching { root.delete() }.getOrDefault(false)) ok = false
        return ok && !root.exists()
    }
}
