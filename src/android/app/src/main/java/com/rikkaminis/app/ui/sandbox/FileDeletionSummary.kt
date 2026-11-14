package com.rikkaminis.app.ui.sandbox

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

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
 * [review-1114 A] The walk does NOT follow links, and the delete it predicts
 * does not either: both go through `Files.walkFileTree` without
 * `FOLLOW_LINKS`. A nested link is one entry (it is unlinked, not entered), so
 * a victim holding `own.txt` + `link -> outside(2 files)` answers **2**, not
 * the 4 the old `listFiles()` walk reported — and the old number was not just
 * wrong, it described the old *deletion*: it counted the outside files because
 * the delete would really have removed them. Count and removal share one
 * traversal rule for the same reason they share one predicate.
 *
 * Unreadable children contribute 0 rather than throwing — the caller is
 * painting a dialog, and a stat failure must not crash it; the real delete
 * reports its own failure afterwards.
 *
 * ponytail: single-threaded walk with no cap, called only from a directory
 * confirmation. | 天花板: a directory holding millions of entries would make
 * the dialog wait on the walk (the walk runs on IO, so the UI stays live but
 * the number arrives late). | 升级触发: someone opens this browser on a tree
 * that big — then cap the walk and label the count "N+".
 */
internal fun countEntriesUnder(item: FileItem): Int {
    if (!shouldDeleteRecursively(item.isDirectory, item.isSymlink)) return 0
    val root: Path = item.file.toPath()
    var count = 0
    return try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                // The dialog says "N items inside", so the directory the user
                // already named in the title never counts; every other
                // directory does (it is removed too).
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != root) count++
                    return FileVisitResult.CONTINUE
                }

                // Reached for plain files AND for symlinks (a link is not
                // descended into without FOLLOW_LINKS) — both cost one unlink.
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    count++
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: java.io.IOException,
                ): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        count
    } catch (_: Throwable) {
        // Missing / permission-denied root: answer with what was countable
        // instead of throwing at a dialog.
        count
    }
}
