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

    /** Directory names that are NEVER artifacts, at ANY depth.
     *
     *  [da1034-A1] `MCPOAuthStore.bridgeDir` is `<minis-global/mcp-servers>/oauth`
     *  — the in-guest token bridge for `minis-mcp-cli`. It holds LIVE
     *  credentials in plaintext: `<server>.json` carries `access_token` /
     *  `refresh_token` / `client_secret` / `token_endpoint` / `client_id` /
     *  `resource`, and a pending `<server>.secret` handoff file carries the
     *  client secret itself. `includeSecrets=false` gates the provider /
     *  envVar / mcpServers JSON sections but NOT this archive, so the
     *  auto-backup (and every WebDAV copy of it) was shipping those tokens
     *  verbatim. The walk drops the directory by name, at every level rather
     *  than only at a root, because the same walker runs over the mcp root
     *  whose bridge dir is one level below `minis-global/`. */
    private val DENIED_DIRS = setOf("oauth")

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
        /** Count of files skipped as oversized: over [MAX_FILE_BYTES], or
         *  skipped because the archive budget was exhausted. The two reasons
         *  are deliberately conflated - the backup manifest only reports a
         *  total, not a per-reason split (ponytail: merged counter | ceiling:
         *  a user debugging why an artifact is missing needs the reason |
         *  upgrade trigger: someone surfaces per-reason counts in the UI). */
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

        // [audit-0917] Guard symlink cycles: a link pointing at an ancestor
        // would otherwise recurse forever (File.isDirectory follows links)
        // and blow the stack. canonicalPath dedupes .. and absolute prefixes,
        // so the first visit wins and any revisit is skipped.
        //
        // [FIX-R3-D / F-06] …but "does not loop" is not "stays inside". The
        // walk only looked at the LINK's own name, so `shared/bridge_bak ->
        // ../../mcp-servers/oauth` was walked and archived as
        // `shared/bridge_bak/<server>.json` — plaintext OAuth tokens riding a
        // credentials-free (`includeSecrets=false`) backup all the way to the
        // user's WebDAV server, past both DENIED_DIRS and the restore-side
        // `mcp/oauth/` filter (neither matches that entry name). The resolved
        // target now has to live under the shared root AND its resolved name
        // has to survive DENIED_DIRS.
        val rootCanon = try { sharedRoot.canonicalFile.path } catch (_: Exception) {
            return Selection(files, 0, 0, 0)
        }
        val rootPrefix = if (rootCanon.endsWith(File.separator)) rootCanon else rootCanon + File.separator
        fun resolveInside(child: File): File? = try {
            val resolved = child.canonicalFile
            val path = resolved.path
            // Fail-closed: a link that escapes, an unreadable inode, and a
            // canonicalisation error are all "not an artifact of this root".
            if (path == rootCanon || path.startsWith(rootPrefix)) resolved else null
        } catch (_: Exception) {
            null
        }

        val visited = HashSet<String>()
        fun walk(dir: File, prefix: String) {
            val canon = try { dir.canonicalPath } catch (_: Exception) { return }
            if (!visited.add(canon)) return
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                // [FIX-R3-D / F-06] Resolve FIRST: `child.name` is the link's
                // own token, `target.name` is what the bytes actually are.
                val target = resolveInside(child) ?: continue
                if (child.isDirectory) {
                    // [da1034-A1] Credential directories are dropped wherever
                    // they appear in the tree — see DENIED_DIRS.
                    if (child.name in DENIED_DIRS || target.name in DENIED_DIRS) continue
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
