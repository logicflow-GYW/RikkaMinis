package com.rikkaminis.app.data.repository

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * [fix/r3-data-1119 F-08 / F-09] The ONE restricted-traversal primitive for
 * the skills tree.
 *
 * Both findings were the same bug wearing two hats: the skills directory is a
 * shared mount the user and the agent can write into (a `git clone` inside it,
 * a hand-made symlink), and the code that walked it trusted the filesystem
 * instead of checking it.
 *
 *  - F-08: `delete()` used `File.deleteRecursively()`, which follows a
 *    directory symlink and deletes the tree it points at — so deleting a
 *    skill with a `ref -> ../../memory` link inside it wiped the user's real
 *    memory directory. Irreversible.
 *  - F-09: `listFilesUnder()` used `File.walkTopDown()`, which follows
 *    symlinks and has no cycle detection — a `loop -> .` link inside a skill
 *    turned the walk into an infinite descent (OOM), and a link pointing
 *    outside the skill root let `exportSkillToZip` pack private files into a
 *    shareable archive.
 *
 * The three rules below are the ones `security-audit-checklist` calls the
 * triad, implemented ONCE so the two call sites cannot drift:
 *
 *  1. **canonicalize** — every path is resolved through
 *     `File.getCanonicalPath()`, so `..` and absolute prefixes are folded
 *     away before anything is compared.
 *  2. **prefix guard** — a path is in scope only when its canonical form is
 *     the root itself or starts with `rootCanonical + File.separator`. A
 *     string `startsWith` on the raw path is not enough: `/root/ab` is not
 *     inside `/root/a`.
 *  3. **fail closed** — a path whose canonical form cannot be computed, or
 *     that lands outside the root, is SKIPPED, never "handled optimistically".
 *
 * Symlinks are never followed: a link is deleted as a link (F-08) and is
 * never descended into nor listed (F-09). The visited set makes a cycle a
 * no-op instead of a hang.
 *
 * Deliberately Android-free (`java.io` / `java.nio.file` only) so both the
 * delete path and the listing path are unit-testable on a plain JVM.
 */
internal object SkillTreeGuard {

    /**
     * Depth limit for the walk. A skill tree deeper than this is not a skill
     * anyone ships; the limit is a hard stop so a pathological tree cannot
     * turn into an unbounded descent even if the visited set is defeated.
     */
    const val MAX_DEPTH = 64

    /** True when [file] itself is a symbolic link (never its target). */
    fun isSymbolicLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * Canonical path of [file], or null when it cannot be resolved
     * (fail closed — a path we cannot canonicalize is a path we do not touch).
     */
    fun canonicalOrNull(file: File): String? =
        runCatching { file.canonicalPath }.getOrNull()

    /**
     * True when [canonical] is [rootCanonical] itself or lives under it. The
     * separator is appended to the root so `/root/ab` is NOT considered
     * inside `/root/a`.
     */
    fun isWithinRoot(canonical: String, rootCanonical: String): Boolean =
        canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)

    /**
     * The restricted walk: every regular file under [root], as paths relative
     * to [root], separated by `/`.
     *
     * Rules (all three of the triad):
     *  - symlinks are neither descended into nor listed (a link to a file
     *    outside the root would otherwise be packed into an export);
     *  - a cycle terminates (the visited set holds canonical directories);
     *  - a directory that resolves outside [root] is skipped entirely.
     *
     * Hidden entries (a leading dot) are skipped — they are host bookkeeping
     * (`.DS_Store`, editor swap files), never skill payload.
     *
     * Returns an empty list when [root] is not a directory: that is how the
     * callers distinguish "no skill dir" from "an empty one".
     */
    fun listFilesUnder(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        val rootCanonical = canonicalOrNull(root) ?: return emptyList()
        val out = ArrayList<String>()
        val visited = HashSet<String>()
        walk(root, rootCanonical, "", 0, visited, out)
        return out
    }

    private fun walk(
        dir: File,
        rootCanonical: String,
        prefix: String,
        depth: Int,
        visited: MutableSet<String>,
        out: MutableList<String>,
    ) {
        if (depth > MAX_DEPTH) return
        val dirCanonical = canonicalOrNull(dir) ?: return
        if (!isWithinRoot(dirCanonical, rootCanonical)) return
        if (!visited.add(dirCanonical)) return   // cycle
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (isSymbolicLink(child)) continue    // never followed, never listed
            val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            val childCanonical = canonicalOrNull(child) ?: continue
            if (!isWithinRoot(childCanonical, rootCanonical)) continue
            if (child.isDirectory) {
                walk(child, rootCanonical, rel, depth + 1, visited, out)
            } else if (child.isFile) {
                out.add(rel)
            }
        }
    }

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

    /**
     * True when [target] resolves inside [root]. Used by the callers that must
     * not act on a path outside the skills tree.
     */
    fun isInside(root: File, target: File): Boolean {
        val rootCanonical = canonicalOrNull(root) ?: return false
        val targetCanonical = canonicalOrNull(target) ?: return false
        return isWithinRoot(targetCanonical, rootCanonical)
    }

    /** True when the path exists as a link, even when its target is gone. */
    fun existsAsLink(file: File): Boolean =
        runCatching { Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) }
            .getOrDefault(false)
}
