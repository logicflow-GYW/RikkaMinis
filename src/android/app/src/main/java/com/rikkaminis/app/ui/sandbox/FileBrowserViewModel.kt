package com.rikkaminis.app.ui.sandbox

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.rikkaminis.app.ui.settings.SharedFolderRegistry
import com.rikkaminis.app.ui.settings.invalidateStorageSnapshot
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
 * File item model — corresponds to iOS FileItem.
 */
@Immutable
data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    /** lastModified() in epoch ms, 0 when unavailable. */
    val modifiedMs: Long = 0L,
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

    /** Mirrors iOS FileItem.formattedDate: today=time, yesterday="Yesterday", <7d=weekday, else short date. */
    val formattedDate: String
        get() {
            if (modifiedMs <= 0L) return ""
            val date = Date(modifiedMs)
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { time = date }
            val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            if (sameDay) return timeFormatter.format(date)
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            val isYesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            if (isYesterday) return "Yesterday"
            val daysAgo = (now.timeInMillis - then.timeInMillis) / (24L * 60 * 60 * 1000)
            if (daysAgo in 0..6) return weekdayFormatter.format(date)
            return shortDateFormatter.format(date)
        }

    companion object {
        private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
        private val weekdayFormatter = SimpleDateFormat("EEEE", Locale.getDefault())
        private val shortDateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

        fun from(file: File, resolveGuestAbsolute: (String) -> File? = { null }): FileItem? {
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
                resolveListedEntry(file, rawTarget, resolveGuestAbsolute)
            } else file

            return FileItem(
                file = file,
                name = file.name,
                isDirectory = resolved.isDirectory,
                isSymlink = isSymlink,
                size = if (resolved.isDirectory) 0L else resolved.length(),
                modifiedMs = resolved.lastModified(),
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    /** Cached unsorted listing so re-sort doesn't need to re-stat the dir. */
    private var rawItems: List<FileItem> = emptyList()

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
        if (mapped != null && mapped.exists()) return mapped
        return File(rawTarget).takeIf { it.exists() }
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
        viewModelScope.launch(Dispatchers.IO) {
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
                val deleted = if (shouldDeleteRecursively(item.isDirectory, item.isSymlink)) {
                    item.file.deleteRecursively()
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
        _uiState.value = _uiState.value.copy(isLoading = true)
        val hostPath = currentHostPath
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Resolve symlinks for listing but keep logical path for breadcrumbs
                val resolvedPath = try {
                    hostPath.toPath().toRealPath().toFile()
                } catch (_: Exception) {
                    hostPath
                }

                // Dotfile filter is gated on the user-facing "Show Hidden
                // Files" preference (T-hidden-files a3e7f1d0). Snapshot
                // showHidden here so we don't see a torn read if the
                // setter races with this background load.
                val showHidden = _uiState.value.showHidden
                val files = resolvedPath.listFiles()
                    ?.filter { showHidden || !it.name.startsWith(".") }
                    // [R4-V F-11] Pass the guest→host mapper so absolute link
                    // targets (a PRoot link stores `/var/minis/…`) describe the
                    // file the shell meant, not a nonexistent host path.
                    ?.mapNotNull { FileItem.from(it, ::resolveGuestAbsoluteTarget) }
                    ?: emptyList()

                rawItems = files
                val sorted = applySort(files, _uiState.value)
                _uiState.value = _uiState.value.copy(
                    items = sorted,
                    isLoading = false,
                    isEmpty = sorted.isEmpty(),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    items = emptyList(),
                    isLoading = false,
                    isEmpty = true,
                    errorMessage = e.message,
                )
            }
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
