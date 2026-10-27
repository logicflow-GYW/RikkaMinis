package com.rikkaminis.app.data.storage

import android.content.Context
import com.rikkaminis.app.data.ComposerDraftStore
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * [da1040-A3] Draft-session alias prefix minted by [ComposerDraftStore]
 * (`__new__<uuid>` — the same 36-char UUID shape behind a fixed prefix).
 *
 * Single constant on purpose: the shape predicate below is the ONLY place that
 * knows the draft namespace. The old call-site `startsWith("__new__")` copy is
 * how four of five orphan-sweep callers kept the narrower notion of
 * "session-shaped" and deleted an active draft's files.
 */
private const val DRAFT_ID_PREFIX = "__new__"

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
    /**
     * [da1040-A3] Ids that already own on-disk state but have NO DB row yet:
     * the composer's active draft slot (`__new__<uuid>`, minted by
     * [ComposerDraftStore.nextDraftId] before the first message is sent).
     *
     * Threaded in as a *provider* rather than a snapshot so a scan sees the
     * slot as it is at call time (the slot is re-minted/released while the app
     * runs). Empty by default: JVM tests and any non-Android construction keep
     * the DB-only behaviour.
     */
    private val activeDraftIds: () -> Set<String> = { emptySet() },
) {
    constructor(context: Context) : this(
        context.filesDir,
        { ComposerDraftStore.activeDraftIds(context) },
    )

    /** Root that holds one subdir per live session: filesDir/minis-sessions/<sid>/. */
    val sessionsRoot: File = File(filesDirForTestingOrProd, "minis-sessions")

    /** Media root: filesDir/media/<date>/<sid>/. */
    private val mediaRoot: File = File(filesDirForTestingOrProd, "media")

    /**
     * Root of the browser tab-pool saved-state files:
     * filesDir/browser_tabs/<sid>.json (written by BrowserTabPool.saveState).
     * [da1025-D] A session's on-disk footprint is also THIS file — it holds
     * the full tab URLs the session visited — yet it lived outside both
     * deleteSessionFiles and orphan reclamation, so a deleted session's URL
     * history stayed on disk forever (invisible even to the storage page's
     * orphan sweep). Centralised here alongside the other two roots so the
     * session-file lifecycle is owned in one place.
     */
    private val browserTabsRoot: File = File(filesDirForTestingOrProd, "browser_tabs")

    /** Test-only accessor for the media root (mirrors [mediaRoot]). */
    internal fun mediaRootForTesting(): File = mediaRoot

    /** Test-only accessor for the browser-tabs root (mirrors [browserTabsRoot]). */
    internal fun browserTabsRootForTesting(): File = browserTabsRoot

    /** Per-session directory under [sessionsRoot]. */
    fun sessionDir(sessionId: String): File = File(sessionsRoot, sessionId)

    /**
     * The browser tab-pool saved-state file for [sessionId]:
     * filesDir/browser_tabs/<sid>.json (same path BrowserTabPool writes/reads
     * — single source for the path shape, no literal drift).
     */
    fun browserTabFile(sessionId: String): File = File(browserTabsRoot, "$sessionId.json")

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
     * One-pass media-tree scan producing BOTH the per-live-session sizes and
     * the orphan media-leaf report.
     *
     * [storage-rescan-jank] The Storage page previously walked the whole media
     * tree twice per entry (`mediaSizesBySessionBrief` + `scanOrphans`'s
     * internal walk). This single walk replaces both production paths; the two
     * old methods remain as thin wrappers (their tests still pin the
     * semantics).
     *
     * Semantics per file: NOFOLLOW_LINKS attrs, hardlinks deduped by fileKey
     * across the WHOLE tree (one shared set — a hardlink spanning a live dir
     * and an orphan leaf is counted once, at the first visit; the old per-leaf
     * `sizeOf` dedupe window was narrower, but cross-leaf hardlinks in media
     * trees are not a real shape).
     */
    fun scanMediaOnce(liveSessionIds: Set<String>): MediaScan {
        // [da1040-A3] The composer's active draft owns media files under a
        // `__new__<uuid>` leaf; without widening here the walk would count a
        // live draft's media as reclaimable orphan bytes.
        val live = withActiveComposerOwner(liveSessionIds)
        if (!mediaRoot.exists()) return MediaScan(emptyMap(), 0, 0L)
        val sizes = mutableMapOf<String, Long>()
        var orphanDirs = 0
        var orphanBytes = 0L
        val seen = HashSet<Any>()

        fun walk(dir: File, parentOrphanLeaf: Boolean) {
            val children = dir.listFiles()
            if (children == null) {
                // [Bug 2 parity] unreadable dir: assume orphan-leaf candidate
                // (bytes unknowable — 0) so measurement never silently misses.
                if (!parentOrphanLeaf && looksLikeSessionId(dir.name) && dir.name !in live) {
                    orphanDirs++
                }
                return
            }
            val isOrphanLeaf = !parentOrphanLeaf &&
                children.none { it.isDirectory } && // File.isDirectory: same check as the old isOrphanMediaLeaf
                looksLikeSessionId(dir.name) &&
                dir.name !in live
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
                    // Do not follow symlinked dirs.
                    if (!attrs.isSymbolicLink) walk(child, isOrphanLeaf)
                } else if (attrs.isRegularFile) {
                    val key = attrs.fileKey()
                    if (key != null && !seen.add(key)) continue
                    val sid = child.parentFile?.name ?: continue
                    if (sid in live) {
                        sizes[sid] = (sizes[sid] ?: 0L) + attrs.size()
                    } else if (isOrphanLeaf) {
                        orphanBytes += attrs.size()
                    }
                }
            }
            if (isOrphanLeaf) orphanDirs++
        }

        walk(mediaRoot, false)
        return MediaScan(sizes, orphanDirs, orphanBytes)
    }

    /** Result of [scanMediaOnce]: live per-session sizes + orphan media leaves. */
    data class MediaScan(
        val liveSizes: Map<String, Long>,
        val orphanDirs: Int,
        val orphanBytes: Long,
    )

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

    /** Recursively delete a session's bind-mounted dir + its media + its browser tab-pool state. No-op if absent. */
    fun deleteSessionFiles(sessionId: String): DeleteResult {
        val sessionDeleted = deleteRecursivelyChecked(sessionDir(sessionId)) &&
            deleteRecursivelyChecked(browserTabFile(sessionId))
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

    /**
     * Outcome of a session-file delete: which parts actually went away.
     *
     * [da1025-D] `sessionDeleted` now covers BOTH the bind-mounted session dir
     * (minis-sessions/<sid>/) AND the browser tab-pool state file
     * (browser_tabs/<sid>.json — the session's visited-URL history). Callers
     * (ChatRepository.deleteSession, StorageManagementScreen) read only
     * `fullyDeleted`, so folding the tab file into `sessionDeleted` widens the
     * partial-failure signal without changing any caller contract. A session
     * with no browsing history has no tab file: `deleteRecursivelyChecked`
     * returns true for an absent file, so the outcome is unchanged there.
     */
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
        // [da1040-A3] One widening per public entry point; the private helpers
        // below are reachable only from here, so they inherit [live].
        val live = withActiveComposerOwner(liveSessionIds)
        val report = scanOrphans(live)
        with(report) {
            // Delete the scanned session dirs (ids in the report).
            if (sessionIds.isNotEmpty()) {
                sessionIds.forEach { sessionDir(it).takeIf { d -> d.exists() }?.deleteRecursively() }
            }
            // Re-delete media leaves (walk again; ids may overlap session dirs).
            deleteOrphanMediaLeaves(live)
        }
        // [da1025-D] Also reclaim orphaned browser tab-pool state files
        // (browser_tabs/<sid>.json for deleted sessions) — previously they
        // leaked forever (full visited-URL history of a dead session left on
        // disk, invisible to this sweep).
        deleteOrphanBrowserTabFiles(live)
        return report
    }

    /**
     * True if [name] is a session-shaped id: a bare session UUID
     * (`UUID.randomUUID().toString()` — 36 chars, 4 hyphens) OR a draft alias
     * (`__new__<uuid>`, see [DRAFT_ID_PREFIX]).
     *
     * [da1040-A3] The draft alias half used to live as a call-site
     * `startsWith(DRAFT_ID_PREFIX)` branch inside ONE of the five sweep
     * callers; the other four kept inheriting the narrower notion, so an
     * in-flight draft's session dir / media leaf looked orphaned. Both shapes
     * are now decided here and nowhere else.
     *
     * Guarding on this shape keeps reclamation fail-safe: an unrelated folder
     * or stray file under minis-sessions/ (or a media date path) is never
     * mistaken for a session, and a `__new__` name without a full UUID behind
     * it (e.g. the bare `__new__` route placeholder) is not exempted either.
     */
    private fun looksLikeSessionId(name: String): Boolean {
        val uuid = if (name.startsWith(DRAFT_ID_PREFIX)) name.substring(DRAFT_ID_PREFIX.length) else name
        return uuid.length == 36 && uuid.count { it == '-' } == 4
    }

    /**
     * [da1040-A3] Widen a caller's live-DB-session set with the composer's
     * active draft id(s). Every public orphan entry point calls this, so no
     * caller (Storage page, ChatRepository) has to know that a draft owns
     * files before its DB row exists — the caller's notion of "live" stays
     * "what the DB says" and this class adds the slot that the DB cannot know
     * about yet.
     */
    private fun withActiveComposerOwner(liveSessionIds: Set<String>): Set<String> {
        val draftIds = activeDraftIds()
        if (draftIds.isEmpty()) return liveSessionIds
        return liveSessionIds + draftIds
    }

    /**
     * B: scan-only variant — measures reclaimable space WITHOUT deleting.
     * The storage page calls this on entry to surface a "X MB of orphan data"
     * banner that the user explicitly confirms before anything is removed.
     */
    fun scanOrphans(liveSessionIds: Set<String>): ReclaimReport {
        val live = withActiveComposerOwner(liveSessionIds)
        val sessionPart = scanOrphanSessionDirs(live)
        val media = scanMediaOnce(live)
        // [§1025-D R1] The tab-state half is part of the same number: it was
        // being deleted without being counted, so "reclaimable" under-reported
        // every session that ever opened the browser.
        val tabs = scanOrphanBrowserTabFiles(live)
        return ReclaimReport(
            sessionIds = sessionPart.sessionIds,
            sessionDirs = sessionPart.sessionDirs,
            sessionBytes = sessionPart.sessionBytes,
            mediaDirs = media.orphanDirs,
            mediaBytes = media.orphanBytes,
            browserTabsDirs = tabs.browserTabsDirs,
            browserTabsBytes = tabs.browserTabsBytes,
        )
    }

    /**
     * The `browser_tabs/<sid>.json` half of [scanOrphans] — measure only, no
     * deletion. Public for the same reason [scanOrphanSessionDirs] is: the
     * Storage page does ONE media walk and one tab walk and assembles the
     * report itself, instead of triggering a full second scan on entry.
     *
     * Counts and sizes are for the SAME files [deleteOrphanBrowserTabFiles]
     * removes. The rule is spelled out a second time rather than factored out:
     * the delete half's predicate is pinned INLINE by the
     * `browser_tabs_lifecycle_check` gate (see its KDoc), so a shared helper
     * would either move the gate's anchor or leave the two disagreeing anyway.
     * Drift is caught mechanically instead —
     * `the scan and the delete half of the reclaim agree` runs both halves over
     * the same fixture matrix and compares their sets.
     */
    fun scanOrphanBrowserTabFiles(liveSessionIds: Set<String>): ReclaimReport {
        val live = withActiveComposerOwner(liveSessionIds)
        var dirs = 0
        var bytes = 0L
        if (browserTabsRoot.exists()) {
            browserTabsRoot.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val stem = file.name.removeSuffix(".json")
                if (stem == file.name) return@forEach // not a .json state file
                val isSessionShaped = looksLikeSessionId(stem)
                if (isSessionShaped && stem !in live) {
                    dirs++
                    bytes += file.length()
                }
            }
        }
        return ReclaimReport(browserTabsDirs = dirs, browserTabsBytes = bytes)
    }

    /**
     * Orphan session dirs under [sessionsRoot] ONLY — the media half of the
     * orphan report lives inside [scanMediaOnce], so the Storage page can do
     * one media walk total instead of two.
     */
    fun scanOrphanSessionDirs(liveSessionIds: Set<String>): ReclaimReport {
        val live = withActiveComposerOwner(liveSessionIds)
        val sessionIds = mutableListOf<String>()
        var sessionBytes = 0L
        if (sessionsRoot.exists()) {
            sessionsRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && looksLikeSessionId(dir.name) && dir.name !in live) {
                    sessionIds += dir.name
                    sessionBytes += sizeOf(dir)
                }
            }
        }
        return ReclaimReport(
            sessionIds = sessionIds,
            sessionDirs = sessionIds.size,
            sessionBytes = sessionBytes,
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
     * [da1025-D] Delete orphaned browser tab-pool state files:
     * browser_tabs/<stem>.json where the stem is session-shaped (bare UUID or
     * a draft alias — BrowserTabPool writes under the draft id before
     * ChatSessionEntryExt renames it on promotion) and is not live.
     *
     * [da1040-A3] The draft half is no longer spelled here: it belongs to
     * [looksLikeSessionId] (single source for "session-shaped"), the same
     * predicate the session-dir and media sweeps use. A local `startsWith`
     * copy is exactly how the other four callers missed it.
     *
     * [§1025-D R1] These files are now COUNTED too ([ReclaimReport.browserTabsDirs]);
     * the original "deliberately not counted" call left the storage page's
     * reclaimable figure smaller than what the sweep actually frees.
     *
     * The predicate below is deliberately spelled INLINE here: the
     * `browser_tabs_lifecycle_check` gate anchors on this body's
     * `looksLikeSessionId(` + "stem is not live" pair (C3/C3b), and that anchor
     * is the thing stopping the polarity flip that once deleted every LIVE
     * session's tab state. [scanOrphanBrowserTabFiles] measures the same set —
     * the two halves must stay textually parallel, and
     * `the scan and the delete half of the reclaim agree` fails if they drift.
     */
    private fun deleteOrphanBrowserTabFiles(liveSessionIds: Set<String>) {
        if (!browserTabsRoot.exists()) return
        browserTabsRoot.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val stem = file.name.removeSuffix(".json")
            if (stem == file.name) return@forEach // not a .json state file
            val isSessionShaped = looksLikeSessionId(stem)
            if (isSessionShaped && stem !in liveSessionIds) file.delete()
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
        return scanMediaOnce(sessionIds).liveSizes
    }

    /**
     * What one orphan scan found. THREE kinds of leftover are measured, and all
     * three are summed by [totalDirs] / [totalBytes] so the Storage page's
     * "reclaimable" figure matches what [reclaimOrphans] actually deletes:
     *
     *   - [sessionDirs] — `minis-sessions/<sid>/` of a dead session;
     *   - [mediaDirs] — media leaves `<sid>/` under `media/<date>/`;
     *   - [browserTabsDirs] — `browser_tabs/<sid>.json` state files
     *     ([§1025-D R1] previously reclaimed but NOT counted, so the banner
     *     promised less than the sweep freed). The field name keeps the
     *     report's `…Dirs` family; the value counts state FILES, one per
     *     session, because that is the shape this store keeps tab state in.
     *
     * Adding a fourth kind means adding it here AND to [scanOrphans], so the
     * number the user confirms is the number that gets deleted.
     */
    data class ReclaimReport(
        val sessionIds: List<String> = emptyList(),
        val sessionDirs: Int = 0,
        val sessionBytes: Long = 0L,
        val mediaDirs: Int = 0,
        val mediaBytes: Long = 0L,
        val browserTabsDirs: Int = 0,
        val browserTabsBytes: Long = 0L,
    ) {
        val totalBytes: Long get() = sessionBytes + mediaBytes + browserTabsBytes
        val totalDirs: Int get() = sessionDirs + mediaDirs + browserTabsDirs
    }
}
