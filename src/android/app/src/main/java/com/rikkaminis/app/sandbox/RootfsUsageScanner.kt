package com.rikkaminis.app.sandbox

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File

/**
 * Computes the REAL on-disk footprint of the terminal rootfs
 * (filesDir/alpine-rootfs) for the Storage screen.
 *
 * The old implementation (dir.walkTopDown() + File.isFile + File.length())
 * made the "Terminal Shell" row read far larger than the actual disk usage:
 *
 *  1. File.isFile()/File.length() FOLLOW symlinks — versioned .so symlinks
 *     (e.g. /usr/lib/llvm19/libLLVM.so.19.1, 144MB, referenced from three
 *     paths) were counted once per link.
 *  2. FileTreeWalk also recurses into symlinked DIRECTORIES
 *     (e.g. /usr/lib/jvm/default-jvm -> java-17-openjdk), double-counting
 *     their contents.
 *  3. File.length() returns LOGICAL size; sparse files were counted at
 *     their nominal size instead of the blocks they really occupy.
 *
 * This scanner uses lstat (never follows links), sums st_blocks*512 (real
 * allocated bytes) and dedupes hard links by (st_dev, st_ino). The [Stat]
 * function is pluggable so the walk logic itself is JVM-testable.
 */
object RootfsUsageScanner {

    data class Node(
        val dedupeKey: Long?,
        val bytes: Long,
        val isDirectory: Boolean,
    )

    fun interface Stat {
        fun stat(path: String): Node?
    }

    data class Entry(val name: String, val bytes: Long)

    data class Report(val totalBytes: Long, val entries: List<Entry>)

    /** Android-backed stat: lstat (no follow) + st_blocks*512. */
    fun androidStat(): Stat = Stat { path ->
        try {
            val st: StructStat = Os.lstat(path)
            Node(
                dedupeKey = (st.st_dev shl 32) xor st.st_ino,
                bytes = st.st_blocks * 512L,
                isDirectory = OsConstants.S_ISDIR(st.st_mode),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Iterative DFS without following symlinks. Files are attributed to the
     * top-level directory they live under (tmp, usr, root, ...) so the
     * Storage screen can show where the space went.
     */
    fun scan(root: File, stat: Stat): Report {
        if (!root.exists()) return Report(0L, emptyList())
        val seen = HashSet<Long>()
        val totals = LinkedHashMap<String, Long>()
        var total = 0L
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(root to "")
        while (stack.isNotEmpty()) {
            val (dir, top) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                val node = stat.stat(child.absolutePath) ?: continue
                if (node.isDirectory) {
                    stack.addLast(child to (if (top.isEmpty()) child.name else top))
                } else {
                    val key = node.dedupeKey
                    if (key != null && !seen.add(key)) continue
                    val bucket = if (top.isEmpty()) child.name else top
                    totals[bucket] = (totals[bucket] ?: 0L) + node.bytes
                    total += node.bytes
                }
            }
        }
        val entries = totals.entries
            .sortedByDescending { it.value }
            .map { Entry(it.key, it.value) }
        return Report(total, entries)
    }
}
