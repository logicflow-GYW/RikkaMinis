package com.rikkaminis.app.ui.sandbox

import java.io.File

/**
 * [R4-V F-11 / B13 F-11] Resolve the directory-listing entry a symlink stands
 * for, using the browser's own guest→host mapper for absolute targets.
 *
 * Two defects this fixes, both measured on the JVM (report §3, arm A/C):
 *
 *  - **PRoot writes the path the SHELL sees.** A link created inside the
 *    container stores `/var/minis/shared`, `/usr/bin/…` — not a host path. The
 *    Android host root has no `/var/minis`, so reading the raw target made
 *    every container-absolute link read as a **0-byte non-directory**: the row
 *    lost its folder affordance and the entry could not be opened at all.
 *  - **An unmappable target must stay "unknown", not become a file.** When
 *    neither the guest mapping nor the host path exists, the function returns
 *    the LINK ITSELF (`isDirectory == false`), which is the refuse-instead-of-
 *    guess answer: the UI shows a plain entry and the delete path unlinks one
 *    name instead of touching a tree it cannot name.
 *
 * [linkTarget] is the raw text `Files.readSymbolicLink` returned (`null` when
 * the link could not be read — same fail-closed answer). The function is pure
 * except for the [resolveGuestAbsolute] callback, so the JVM test source set
 * can pin every arm without an Android `Context`.
 */
internal fun resolveListedEntry(
    file: File,
    linkTarget: String?,
    resolveGuestAbsolute: (String) -> File?,
): File {
    if (linkTarget == null) return file
    return try {
        val target = File(linkTarget)
        if (target.isAbsolute) {
            resolveGuestAbsolute(linkTarget) ?: file
        } else {
            val parent = file.parentFile ?: return file
            File(parent, linkTarget)
        }
    } catch (_: Exception) {
        file
    }
}

/**
 * [R4-V F-11] May this entry be removed with `deleteRecursively()`?
 *
 * A symlink is never a directory to delete *through*. Kotlin's
 * `File.deleteRecursively()` walks the resolved tree (`FileTreeWalk` →
 * `File.listFiles()`, which follows the link) and then deletes what it found,
 * so a directory-shaped link wiped the link's TARGET — measured in the report's
 * §3 arm A: two files under the target before the call, zero after, target
 * directory itself left behind. `File.delete()` on the same link removes only
 * the name (arm B).
 *
 * Kept as a named predicate (not an inline `&&`) because the rule is what the
 * delete site must be graded on, and because the two booleans come from
 * different sources: [isDirectory] is the RESOLVED truth used for display,
 * [isSymlink] is the lstat truth about the entry itself.
 */
internal fun shouldDeleteRecursively(isDirectory: Boolean, isSymlink: Boolean): Boolean =
    isDirectory && !isSymlink
