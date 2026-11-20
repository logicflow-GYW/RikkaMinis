package com.rikkaminis.app.ui.sandbox

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rikkaminis.app.logging.AppLogger
import com.rikkaminis.app.sandbox.internalGuestTmpDeleteTreeNoFollow
import com.rikkaminis.app.ui.settings.SharedFolderRegistry
import com.rikkaminis.app.ui.settings.invalidateStorageSnapshot
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Mirrors iOS FileSortKey. */
enum class FileSortKey { NAME, MODIFIED, SIZE, KIND }

/**
 * Pure-Kotlin file size formatting (1024-based B/KB/MB/GB).
 *
 * [sweep1014-B29] Replaces `android.text.format.Formatter.formatFileSize`:
 * AOSP Formatter returns "" when its `context` argument is null, and every
 * call site here passed null — so every file size in the file browser and
 * preview screens rendered as blank. No Android context is needed for the
 * binary-unit format this UI uses, so the formatting lives in a pure
 * function (also unit-testable on the JVM).
 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < 3) {
        value /= 1024.0
        unit += 1
    }
    val unitName = when (unit) {
        1 -> "KB"
        2 -> "MB"
        else -> "GB"
    }
    val rounded = (value * 10).let { kotlin.math.round(it) } / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.toLong()} $unitName" else "$rounded $unitName"
}

/**
 * [FIX-FW-FILE-DATE] Which label [FileItem.formattedDate] shows for a file's
 * mtime. Split out of the getter so the day arithmetic can be unit-tested with
 * an injected `now` and an injected zone (the getter reads the device clock and
 * the default zone, neither of which a JVM table test can pin).
 */
internal enum class FileDateBucket {
    /** No usable mtime (`lastModified()` answers 0 for an unreadable entry). */
    EMPTY,

    /** mtime is AHEAD of the reader's clock. */
    FUTURE,

    SAME_DAY,

    YESTERDAY,

    /** 1–6 whole days back — shown as a weekday name. */
    WITHIN_WEEK,

    /** 7+ days back — shown as an absolute date. */
    OLDER,
}

private const val DAY_MS = 24L * 60L * 60L * 1000L

/**
 * [FIX-FW-FILE-DATE] Bucket for [modifiedMs] as seen at [nowMs].
 *
 * The defect this replaces: `daysAgo in 0..6` was the last "recent" test, and
 * `daysAgo` came from an integer division that Kotlin truncates TOWARD ZERO —
 * so a timestamp a minute in the future (clock drift; sandbox / NFS / SMB
 * mounts hand out the SERVER's mtime) produced `daysAgo == 0`, sailed past
 * same-day and yesterday, and rendered a WEEKDAY NAME ("Thursday"). A file the
 * user had just written read as if it were written last Thursday.
 *
 * FUTURE is therefore an explicit arm evaluated before any day arithmetic, not
 * something the sign of `daysAgo` is trusted to catch. Consequently `daysAgo`
 * here can only be ≥ 1, and [WITHIN_WEEK] starts at 1: a residual 0 (should the
 * ordering ever change) degrades to an absolute date instead of inventing a
 * weekday.
 *
 * [zone] is a parameter only so tests can pin UTC; production passes the
 * device default via the caller.
 */
internal fun fileDateBucket(
    modifiedMs: Long,
    nowMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
): FileDateBucket {
    if (modifiedMs <= 0L) return FileDateBucket.EMPTY
    if (modifiedMs > nowMs) return FileDateBucket.FUTURE
    val then = Calendar.getInstance(zone).apply { timeInMillis = modifiedMs }
    val now = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return FileDateBucket.SAME_DAY
    val yesterday = Calendar.getInstance(zone).apply {
        timeInMillis = nowMs
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (isYesterday) return FileDateBucket.YESTERDAY
    val daysAgo = (nowMs - modifiedMs) / DAY_MS
    if (daysAgo in 1..6) return FileDateBucket.WITHIN_WEEK
    return FileDateBucket.OLDER
}

/**
 * [R2 F-04/F-05] Real (fully resolved) form of [f], or `null` when the
 * filesystem cannot answer.
 *
 * `File.canonicalFile` is NOT the right primitive here: it resolves `..` and
 * `.` lexically and happily returns a path that does not exist, so
 * "unresolvable" never happens and a deleted/renamed target reads as a valid
 * one. `toRealPath()` requires every component to exist and resolves symlinks,
 * which is exactly the question the guards are asking — and its failure is the
 * fail-closed answer.
 */
internal fun canonicalFileOrNull(f: File): File? =
    try {
        f.toPath().toRealPath().toFile()
    } catch (_: Exception) {
        null
    }

/**
 * [R2 F-04/F-05] True when [path] IS [root] or sits below it, compared on whole
 * segments after canonicalizing BOTH sides.
 *
 * Both sides canonical: a raw `startsWith` on `/data/user/0/<pkg>` admits
 * `/data/user/0/<pkg>-other`, and comparing a canonical path against a
 * non-canonical root (`/data/data/…` vs `/data/user/0/…` on the same device)
 * silently answers "outside" for a path that is inside. Unresolvable ⇒ false.
 */
internal fun isCanonicalUnder(path: File, root: File): Boolean {
    val p = canonicalFileOrNull(path)?.path ?: return false
    val r = canonicalFileOrNull(root)?.path ?: return false
    val base = r.trimEnd(File.separatorChar)
    if (base.isEmpty()) return false
    return p == base || p.startsWith("$base${File.separatorChar}")
}

/** [R2 F-05] True when [path] is under ANY of [roots]; an empty [roots] list is
 *  "nothing is allowed" — the fail-closed reading, never "no restriction". */
internal fun isUnderAnyRoot(path: File?, roots: List<File>): Boolean {
    if (path == null || roots.isEmpty()) return false
    return roots.any { isCanonicalUnder(path, it) }
}

/** [R2 F-04] Why a delete was refused on the CANONICAL path. */
internal enum class CanonicalDeleteRefusal {
    /** The canonical target is outside every sandbox root (or unresolvable). */
    OutsideSandbox,
    /** The canonical target lands in a declared read-only zone. */
    ReadOnlyZone,
}

/**
 * [R2 F-04/F-05] Refusal verdict for deleting [target], decided on the
 * CANONICAL path rather than on the logical one the browser displays.
 *
 * The defect this closes: `deleteItem` validated `linuxPathOf(item)` — a string
 * built from the browser's root + breadcrumb + name. After `ln -s
 * /var/minis/memory /var/minis/workspace/m`, entering `m` lists the REAL
 * `memory/` files while the logical path still reads `/var/minis/workspace/m/…`,
 * so every read-only guard saw a writable path and the trash icon deleted
 * `SOUL.md` / `GLOBAL.md` from disk.
 *
 * [target] is what actually gets removed:
 *  - a SYMLINK contributes its PARENT directory — the delete unlinks the name,
 *    so the only thing that has to be inside is the directory holding it (a
 *    dangling link stays deletable, which is correct: it is a name, not data);
 *  - everything else contributes itself.
 *
 * `null` (unresolvable) is a REFUSAL: a path whose canonical form cannot be
 * established is not proven inside the sandbox.
 */
internal fun canonicalDeleteRefusal(
    target: File?,
    isSymlink: Boolean,
    allowedRoots: List<File>,
    readOnlyRoots: List<File>,
): CanonicalDeleteRefusal? {
    if (target == null) return CanonicalDeleteRefusal.OutsideSandbox
    val subject = if (isSymlink) target.absoluteFile.parentFile else target
    val canonical = canonicalFileOrNull(subject ?: return CanonicalDeleteRefusal.OutsideSandbox)
        ?: return CanonicalDeleteRefusal.OutsideSandbox
    if (!isUnderAnyRoot(canonical, allowedRoots)) return CanonicalDeleteRefusal.OutsideSandbox
    if (isUnderAnyRoot(canonical, readOnlyRoots)) return CanonicalDeleteRefusal.ReadOnlyZone
    return null
}

/**
 * [R2 F-05] Bound a symlink target to the browser's sandbox roots.
 *
 * The pre-fix resolver answered `File(rawTarget).takeIf { it.exists() }` when
 * the guest mapping missed — an unbounded host path, so a link to
 * `/data/user/0/<pkg>/shared_prefs` (or a `../../../../databases` relative
 * spelling) was browsable, previewable, shareable and deletable from the UI.
 *
 * Returns the CANONICAL target when it sits under one of [allowedRoots], and
 * `null` otherwise — including when canonicalization fails. `null` is the
 * documented "refuse instead of guess" answer every caller already has a branch
 * for (the entry stays "unknown" / the listing stays empty).
 */
internal fun boundedSymlinkTarget(candidate: File?, allowedRoots: List<File>): File? {
    if (candidate == null) return null
    val canonical = canonicalFileOrNull(candidate) ?: return null
    return canonical.takeIf { isUnderAnyRoot(it, allowedRoots) }
}

/**
 * [R2 F-05] Pick the host target for a symlink whose own target text is
 * [rawTarget], given what the guest mapper answered ([mapped]).
 *
 * The pre-fix shape was:
 *
 * ```
 * if (mapped != null && mapped.exists()) return mapped
 * return File(rawTarget).takeIf { it.exists() }
 * ```
 *
 * The fallback covers a real case — a link written on the HOST side names a host
 * path, not a guest one, and dropping it would make every such link unopenable —
 * but it answered ANY path, so `ln -s /data/user/0/<pkg>/shared_prefs p` turned
 * the private preferences directory into a browseable, previewable, deletable
 * folder. Both arms are bounded by the browser's own roots here, and an
 * unresolvable/unplaceable answer is `null` (the documented
 * "refuse instead of guess" answer), never the raw path.
 *
 * Pure: [exists] and [roots] arrive as parameters so the JVM test source set
 * pins every arm without an Android `Context` or a real filesystem.
 */
internal fun resolveHostSymlinkTarget(
    mapped: File?,
    rawTarget: String?,
    exists: (File) -> Boolean,
    roots: List<File>,
): File? {
    if (mapped != null && exists(mapped)) {
        boundedSymlinkTarget(mapped, roots)?.let { return it }
    }
    if (rawTarget.isNullOrEmpty()) return null
    val raw = runCatching { File(rawTarget) }.getOrNull() ?: return null
    if (!exists(raw)) return null
    return boundedSymlinkTarget(raw, roots)
}

/**
 * [R5-F F-F-02] Host directory a listing must read for [hostDir], following a
 * link whose target is a GUEST path at ANY depth.
 *
 * [resolveListedEntry] already maps a link's own target and [FileItem.from]
 * uses it to DESCRIBE the entry — but entering the entry is a different
 * question. `navigateTo` appends the link's *name* to the logical path, so the
 * next listing asks the filesystem for `<root>/dirLink` (and later
 * `<root>/dirLink/sub`), and `toRealPath()` cannot resolve either: PRoot stored
 * `/var/minis/…` in the link, a path the Android host does not have. The old
 * code fell back to the literal path, `listFiles()` returned `null` and the
 * folder the user had just tapped opened EMPTY. Walking up to the deepest
 * symlink ancestor and rebasing the remainder onto its mapped target is what
 * makes both the one-level tap and sub-navigation work.
 *
 * Pure except for [mapGuest] (the browser's own guest→host mapper) and the
 * filesystem reads, so the JVM test source set can pin every arm.
 */
internal fun resolveListingHostDir(
    hostDir: File,
    mapGuest: (String) -> File?,
    sandboxRoots: List<File> = emptyList(),
): File {
    // Fast path — every non-link path, and every link whose target exists on
    // the host under its literal spelling.
    try {
        val real = hostDir.toPath().toRealPath().toFile()
        // [R2 F-05] Bound the fast path too: `toRealPath()` FOLLOWS links, so a
        // plain-looking `<root>/dirLink/sub` reaches whatever the link names —
        // including `/data/user/0/<pkg>/databases`. Only the answer is checked
        // (not the spelling), so a link into a legitimate root still lists.
        if (sandboxRoots.isEmpty() || isUnderAnyRoot(real, sandboxRoots)) return real
    } catch (_: Exception) {
        // Fall through: an unresolvable path may sit under a guest-path link.
    }
    var node: File? = hostDir
    // Segments below [node], root-first; rebased onto the mapped target.
    val tail = ArrayDeque<String>()
    while (node != null) {
        val linkTarget = try {
            if (Files.isSymbolicLink(node.toPath())) {
                Files.readSymbolicLink(node.toPath()).toString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        val target = resolveListedEntry(node, linkTarget, mapGuest)
        // `target != node` skips the unmappable case (resolveListedEntry then
        // answers the link itself — refuse-instead-of-guess); `exists()` keeps
        // a dangling link from being rebased onto a fabricated path.
        if (target != node && target.exists()) {
            var out = target
            for (name in tail) out = File(out, name)
            // [R2 F-05] Same bound as the fast path, applied to the REBASED
            // path: a guest-absolute link (`ln -s /var/minis/workspace/x l`) is
            // mapped by [mapGuest] to a host dir, and the mapping itself is the
            // thing that could name a private directory. Unbounded, this arm
            // was the F-05 escape with a container spelling.
            if (sandboxRoots.isEmpty()) return out
            boundedSymlinkTarget(out, sandboxRoots)?.let { return it }
            // Refused: never hand back the link (the caller's `listFiles()`
            // would follow it and list exactly the tree we just refused).
            return refusedListing(hostDir, sandboxRoots)
        }
        tail.addFirst(node.name)
        node = node.parentFile
    }
    // Nothing along the chain mapped to a placeable target. When roots are in
    // play the answer must still be inside them: `hostDir` may BE a link whose
    // `listFiles()` follows it (the F-05 shape).
    if (sandboxRoots.isEmpty()) return hostDir
    return refusedListing(hostDir, sandboxRoots)
}

/** [R2 F-05] Name used as the "this listing was refused" target. Never created
 *  on disk, so `listFiles()` on it answers `null` (an empty folder in the UI). */
private const val REFUSED_LISTING_NAME = ".minis-refused-listing"

/**
 * [R2 F-05] What to LIST when [hostDir] resolved to a refused target.
 *
 * Two cases, because "the caller will call `listFiles()`" is the constraint:
 *  - the refusal happened DEEPER than [hostDir] (a nested link) ⇒ the nearest
 *    allowed ancestor is a real, listable directory and is the honest answer;
 *  - [hostDir] ITSELF is the refused entry ⇒ nothing may be listed, and any
 *    existing path here would be followed, so answer a name that does not
 *    exist.
 */
internal fun refusedListing(hostDir: File, roots: List<File>): File {
    // Always a name that does NOT exist: an existing path here would be listed
    // (or followed), and the whole point is that nothing about the refused
    // entry may be read. The UI shows an empty folder, which is honest.
    val parent = hostDir.absoluteFile.parentFile ?: hostDir
    val sentinel = File(parent, REFUSED_LISTING_NAME)
    // Keep the answer inside the roots even in the pathological case where the
    // refused entry's own parent is outside them.
    val anchor = nearestAllowedDir(parent, roots)
    return File(anchor, REFUSED_LISTING_NAME).takeIf { !it.exists() } ?: sentinel
}

/**
 * [R2 F-05] Nearest ancestor of [dir] (itself included) that resolves inside
 * [roots], or [dir] when none does — the "list nothing rather than the wrong
 * thing" answer for a refused link.
 */
internal fun nearestAllowedDir(dir: File, roots: List<File>): File {
    var cur: File? = dir
    while (cur != null) {
        if (isUnderAnyRoot(cur, roots)) return cur
        cur = cur.parentFile
    }
    return dir
}

/**
 * File item model — corresponds to iOS FileItem.
 */
@Immutable
data class FileItem(
    /**
     * The entry as it sits in the listing — for a symlink this is the LINK
     * (lstat), never what it points at. Delete/unlink and every path-based
     * gesture must keep reading this field: [R4-V F-11] removing a
     * directory-shaped link has to unlink the NAME, and
     * `deleteRecursively()` on the resolved tree would wipe the target.
     */
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    /** lastModified() in epoch ms, 0 when unavailable. */
    val modifiedMs: Long = 0L,
    /**
     * [R5-F F-F-02] Host-READABLE path — what the entry resolves to for
     * reading, i.e. [resolveListedEntry]'s answer, which is [file] itself for
     * every non-link entry (and for a link that cannot be mapped, the
     * documented refuse-instead-of-guess answer).
     *
     * Consumers that OPEN the entry use this; consumers that remove or rename
     * it use [file]. Keeping both on the item is what lets a container-absolute
     * link (`ln -s /var/minis/workspace/x dirLink`) be listed AND opened: the
     * preview route used to be handed [file]'s absolutePath — the host path of
     * the link — and every content read followed it to a target the Android
     * host does not have, so the preview screen came up empty.
     */
    val hostPath: File = file,
) {
    val iconRes: String
        get() {
            if (isDirectory) return "folder"
            val ext = file.extension.lowercase()
            return when (ext) {
                "txt", "md", "json", "xml", "yaml", "yml", "conf", "cfg", "ini",
                "log", "csv" -> "text"

                "sh", "bash", "zsh", "fish" -> "terminal"
                "py", "js", "ts", "kt", "java", "c", "cpp", "h", "m", "swift",
                "rs", "go", "rb", "php", "lua", "pl" -> "code"

                "png", "jpg", "jpeg", "gif", "bmp", "svg", "webp", "ico" -> "image"
                "mp3", "wav", "aac", "flac", "ogg", "m4a" -> "audio"
                "mp4", "mov", "avi", "mkv", "webm" -> "video"
                "zip", "tar", "gz", "bz2", "xz", "7z", "rar" -> "archive"
                "pdf" -> "pdf"
                "apk", "deb", "rpm" -> "package"
                "db", "sqlite", "sqlite3" -> "database"
                "so", "dylib", "a" -> "library"
                else -> "file"
            }
        }

    val formattedSize: String
        get() = formatFileSize(size)

    /** Whether this file can be previewed inline (text, image, etc.) */
    val isPreviewable: Boolean
        get() = isTextFile || isImageFile

    val isTextFile: Boolean
        get() {
            val ext = file.extension.lowercase()
            return ext in setOf(
                "txt", "md", "json", "xml", "yaml", "yml", "conf", "cfg", "ini",
                "log", "csv", "sh", "bash", "zsh", "fish",
                "py", "js", "ts", "kt", "java", "c", "cpp", "h", "m", "swift",
                "rs", "go", "rb", "php", "lua", "pl", "html", "css", "scss",
                "toml", "env", "gitignore", "dockerfile", "makefile",
            ) || ext.isEmpty() // extensionless files treated as text
        }

    val isImageFile: Boolean
        get() {
            val ext = file.extension.lowercase()
            return ext in setOf("png", "jpg", "jpeg", "gif", "bmp", "webp", "ico")
        }

    val isMarkdownFile: Boolean
        get() = file.extension.lowercase() in setOf("md", "markdown", "mdown", "mkd")

    val isHtmlFile: Boolean
        get() = file.extension.lowercase() in setOf("html", "htm", "xhtml")

    val isAudioFile: Boolean
        get() = file.extension.lowercase() in setOf("mp3", "wav", "aac", "flac", "ogg", "m4a")

    val isVideoFile: Boolean
        get() = file.extension.lowercase() in setOf("mp4", "mov", "avi", "mkv", "webm")

    val isPdfFile: Boolean
        get() = file.extension.lowercase() == "pdf"

    // T144 — file-type flags driving FilePreviewScreen renderers.
    val isCsvFile: Boolean
        get() = file.extension.lowercase() in setOf("csv", "tsv")

    val isJsonFile: Boolean
        get() = file.extension.lowercase() == "json"

    val isArchiveFile: Boolean
        get() = file.extension.lowercase() in setOf("zip", "jar", "apk", "aar")

    val isOfficeFile: Boolean
        get() = file.extension.lowercase() in setOf(
            "xlsx", "xls", "docx", "doc", "pptx", "ppt", "odt", "ods", "odp",
        )

    /**
     * Mirrors iOS FileItem.formattedDate: today=time, yesterday="Yesterday", <7d=weekday, else short date.
     *
     * [FIX-FW-FILE-DATE] A timestamp in the FUTURE renders as `"Just now"`:
     * a future mtime is almost always a clock that runs ahead of this device
     * (sandbox/PRoot, NFS/SMB mounts stamp the SERVER's clock, a just-written
     * file from a drifted guest), so "the file is new" is the honest reading.
     * The alternative — an absolute date — would print tomorrow's (or next
     * year's) date for a file that appeared a second ago, which is exactly the
     * misreading this fix removes. `"Just now"` is distinguishable from the
     * same-day label (`h:mm a`), and collapsing "1 s ago" and "1 s from now"
     * onto it is intentional: the two are indistinguishable to the user anyway.
     * Bucketing (and therefore the weekday fallthrough) lives in
     * [fileDateBucket] where a JVM table test can pin it.
     */
    val formattedDate: String
        get() {
            val date = Date(modifiedMs)
            return when (fileDateBucket(modifiedMs, System.currentTimeMillis())) {
                FileDateBucket.EMPTY -> ""
                FileDateBucket.FUTURE -> "Just now"
                FileDateBucket.SAME_DAY -> timeFormatter.format(date)
                FileDateBucket.YESTERDAY -> "Yesterday"
                FileDateBucket.WITHIN_WEEK -> weekdayFormatter.format(date)
                FileDateBucket.OLDER -> shortDateFormatter.format(date)
            }
        }

    companion object {
        private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        private val weekdayFormatter = SimpleDateFormat("EEEE", Locale.getDefault())
        private val shortDateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        fun from(
            file: File,
            resolveGuestAbsolute: (String) -> File? = { null },
            sandboxRoots: List<File> = emptyList(),
        ): FileItem? {
            // [sweep1014-B29] Symlink check MUST precede the exists() check:
            // exists() follows the link, so a broken (dangling) symlink reads
            // as nonexistent and was dropped before the isSymlink handling
            // below ever ran. Dangling symlinks are still listable entries.
            val isSymlink = Files.isSymbolicLink(file.toPath())
            if (!isSymlink && !file.exists()) return null
            // [R4-V F-11] PRoot writes the path the SHELL sees into the link
            // (`/var/minis/…`, `/usr/bin/…`). The Android host root has no
            // `/var/minis`, so resolving an absolute target as a host path made
            // every container-absolute link read as a 0-byte NON-directory —
            // no folder affordance, unopenable, and it exposed the trash icon.
            // Absolute targets now go through the browser's own guest→host
            // mapper; an unmappable target stays "unknown" (the link itself),
            // never a fabricated file.
            val resolved = if (isSymlink) {
                val rawTarget = try {
                    Files.readSymbolicLink(file.toPath()).toString()
                } catch (_: Exception) {
                    null
                }
                // [R2 F-05] Bounded: the mapped/unmapped answer only stands when
                // its CANONICAL form is still inside a declared sandbox root.
                // A link to `/data/user/0/<pkg>/shared_prefs` (or the relative
                // `../../../../databases` spelling) used to describe the private
                // directory as an ordinary folder — browsable, previewable,
                // shareable and deletable from the UI. An empty [sandboxRoots]
                // list keeps the historical behaviour for callers that pass no
                // roots (chat link resolution, tests); the browser always passes
                // them.
                val entry = resolveListedEntry(file, rawTarget, resolveGuestAbsolute)
                if (sandboxRoots.isEmpty()) {
                    entry
                } else {
                    // [R2 F-05] Bound the RESOLVED target in every shape. When
                    // the guest mapper cannot place an absolute target (a link
                    // written host-side, or a relative one) the filesystem is
                    // still the authority on where it points — `toRealPath()`
                    // follows it, and the answer is what the entry may open.
                    // A refused target reads as "unknown" (never as the link:
                    // `isDirectory` follows links, so handing the link back
                    // would keep the folder affordance and let `navigateTo`
                    // enter the private directory).
                    val candidate = if (entry != file) {
                        entry
                    } else {
                        runCatching { file.toPath().toRealPath().toFile() }.getOrNull()
                    }
                    boundedSymlinkTarget(candidate, sandboxRoots) ?: return FileItem(
                        file = file,
                        name = file.name,
                        isDirectory = false,
                        isSymlink = true,
                        size = 0L,
                        modifiedMs = runCatching { Files.getLastModifiedTime(file.toPath()).toMillis() }
                            .getOrDefault(0L),
                        hostPath = file,
                    )
                }
            } else file

            return FileItem(
                file = file,
                name = file.name,
                isDirectory = resolved.isDirectory,
                isSymlink = isSymlink,
                size = if (resolved.isDirectory) 0L else resolved.length(),
                modifiedMs = resolved.lastModified(),
                // [R5-F F-F-02] The resolved target — what opening the entry
                // must read. `file` stays the link so delete/unlink keeps
                // working on the name (see the field KDoc).
                hostPath = resolved,
            )
        }
    }
}

data class FileBrowserUiState(
    val items: List<FileItem> = emptyList(),
    val pathComponents: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isEmpty: Boolean = false,
    val canGoBack: Boolean = false,
    val currentPath: String = "",
    val errorMessage: String? = null,
    val sortKey: FileSortKey = FileSortKey.NAME,
    val sortAscending: Boolean = true,
    val foldersFirst: Boolean = true,
    /**
     * Show files whose name starts with `.` (Unix dotfiles). Off by
     * default so the listing matches typical `ls` behaviour. Persisted
     * across app launches via SharedPreferences (see
     * [FileBrowserViewModel.PREFS_NAME]). T-hidden-files a3e7f1d0.
     */
    val showHidden: Boolean = false,
    /**
     * [T-android-file-context-copy-abs-path] Linux-side (PRoot) absolute path
     * of the directory currently shown, e.g. "/var/minis/workspace/foo". Null
     * when this browser isn't rooted under a Linux bind mount (a raw host-path
     * browser). The file context menu's "Copy Absolute Path" joins this with
     * the item name so the user copies the path the agent / shell sees
     * (/var/minis/…), not the opaque Android host path.
     */
    val currentLinuxPath: String? = null,
)

class FileBrowserViewModel(
    private val rootPath: File,
    initialPath: File? = null,
    private val rootLabel: String = rootPath.name,
    // linuxRootPath: when set, directory listings route through PRootKernel
    // bind mounts so subdirs like /var/minis/{skills,memory,shared} list their
    // real content (filesDir/minis-global/*) instead of the empty placeholder
    // dirs shipped inside the Alpine rootfs tarball.
    private val linuxRootPath: String? = null,
    // T147: when set together with [appContext], `/var/minis/{attachments,
    // workspace,offloads,browser}` resolves against THIS session's per-session
    // host dir (filesDir/minis-sessions/<sessionId>/<subdir>) instead of
    // PRootKernel's last-writer-wins global bindMounts map. Lets the Chat
    // Files browser show files generated by the agent in this session even
    // when another session was the most recent shell to boot.
    private val sessionId: String? = null,
    private val appContext: android.content.Context? = null,
    // [T-android-copy-abs-path-fullpath] Linux (PRoot) path of [rootPath] used
    // ONLY to compute the "Copy Absolute Path" value, decoupled from
    // [linuxRootPath] (which also re-routes directory listings). The session
    // storage browser roots its host listing directly at the per-session dir
    // (filesDir/minis-sessions/<sid>) — that listing already resolves correctly
    // host-side, so we must NOT route it through the PRoot resolver (which would
    // redirect /var/minis to the global/empty placeholder dir). Instead this
    // prefix just lets the copy menu emit /var/minis/workspace/foo.py instead of
    // the opaque /data/user/0/.../minis-sessions/<sid>/workspace/foo.py host
    // path. When null, [linuxRootPath] (if any) drives the copy path as before.
    private val displayLinuxPrefix: String? = null,
    // Injectable IO boundary for deterministic out-of-order completion tests.
    private val listingDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val directoryReader: (suspend (File, Boolean) -> List<FileItem>)? = null,
    private val fileOperationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    /** Cached unsorted listing so re-sort doesn't need to re-stat the dir. */
    private var rawItems: List<FileItem> = emptyList()

    /** Main-confined: repeated loads of the SAME path still have distinct owners. */
    private var loadGeneration = 0L

    /** Path relative to [rootPath] (e.g. "skills/skill-creator"); empty = at root. */
    private var relativePath: String = initialPath?.let { init ->
        if (init.absolutePath.startsWith(rootPath.absolutePath)) {
            init.absolutePath.removePrefix(rootPath.absolutePath).trim('/')
        } else ""
    }.orEmpty()

    /**
     * T145: snapshot of [relativePath] at construction time — the
     * directory the screen was opened at. Back navigation stops here
     * (and `goBack()` returns false to signal the caller to pop the
     * screen) instead of crawling further up toward [rootPath]. Mirrors
     * iOS FileBrowserView's behaviour where the initial directory is
     * the effective root for the back-stack of this screen.
     */
    private val initialRelativePath: String = relativePath

    /** Current resolved host-side File (accounts for PRoot bind mounts). */
    private val currentHostPath: File
        get() = resolveCurrentHostPath()

    private fun resolveCurrentHostPath(): File {
        val linuxRoot = linuxRootPath
        if (linuxRoot != null) {
            val linuxPath = if (relativePath.isEmpty()) linuxRoot
                            else "${linuxRoot.trimEnd('/')}/$relativePath"
            // T147: prefer the session-scoped resolver when we know which
            // session's view we're rendering — global bindMounts is
            // last-writer-wins and points at whichever session's PRoot
            // booted most recently. [audit3 F-08] Route the choice through
            // resolvePathForSession: a path the SESSION resolver rejects
            // (escape out of the session tree) must not fall through to the
            // global resolver, which searches every minis-sessions/<id>/ tree
            // — i.e. possibly another session's view. Non-session paths
            // (/var/minis/skills, mounts) still resolve globally, inside
            // resolveSessionHostPath itself.
            val sid = sessionId
            val ctx = appContext
            val scoped = if (sid != null && ctx != null) {
                com.rikkaminis.app.sandbox.PRootKernel.resolvePathForSession(sid, linuxPath, ctx)
            } else {
                com.rikkaminis.app.sandbox.PRootKernel.resolveHostPath(linuxPath)
            }
            scoped?.let { return it }
        }
        return if (relativePath.isEmpty()) rootPath else File(rootPath, relativePath)
    }

    /**
     * [R4-V F-11] Map a symlink's ABSOLUTE target — a GUEST path, because that
     * is what PRoot stores when the shell creates the link — onto the host file
     * the listing should describe.
     *
     * Same resolver the listing itself uses ([resolvePathForSession] when the
     * screen knows its session, the global [PRootKernel.resolveHostPath]
     * otherwise), so a link into `/var/minis/workspace` reads as THIS session's
     * workspace rather than whichever session booted last.
     *
     * A host-absolute link (not written by PRoot) has no guest meaning: the
     * guest mapping lands inside the rootfs, so when the mapped path does not
     * exist we fall back to the raw path. When neither exists the answer is
     * `null` — [resolveListedEntry] then keeps the entry as "unknown" and the
     * UI treats it as a plain non-directory, which is the refuse-instead-of-
     * guess shape this defect needs.
     */
    private fun resolveGuestAbsoluteTarget(rawTarget: String): File? {
        val ctx = appContext
        val mapped = if (ctx != null) {
            com.rikkaminis.app.sandbox.PRootKernel.resolvePathForSession(sessionId, rawTarget, ctx)
        } else {
            com.rikkaminis.app.sandbox.PRootKernel.resolveHostPath(rawTarget)
        }
        // [R2 F-05] The unbounded fallback `File(rawTarget).takeIf { it.exists() }`
        // that used to sit here is now BOUNDED. It existed for a reason — a link
        // written on the host side names a host path, not a guest one, and
        // dropping the fallback entirely would make every such link unopenable —
        // but it answered any path at all, so `ln -s /data/user/0/<pkg>/
        // shared_prefs p` made the private preferences directory a browseable,
        // previewable, deletable folder. Both arms now go through the pure
        // [resolveHostSymlinkTarget], which bounds each answer by the browser's
        // own roots and answers null (refuse instead of guess) otherwise.
        return resolveHostSymlinkTarget(
            mapped = mapped,
            rawTarget = rawTarget,
            exists = { it.exists() },
            roots = sandboxRoots(),
        )
    }

    /**
     * [R2 F-04/F-05] The filesystem roots this browser is allowed to reach.
     *
     * Derived, never a literal list: the root the screen was opened at (the
     * rootfs, a session dir, a mounted folder, …), the two shared global roots
     * (`minis-global/{memory,skills}` back `/var/minis/{memory,skills}`), the
     * global shared folder, the rootfs itself when a guest root is in play, and
     * every currently-mounted external folder. Missing entries are simply
     * absent from the list, which the guards read as "not allowed".
     */
    private fun sandboxRoots(): List<File> {
        val roots = LinkedHashSet<File>()
        roots += rootPath
        val ctx = appContext
        if (ctx != null) {
            val filesDir = ctx.filesDir
            roots += File(filesDir, "minis-sessions")
            roots += File(filesDir, "minis-global")
            roots += File(filesDir, "media")
            roots += File(filesDir, "share_extension")
            // PRoot's bind-mount table is what /var/minis/* really points at;
            // its host sides are legitimate browse targets.
            com.rikkaminis.app.sandbox.PRootKernel.bindMounts.values
                .mapNotNullTo(roots) { runCatching { File(it) }.getOrNull() }
            runCatching {
                com.rikkaminis.app.sandbox.RootfsManager.getInstance(ctx).rootfsDir
            }.getOrNull()?.let { roots += it }
            runCatching {
                com.rikkaminis.app.sandbox.PRootKernel.mountedFoldersStore
                    ?.entries?.value
                    ?.mapNotNull { it.resolvedHostPath }
                    ?.map { File(it) }
            }.getOrNull()?.let { roots += it }
        }
        return roots.filter { it.exists() }
    }

    /**
     * [R2 F-04] The HOST directories behind the read-only zones, so the
     * canonical-path guard can refuse them.
     *
     * Derived, never a second path list: `SharedFolderRegistry.readOnlyLinuxPaths`
     * is the single source for "which guest paths are read-only", and each is
     * mapped to its host dir through PRoot's own bind table (the same mapping
     * the listing uses). A guest path with no live bind contributes nothing —
     * the string-based guards below still cover it.
     */
    private fun readOnlyRoots(): List<File> {
        val mounts = com.rikkaminis.app.sandbox.PRootKernel.bindMounts
        return SharedFolderRegistry.readOnlyLinuxPaths.mapNotNull { guest ->
            val host = mounts[guest] ?: return@mapNotNull null
            runCatching { File(host) }.getOrNull()
        }
    }

    init {
        // Restore persisted showHidden preference (T-hidden-files
        // a3e7f1d0). Persisted lazily via SharedPreferences when the user
        // toggles the menu item; if [appContext] is null (e.g. test
        // harness) we fall back to the default false.
        val persisted = appContext
            ?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.getBoolean(PREF_KEY_SHOW_HIDDEN, false)
            ?: false
        if (persisted) {
            _uiState.value = _uiState.value.copy(showHidden = true)
        }
        updatePathComponents()
        loadItems()
    }

    /**
     * Toggle the "show hidden files" preference and re-load the current
     * directory listing so newly-included / newly-excluded dotfiles
     * surface immediately. Persisted via SharedPreferences so the choice
     * sticks across app restarts. T-hidden-files a3e7f1d0.
     */
    fun setShowHidden(value: Boolean) {
        if (_uiState.value.showHidden == value) return
        _uiState.value = _uiState.value.copy(showHidden = value)
        appContext
            ?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(PREF_KEY_SHOW_HIDDEN, value)
            ?.apply()
        loadItems()
    }

    fun navigateTo(item: FileItem) {
        if (!item.isDirectory) return
        relativePath = if (relativePath.isEmpty()) item.name else "$relativePath/${item.name}"
        updatePathComponents()
        loadItems()
    }

    /**
     * T145: pop one directory level. Returns `true` when the navigation
     * was consumed in-place (the screen stays open), `false` when we're
     * already at the initial entry directory — caller should treat this
     * as "exit the screen" (popBackStack).
     *
     * Without the floor at [initialRelativePath] the user would crawl
     * up past the directory they entered (e.g. into `/var/`, `/`),
     * which makes Chat Files feel like a generic file manager rather
     * than a session-scoped browser.
     */
    fun goBack(): Boolean {
        if (relativePath == initialRelativePath) return false
        // We're somewhere below the initial dir. Pop one segment, but
        // don't pop past the initial dir even if the user navigated
        // sideways (e.g. via breadcrumb tap to a deeper sibling) —
        // clamp the resulting path to start with initialRelativePath.
        val parent = relativePath.substringBeforeLast('/', "")
        val initial = initialRelativePath
        relativePath = when {
            initial.isEmpty() -> parent
            parent.isEmpty() -> initial
            parent == initial || parent.startsWith("$initial/") -> parent
            else -> initial
        }
        updatePathComponents()
        loadItems()
        return true
    }

    fun navigateToPathComponent(index: Int) {
        val components = _uiState.value.pathComponents
        val candidate = if (index <= 0) "" else {
            components.drop(1).take(index).joinToString("/")
        }
        // T145: clamp breadcrumb taps to the initial entry directory
        // so the user can't escape "above" Chat Files via the path bar
        // either. Anything that would resolve above the entry dir snaps
        // back to it.
        val initial = initialRelativePath
        relativePath = when {
            initial.isEmpty() -> candidate
            candidate == initial || candidate.startsWith("$initial/") -> candidate
            else -> initial
        }
        updatePathComponents()
        loadItems()
    }

    /**
     * [da1031-C] Linux (PRoot) path of [item] — the same root + relativePath +
     * name mapping [updatePathComponents] uses for currentLinuxPath, so the
     * read-only-mount guard sees the path the agent / shell sees. Null when
     * this browser has no linux root: nothing guest-visible to guard then.
     */
    private fun linuxPathOf(item: FileItem): String? =
        (displayLinuxPrefix ?: linuxRootPath)?.let { root ->
            val base = root.trimEnd('/')
            if (relativePath.isEmpty()) "$base/${item.name}"
            else "$base/$relativePath/${item.name}"
        }

    fun deleteItem(item: FileItem) {
        viewModelScope.launch(fileOperationDispatcher) {
            // [R2 F-04] CANONICAL guard, decided on the file that will actually
            // be removed — not on the logical path the browser displays.
            //
            // The defect: every refusal below reads `linuxPathOf(item)`, a
            // string built from the browser's root + breadcrumb + name. Enter a
            // link (`ln -s /var/minis/memory /var/minis/workspace/m`) and the
            // listing shows the REAL memory files while the logical path still
            // says `/var/minis/workspace/m/SOUL.md` — writable by every
            // string-based rule, so the trash icon deleted the file. A guard
            // that asks about a spelling answers about a spelling.
            val canonicalRefusal = canonicalDeleteRefusal(
                target = item.hostPath,
                isSymlink = item.isSymlink,
                allowedRoots = sandboxRoots(),
                readOnlyRoots = readOnlyRoots(),
            )
            when (canonicalRefusal) {
                CanonicalDeleteRefusal.OutsideSandbox -> {
                    AppLogger.warning(
                        "FileBrowser",
                        "refused delete of ${item.name}: canonical target " +
                            "${item.hostPath.absolutePath} is outside every sandbox root",
                    )
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Outside the sandbox: ${item.name} cannot be deleted",
                    )
                    return@launch
                }
                CanonicalDeleteRefusal.ReadOnlyZone -> {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Read-only folder: ${item.name} cannot be deleted",
                    )
                    return@launch
                }
                null -> Unit
            }
            // [da1031-C] ISSUE-04 A1: guard BEFORE deleting. T219's read-only
            // mount lock is software-only (PRoot `-b` has no read-only
            // modifier) and File.delete() works on the HOST path, which that
            // lock never covers — without this check a Locked mount's files
            // were really deleted from the UI. Same refusal wording family as
            // DebugRPCHandler's handleWriteFile guard.
            linuxPathOf(item)?.let { lp ->
                if (com.rikkaminis.app.sandbox.PRootKernel.isLinuxPathUnderReadOnlyMount(lp)) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Read-only mount: ${item.name} cannot be deleted",
                    )
                    return@launch
                }
                // [deep-audit-1033 I01] The browser also opens the two
                // read-only shared folders (Settings → Shared Folders →
                // Skills / Memory, and the agent's /var/minis/{skills,memory}
                // shortcuts). Their `writable = false` reached the AccessBadge
                // and nothing else, so the trash icon really removed SOUL.md /
                // GLOBAL.md / SKILL.md from disk while the screen said
                // Read-Only. Refusal shape is deliberately the same as the
                // mount guard above; the predicate comes from the registry
                // (single source), not a second path list here.
                if (SharedFolderRegistry.isLinuxPathUnderReadOnlySharedDir(lp)) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Read-only folder: ${item.name} cannot be deleted",
                    )
                    return@launch
                }
                // [incr1116-A F-01] The delete button reached directories in
                // this window, and the guest dirs under /var/minis are PRoot
                // bind-mount TARGETS. Removing one deletes the placeholder the
                // bind needs, and PRoot silently skips a bind whose target is
                // missing — so the whole sandbox loses that view with one tap
                // (the session's files are untouched, which makes it worse: the
                // size still shows, the path is gone). Ancestors too: /var and
                // /var/minis are a back-press away in the rootfs browser.
                if (com.rikkaminis.app.sandbox.PRootKernel.isLinuxPathMountPointOrAncestor(lp)) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Mount point: ${item.name} cannot be deleted",
                    )
                    return@launch
                }
            }
            try {
                // T10-L5: File.delete()/deleteRecursively() return false instead
                // of throwing on a read-only mount or missing permission. The
                // old code ignored the result, so loadItems() re-listed the
                // still-present file and the user saw nothing happen.
                // [R4-V F-11] A SYMLINK is never deleted *through*: Kotlin's
                // deleteRecursively() walks the RESOLVED tree (FileTreeWalk →
                // File.listFiles(), which follows the link), so removing a
                // directory-shaped link wiped the target directory's contents
                // (measured: 2 files before, 0 after — report §3 arm A) while
                // leaving the link's target in place. Unlink the name instead.
                // [review-1114 A] The SAME reasoning applies one level down: a
                // plain directory that CONTAINS a link to the outside was
                // recursed into through that link, so deleting "this folder"
                // also emptied an unrelated tree (measured: victim's own 2
                // entries + outside's 2 files = 4 entries gone). The recursive
                // arm now delegates to the sweeper's no-follow walker, which
                // unlinks a nested link as a link and never enters its target.
                //
                // Deliberately INLINE (not extracted into a helper): the
                // read-only guard two screens up must sit in the SAME function
                // as a real removal call — `readonly_mount_write_guard` anchors
                // on this pair, and a guard whose write moved elsewhere is a
                // guard the scanner can no longer verify.
                val deleted = if (shouldDeleteRecursively(item.isDirectory, item.isSymlink)) {
                    internalGuestTmpDeleteTreeNoFollow(item.file.toPath())
                } else {
                    item.file.delete()
                }
                if (!deleted) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Delete failed: ${item.name}",
                    )
                    return@launch
                }
                // [deep-audit-1048 B-05] The Storage page keeps a
                // process-lifetime snapshot (`StorageSnapshotCache`: rootfs
                // lstat walk + per-session recursive sizing, ~minutes of work)
                // and its re-entry path reuses the cached totals instead of
                // re-walking. Deleting a file / a whole session directory here
                // therefore left the Storage screen showing the pre-delete
                // sizes for the rest of the process. Invalidate on the SUCCESS
                // path only — the failure branch above (and the read-only
                // guards) leave the tree untouched, so dropping the cache there
                // would only cost a rescan.
                invalidateStorageSnapshot()
                loadItems()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message)
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun setSort(
        key: FileSortKey = _uiState.value.sortKey,
        ascending: Boolean = _uiState.value.sortAscending,
        foldersFirst: Boolean = _uiState.value.foldersFirst,
    ) {
        val newState = _uiState.value.copy(
            sortKey = key,
            sortAscending = ascending,
            foldersFirst = foldersFirst,
        )
        val sorted = applySort(rawItems, newState)
        _uiState.value = newState.copy(items = sorted, isEmpty = sorted.isEmpty())
    }

    private fun applySort(
        items: List<FileItem>,
        state: FileBrowserUiState,
    ): List<FileItem> {
        val nameAsc = Comparator<FileItem> { a, b ->
            a.name.compareTo(b.name, ignoreCase = true)
        }
        val keyComparator: Comparator<FileItem> = when (state.sortKey) {
            FileSortKey.NAME -> nameAsc
            FileSortKey.MODIFIED -> Comparator { a, b ->
                val cmp = a.modifiedMs.compareTo(b.modifiedMs)
                if (cmp != 0) cmp else nameAsc.compare(a, b)
            }
            FileSortKey.SIZE -> Comparator { a, b ->
                val cmp = a.size.compareTo(b.size)
                if (cmp != 0) cmp else nameAsc.compare(a, b)
            }
            FileSortKey.KIND -> Comparator { a, b ->
                val ea = if (a.isDirectory) "" else a.file.extension.lowercase()
                val eb = if (b.isDirectory) "" else b.file.extension.lowercase()
                val cmp = ea.compareTo(eb)
                if (cmp != 0) cmp else nameAsc.compare(a, b)
            }
        }
        val directional = if (state.sortAscending) keyComparator else keyComparator.reversed()
        val final: Comparator<FileItem> = if (state.foldersFirst) {
            // Directories first regardless of sort direction.
            Comparator<FileItem> { a, b ->
                when {
                    a.isDirectory && !b.isDirectory -> -1
                    !a.isDirectory && b.isDirectory -> 1
                    else -> 0
                }
            }.then(directional)
        } else {
            directional
        }
        return items.sortedWith(final)
    }

    private fun loadItems() {
        // Navigation calls on Main start immediately; delete's IO callback joins
        // the same owner/commit lane before reading any mutable browser state.
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val generation = ++loadGeneration
            val requestPath = relativePath
            val hostPath = currentHostPath
            val showHidden = _uiState.value.showHidden
            // A re-sort while the replacement is in flight must not revive the
            // previous directory's cached entries under the new breadcrumbs.
            rawItems = emptyList()
            _uiState.value = _uiState.value.copy(
                items = emptyList(),
                isLoading = true,
                isEmpty = false,
                errorMessage = null,
            )
            var loadedItems = emptyList<FileItem>()
            var loadError: Throwable? = null
            try {
                loadedItems = withContext(listingDispatcher) {
                    directoryReader?.let { return@withContext it(hostPath, showHidden) }
                    // Resolve symlinks for listing but keep logical path for breadcrumbs.
                    // [R5-F F-F-02] Guest-absolute symlinks need the same bounded
                    // guest-to-host mapping as before, including at ancestor depth.
                    val resolvedPath = resolveListingHostDir(
                        hostPath,
                        ::resolveGuestAbsoluteTarget,
                        sandboxRoots(),
                    )
                    resolvedPath.listFiles()
                        ?.filter { showHidden || !it.name.startsWith(".") }
                        ?.mapNotNull { FileItem.from(it, ::resolveGuestAbsoluteTarget, sandboxRoots()) }
                        ?: emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e
            }
            // Native directory IO need not honour cancellation. Both success
            // and failure must prove ownership HERE, on Main, before any write.
            if (generation != loadGeneration || requestPath != relativePath ||
                hostPath.absolutePath != _uiState.value.currentPath ||
                showHidden != _uiState.value.showHidden
            ) return@launch
            rawItems = loadedItems
            val sorted = applySort(rawItems, _uiState.value)
            _uiState.value = _uiState.value.copy(
                items = sorted,
                isLoading = false,
                isEmpty = sorted.isEmpty(),
                errorMessage = loadError?.message,
            )
        }
    }

    private fun updatePathComponents() {
        val components = mutableListOf(rootLabel)
        if (relativePath.isNotEmpty()) {
            components += relativePath.split("/").filter { it.isNotEmpty() }
        }

        _uiState.value = _uiState.value.copy(
            pathComponents = components,
            // T145: only "can go back" when we're below the entry
            // directory; at the initial root the back button should
            // exit the screen instead of trying to ascend into the
            // wrapping rootfs.
            canGoBack = relativePath != initialRelativePath,
            currentPath = currentHostPath.absolutePath,
            // [T-android-file-context-copy-abs-path] Linux dir path for the
            // "Copy Absolute Path" menu item — derived from the VM's own
            // linuxRootPath + relativePath (the same mapping directory
            // listings route through), not host-path string-fiddling.
            // [T-android-copy-abs-path-fullpath] Prefer the dedicated
            // displayLinuxPrefix when supplied (session storage browser), else
            // fall back to linuxRootPath (Chat Files / shared folders).
            currentLinuxPath = (displayLinuxPrefix ?: linuxRootPath)?.let { root ->
                if (relativePath.isEmpty()) root.trimEnd('/')
                else "${root.trimEnd('/')}/$relativePath"
            },
        )
    }

    companion object {
        val dateFormatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())

        /** SharedPreferences file for FileBrowser display options. */
        const val PREFS_NAME = "file_browser_prefs"
        /** Key for the "show hidden files" toggle. Persists across app launches. */
        const val PREF_KEY_SHOW_HIDDEN = "file_browser_show_hidden"

        fun formatDate(timestamp: Long): String {
            return if (timestamp > 0) dateFormatter.format(Date(timestamp)) else "—"
        }
    }
}
