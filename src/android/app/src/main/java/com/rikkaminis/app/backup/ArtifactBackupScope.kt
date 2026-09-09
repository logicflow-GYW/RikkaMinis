package com.rikkaminis.app.backup

import java.io.File

/**
 * [T-auto-backup-assets] Selection policy for the artifact backup section —
 * the user's *task outputs* (`/var/minis/shared`, plus MCP memory-server data
 * like knowledge-graph JSONL), NOT the chat transcript.
 *
 * The user's model of this app: it is an agent app, not a chat app. Chat
 * history is 90% process byproduct and deliberately excluded from the
 * automatic backup (manual full exports keep their own chat window setting).
 * What MUST be protected is what the agent *produced*: handoff docs, audit
 * reports, tools, evaluations, knowledge graphs.
 *
 * Pure JVM so the include/exclude rules are unit-testable without Android.
 */
object ArtifactBackupScope {

    /** Per-file cap: anything larger is regenerable or an environment payload
     *  (Alpine rootfs bundles etc.), not a user artifact. */
    const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /** Total budget for one archive. The automatic backup must stay a few MB,
     *  not a 64MB blob: it runs on every foreground pass. */
    const val MAX_ARCHIVE_BYTES = 12L * 1024 * 1024

    /** Top-level directory names inside /var/minis/shared that are excluded
     *  from the artifact backup (regenerable environment payloads). */
    private val EXCLUDED_DIRS = setOf("sandbox-env")

    /** File extensions that count as artifacts. Text-only: the automatic
     *  backup must not silently grow into a media hoarder. */
    val DEFAULT_ALLOWED_EXTENSIONS = setOf(
        "md", "txt", "json", "jsonl", "xml", "yml", "yaml", "toml",
        "py", "sh", "js", "ts", "tsx", "css", "html",
        "kt", "java", "swift", "c", "h", "cpp", "rs", "go",
        "csv", "tsv", "log",
    )

    data class SelectedFile(
        /** Path relative to the shared root, using '/' separators. */
        val relPath: String,
        val size: Long,
    )

    data class Selection(
        val files: List<SelectedFile>,
        val totalBytes: Long,
        /** Count of files skipped because they exceeded [MAX_FILE_BYTES]. */
        val oversizedSkipped: Int,
        /** Count of non-text files skipped by the extension allow-list. */
        val nonTextSkipped: Int,
    )

    /** Select artifact files under [sharedRoot]. Pure: no I/O beyond the walk. */
    fun select(
        sharedRoot: File,
        maxFileBytes: Long = MAX_FILE_BYTES,
        maxArchiveBytes: Long = MAX_ARCHIVE_BYTES,
        allowedExtensions: Set<String> = DEFAULT_ALLOWED_EXTENSIONS,
    ): Selection {
        val files = ArrayList<SelectedFile>()
        var total = 0L
        var oversized = 0
        var nonText = 0
        if (!sharedRoot.isDirectory) return Selection(files, 0, 0, 0)

        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                if (child.isDirectory) {
                    // Only the shared root has environment payloads; nested
                    // directories are always walked.
                    if (prefix.isEmpty() && child.name in EXCLUDED_DIRS) continue
                    walk(child, if (prefix.isEmpty()) child.name else "$prefix/${child.name}")
                    continue
                }
                val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                val ext = child.extension.lowercase()
                if (ext !in allowedExtensions) {
                    nonText++
                    continue
                }
                val size = child.length()
                if (size > maxFileBytes) {
                    oversized++
                    continue
                }
                // Budget-aware: stop once the archive budget is exhausted;
                // keep counting what was skipped for the report.
                if (total + size > maxArchiveBytes) {
                    oversized++
                    continue
                }
                files.add(SelectedFile(rel, size))
                total += size
            }
        }
        walk(sharedRoot, "")
        return Selection(files, total, oversized, nonText)
    }
}
