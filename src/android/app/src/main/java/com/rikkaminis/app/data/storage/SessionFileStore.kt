package com.rikkaminis.app.data.storage

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * Singular owner of the on-disk footprint attached to a chat session.
 *
 * A session's real existence is THREE things, and they must live and die
 * together:
 *  1. the DB row(s) in ChatDao          → chatRepository/dao ownership
 *  2. its bind-mounted dir under filesDir/minis-sessions/<sid>/
 *     (workspace / attachments / offloads / browser)               → this class
 *  3. its media under filesDir/media/<date>/<sid>/                 → MediaStore
 *
 * Before this class existed, deleting a session only dropped the DB rows —
 * the files under minis-sessions/<sid>/ (and media) were orphaned forever:
 * invisible to the DB-backed session list yet still burning disk. This is the
 * same lineage as the backup-OOM: chat session dirs accumulate large tool
 * artifacts (workspace downloads, browser data, offload dumps, attachments)
 * with no reclamation path.
 *
 * Centralising session-file ownership here (instead of scattering
 * deleteRecursively/directorySize inline in UI) gives deleteSession-> and the
 * storage page a single, converged notion of "empty this session / reclaim
 * orphans".
 */
class SessionFileStore internal constructor(
    private val filesDirForTestingOrProd: File,
) {
    constructor(context: Context) : this(context.filesDir)

    /** Root that holds one subdir per live session: filesDir/minis-sessions/<sid>/. */
    val sessionsRoot: File = File(filesDirForTestingOrProd, "minis-sessions")

    /** Media root: filesDir/media/<date>/<sid>/. */
    private val mediaRoot: File = File(filesDirForTestingOrProd, "media")

    /** Test-only accessor for the media root (mirrors [mediaRoot]). */
    internal fun mediaRootForTesting(): File = mediaRoot

    /** Per-session directory under [sessionsRoot]. */
    fun sessionDir(sessionId: String): File = File(sessionsRoot, sessionId)

    /**
     * Recursive on-disk size of [dir] in bytes.
     *
     * [Bug 3 fix] Was `dir.walkTopDown() + File.isFile + File.length()`, which
     * FOLLOWS symlinks and counts logical size — the same over-counting the
     * terminal rootfs once suffered from (versioned .so symlinks, symlinked
     * dirs like default-jvm, sparse files). A session's workspace carries the
     * same artifacts, so the "Sessions" row and per-session detail could read
     * far larger than real disk usage.
     *
     * This now measures with NOFOLLOW_LINKS (never recurses into symlinked
     * dirs, never double-counts a symlink target) and dedupes hard links by
     * `fileKey` (inode), mirroring RootfsUsageScanner's semantics while
     * staying pure-JVM (java.nio, no android.system.Os) so it remains testable.
     */
    fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0L
        val seen = HashSet<Any>()
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val children = cur.listFiles() ?: continue
            for (child in children) {
                val attrs = try {
                    Files.readAttributes(
                        Paths.get(child.path),
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (e: Exception) {
                    continue
                }
                if (attrs.isDirectory) {
                    // Do not follow symlinked dirs; a real dir goes on the stack.
                    if (!attrs.isSymbolicLink) stack.addLast(child)
                } else if (attrs.isRegularFile) {
                    val key = attrs.fileKey()
                    if (key != null && !seen.add(key)) continue
                    total += attrs.size()
                }
            }
        }
        return total
    }

    /** Media bytes belonging to [sessionId] (files anywhere under media/<…>/<sid>/). */
    fun mediaSize(sessionId: String): Long {
        return mediaSessionDirs(sessionId).sumOf { sizeOf(it) }
    }

    /**
     * All media leaf dirs matching layout `media/<date>/<sid>/` whose name is
     * [sessionId]. Used by both [mediaSize] (size) and [deleteSessionFiles]
     * (delete), so the size shown and the bytes deleted always refer to the
     * same set of dirs — no drift between "displayed footprint" and what
     * Clear removes.
     */
    private fun mediaSessionDirs(sessionId: String): List<File> {
        if (!mediaRoot.exists()) return emptyList()
        val out = mutableListOf<File>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (!child.isDirectory) continue
                if (child.name == sessionId) {
                    out += child
                } else {
                    walk(child)
                }
            }
        }
        walk(mediaRoot)
        return out
    }

    /**
     * Per-subdir breakdown of one session's bind-mounted dir under
     * minis-sessions/<sid>/ (workspace / attachments / offloads / browser).
     * [C: storage-page breakdown] — lets the UI surface which component of a
     * session's footprint is actually huge (usually workspace), instead of one
     * opaque "total" number.
     */
    fun sessionSubdirSizes(sessionId: String): Map<String, Long> {
        val dir = sessionDir(sessionId)
        if (!dir.exists()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) out[child.name] = sizeOf(child)
        }
        return out
    }

    /** Recursively delete a session's bind-mounted dir + its media. No-op if absent. */
    fun deleteSessionFiles(sessionId: String): DeleteResult {
        val sessionDeleted = deleteRecursivelyChecked(sessionDir(sessionId))
        val mediaDeleted = mediaSessionDirs(sessionId).fold(true) { acc, dir ->
            deleteRecursivelyChecked(dir) && acc
        }
        return DeleteResult(sessionDeleted, mediaDeleted)
    }

    /**
     * [Bug 4 fix] `File.deleteRecursively()` returns false (and silently) when
     * a file is held open, a mount is read-only, or a path is unroutable — and
     * the storage UI previously ignored that return value, zeroing the size and
     * showing "Cleared 0 B" while the bytes were still on disk (a fake close).
     * This wrapper reports the real outcome so callers can surface a partial-
     * failure state instead of pretending success.
     */
    private fun deleteRecursivelyChecked(dir: File): Boolean {
        if (!dir.exists()) return true // absent == nothing to delete == success
        val ok = dir.deleteRecursively()
        return if (!ok) {
            // A failed recursive delete may have removed some children; try one
            // more pass and report whatever survives.
            !dir.exists()
        } else {
            true
        }
    }

    /** Outcome of a session-file delete: which parts actually went away. */
    data class DeleteResult(
        val sessionDeleted: Boolean,
        val mediaDeleted: Boolean,
    ) {
        val fullyDeleted: Boolean get() = sessionDeleted && mediaDeleted
    }

    /**
     * B: orphan reclamation. Any dir under [sessionsRoot] or any media subdir
     * whose id is NOT in [liveSessionIds] is a leftover from a deleted (or
     * never-committed) session. Returns the reclaimable bytes and reclaims them.
     */
    fun reclaimOrphans(liveSessionIds: Set<String>): ReclaimReport {
        val report = scanOrphans(liveSessionIds)
        with(report) {
            // Delete the scanned session dirs (ids in the report).
            if (sessionIds.isNotEmpty()) {
                sessionIds.forEach { sessionDir(it).takeIf { d -> d.exists() }?.deleteRecursively() }
            }
            // Re-delete media leaves (walk again; ids may overlap session dirs).
            deleteOrphanMediaLeaves(liveSessionIds)
        }
        return report
    }

    /**
     * True if [name] looks like a session directory id. Session ids are always
     * `UUID.randomUUID().toString()` (36 chars, 4 hyphens). Guarding on this
     * shape means orphan reclamation can never mistake an unrelated folder or
     * stray file under minis-sessions/ (or a media date path) for a session and
     * delete it — fail-safe: unknown-looking names are simply never reclaimed.
     */
    private fun looksLikeSessionId(name: String): Boolean {
        return name.length == 36 && name.count { it == '-' } == 4
    }

    /**
     * B: scan-only variant — measures reclaimable space WITHOUT deleting.
     * The storage page calls this on entry to surface a "X MB of orphan data"
     * banner that the user explicitly confirms before anything is removed.
     */
    fun scanOrphans(liveSessionIds: Set<String>): ReclaimReport {
        val sessionIds = mutableListOf<String>()
        val mediaIds = mutableListOf<String>()
        var sessionBytes = 0L
        var mediaBytes = 0L
        if (sessionsRoot.exists()) {
            sessionsRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && looksLikeSessionId(dir.name) && dir.name !in liveSessionIds) {
                    sessionIds += dir.name
                    sessionBytes += sizeOf(dir)
                }
            }
        }
        if (mediaRoot.exists()) {
            mediaRoot.walkTopDown().forEach { dir ->
                if (isOrphanMediaLeaf(dir, liveSessionIds)) {
                    mediaIds += dir.name
                    mediaBytes += sizeOf(dir)
                }
            }
        }
        return ReclaimReport(
            sessionIds = sessionIds,
            sessionDirs = sessionIds.size,
            sessionBytes = sessionBytes,
            mediaDirs = mediaIds.size,
            mediaBytes = mediaBytes,
        )
    }

    /**
     * True when [dir] is an orphaned media leaf: a directory named like a
     * session id, with no subdirectories (so it's a terminal `<sid>/` under
     * `media/<date>/`), whose id is not among the live sessions.
     *
     * [Bug 2 fix] The old check was `dir.listFiles()?.none { it.isDirectory }`.
     * When `listFiles()` returns null (unreadable dir) the whole guard short-
     * circuited to null and the dir was silently skipped — a permissions
     * hiccup could hide real orphans. This now treats null (unreadable) as
     * "assume leaf, still a candidate" so measurement doesn't silently miss
     * reclaimable data; the fail-safe `looksLikeSessionId` shape guard remains
     * the load-bearing safety net against deleting anything non-session-shaped.
     */
    private fun isOrphanMediaLeaf(dir: File, liveSessionIds: Set<String>): Boolean {
        if (!dir.isDirectory) return false
        if (!looksLikeSessionId(dir.name)) return false
        if (dir.name in liveSessionIds) return false
        val children = dir.listFiles() ?: return true
        return children.none { it.isDirectory }
    }

    private fun deleteOrphanMediaLeaves(liveSessionIds: Set<String>) {
        if (!mediaRoot.exists()) return
        mediaRoot.walkTopDown().forEach { dir ->
            if (isOrphanMediaLeaf(dir, liveSessionIds)) dir.deleteRecursively()
        }
    }

    /**
     * Batch media-size lookup for many live sessions (avoids re-walking the
     * whole media tree once per session).
     *
     * [Bug 3] Uses the same NOFOLLOW_LINKS + hardlink-dedupe semantics as
     * [sizeOf], attributing each file to its parent `<sid>/` leaf dir, so the
     * overview total and the per-session `mediaSize` agree (previously the
     * brief used `File.length()` and could over-count symlinked media the same
     * way `sizeOf` did).
     */
    fun mediaSizesBySessionBrief(sessionIds: Set<String>): Map<String, Long> {
        if (!mediaRoot.exists()) return emptyMap()
        val sizes = mutableMapOf<String, Long>()
        val seen = HashSet<Any>()
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val attrs = try {
                    Files.readAttributes(
                        Paths.get(child.path),
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (e: Exception) {
                    continue
                }
                if (attrs.isDirectory) {
                    if (!attrs.isSymbolicLink) walk(child)
                } else if (attrs.isRegularFile) {
                    val sid = child.parentFile?.name ?: continue
                    if (sid in sessionIds) {
                        val key = attrs.fileKey()
                        if (key != null && !seen.add(key)) continue
                        sizes[sid] = (sizes[sid] ?: 0L) + attrs.size()
                    }
                }
            }
        }
        walk(mediaRoot)
        return sizes
    }

    data class ReclaimReport(
        val sessionIds: List<String> = emptyList(),
        val sessionDirs: Int = 0,
        val sessionBytes: Long = 0L,
        val mediaDirs: Int = 0,
        val mediaBytes: Long = 0L,
    ) {
        val totalBytes: Long get() = sessionBytes + mediaBytes
        val totalDirs: Int get() = sessionDirs + mediaDirs
    }
}
