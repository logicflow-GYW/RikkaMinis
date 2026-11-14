package com.rikkaminis.app.ui.sandbox

import java.io.File

/**
 * [rc-1114 U-06] How many entries a delete of [item] will remove from disk.
 *
 * The file browser used to hide the trash affordance for directories
 * (`if (!item.isDirectory)` at the row's action slot) while
 * [FileBrowserViewModel.deleteItem] already handled them via
 * `deleteRecursively()`. Opening that path without saying WHAT it removes would
 * turn a one-tap mis-tap into "the whole session workspace is gone", so the
 * confirmation dialog needs a number and this is it.
 *
 * Derived from [shouldDeleteRecursively] — the SAME predicate the delete
 * itself branches on — so the count and the removal can never disagree about
 * which shapes are recursive. A file or a symlink is unlinked by name and
 * removes nothing *inside* itself ⇒ 0.
 *
 * Traversal matches `File.deleteRecursively()`: `listFiles()` is followed, so a
 * nested symlink-to-directory is counted (and deleted) through its target,
 * exactly as the deletion will behave. Unreadable children contribute 0 rather
 * than throwing — the caller is painting a dialog, and a stat failure must not
 * crash it; the real delete reports its own failure afterwards.
 *
 * ponytail: single-threaded walk with no cap, called only from a directory
 * confirmation. | 天花板: a directory holding millions of entries would make
 * the dialog wait on the walk (the walk runs on IO, so the UI stays live but
 * the number arrives late). | 升级触发: someone opens this browser on a tree
 * that big — then cap the walk and label the count "N+".
 */
internal fun countEntriesUnder(item: FileItem): Int {
    if (!shouldDeleteRecursively(item.isDirectory, item.isSymlink)) return 0
    var count = 0
    val stack = ArrayDeque<File>()
    stack.addLast(item.file)
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        val children = dir.listFiles() ?: continue
        for (child in children) {
            count++
            if (child.isDirectory) stack.addLast(child)
        }
    }
    return count
}
