package com.rikkaminis.app.backup

import java.io.File

/**
 * [feat/auto-backup-external-mirror] Which files the external mirror carries.
 *
 * The mirror exists because the daily auto backup, however good, lives in the
 * same `filesDir` as the data it protects — when that directory is rebuilt or
 * rolled back (2026-10-29 incident), the working copy AND its backup vanish
 * together. A second copy under `Download/RikkaMinis/backup/` sits on a
 * different owner (MediaStore) in a user-visible location, so it survives that
 * failure mode, an uninstall, and a "clear app data".
 *
 * Scope is deliberately NOT "everything under /var/minis/shared": measured
 * 2026-10-29, `shared/` is 749 MB of which `work/` is 702 MB (git clones with a
 * remote + regenerable probe rigs). What the mirror carries is the set that
 * cannot be regenerated and is small enough to rewrite every ten minutes:
 *
 *  - `memory/` — daily logs, GLOBAL.md, rollup (94 files / 6.4 MB)
 *  - `skills/` — SKILL.md bodies and scripts (140 text files / 3.8 MB)
 *  - `shared/` — top-level documents (backlog, sandbox-limits, handoff, reports)
 *  - `shared/work/<batch>/{fix-tasks,triage,dispatch,reports,…}` — the documents
 *    of in-flight work. These are the ones the 2026-10-29 incident destroyed and
 *    the daily backup could NOT save (its artifact archive caps at 12 MiB while
 *    `work/` holds 201 MB of text ⇒ ~6% coverage, chosen by directory-name
 *    order). 1549 files / 24.4 MB.
 *
 * Deliberately pure `java.io` (no Android, no Context): the JVM harness
 * compiles this file verbatim and pins the selection rules (MirrorScopeTest).
 */
internal object MirrorScope {

    /** Mirror prefix for `/var/minis/memory`. */
    const val PREFIX_MEMORY = "memory"

    /** Mirror prefix for `/var/minis/skills`. */
    const val PREFIX_SKILLS = "skills"

    /** Mirror prefix for `/var/minis/shared`. */
    const val PREFIX_SHARED = "shared"

    /**
     * Per-file cap, same rationale as [ArtifactBackupScope.MAX_FILE_BYTES]: a
     * multi-MB file under these trees is an environment payload or an export,
     * not a document someone would miss. Kept as its own constant so the two
     * policies can diverge without one silently changing the other.
     */
    const val MAX_FILE_BYTES = 4L * 1024 * 1024

    /**
     * Directory names inside `shared/work/<batch>/` that hold work documents.
     *
     * This is a WHITELIST, not a blacklist, and that is the point: a batch
     * directory also holds `repo/`, `wt-…` worktree directories (git worktrees, 700 MB) and probe
     * rigs. Adding a directory here is the one decision that grows the mirror;
     * everything unnamed is ignored by construction, so a new probe directory
     * cannot silently inflate the sync cost.
     */
    val WORK_KEY_DIRS = setOf(
        "fix-tasks", "triage", "dispatch", "reports", "fix-reports",
        "snapshots", "incidents", "design",
    )

    /** Text extensions — the same intent as the artifact backup: documents and
     *  scripts, never media or binaries. */
    val TEXT_EXTENSIONS: Set<String> = ArtifactBackupScope.DEFAULT_ALLOWED_EXTENSIONS

    /** One file selected for mirroring. [relPath] is relative to the mirror
     *  root and already carries the source prefix (`memory/…`, `shared/…`).
     *  [file] is the source the sync reads bytes from — carried so a sync
     *  never has to re-resolve the host path from the relPath (the mapping
     *  lives in exactly one place). */
    data class MirrorFile(
        val relPath: String,
        val size: Long,
        val modifiedMs: Long,
        val file: File,
    )

    /** `memory/` — the whole tree; it is small and every file is authored. */
    fun selectMemory(root: File): List<MirrorFile> =
        select(root, PREFIX_MEMORY, includeAllDirs = { _, name -> name != ".git" })

    /** `skills/` — the whole tree, text only (the directory also holds
     *  bundled binaries that are part of the app, not user work). */
    fun selectSkills(root: File): List<MirrorFile> =
        select(root, PREFIX_SKILLS, includeAllDirs = { _, name -> name != ".git" })

    /**
     * `shared/` — top-level files plus work documents.
     *
     * Depth rules (each one exists to keep a known large or regenerable subtree
     * out of a sync that runs every ten minutes):
     *  - at the shared root only `work/` is entered (no `archive/`, no
     *    `port-v114/`, no `tools/` — those are either history or binaries);
     *  - at `work/` the next level is a batch name: always entered;
     *  - at `work/<batch>/` only [WORK_KEY_DIRS] are entered — this is where
     *    `repo/` and `wt-…` worktree directories (the 700 MB) are excluded;
     *  - deeper than `work/<batch>/<keydir>/` everything is entered.
     */
    fun selectShared(root: File): List<MirrorFile> =
        select(root, PREFIX_SHARED, includeAllDirs = ::includeSharedDir)

    /**
     * True when `parentRel/name` (a directory) may be walked for [PREFIX_SHARED].
     * [parentRel] is the directory's own path relative to the shared root, ""
     * at the root. Pure and public within the module so the depth rules are
     * testable one by one.
     */
    fun includeSharedDir(parentRel: String, name: String): Boolean {
        if (name == ".git") return false
        return when {
            parentRel.isEmpty() -> name == "work"
            parentRel == "work" -> true // one level of batch names
            parentRel.count { it == '/' } == 1 && parentRel.startsWith("work/") ->
                name in WORK_KEY_DIRS // batch/<dir>
            else -> true
        }
    }

    /**
     * Shared walker for the three sources.
     *
     * [includeAllDirs] decides directory descent; files are filtered by
     * [TEXT_EXTENSIONS] and [maxFileBytes]. Symbolic links are skipped
     * entirely: a link inside the mirror scope would make "copy this file" mean
     * "copy whatever it points at", and the mirror has no cycle guard by
     * design — refusing links is the cheaper, safer rule.
     *
     * Symlink/cycle note: `File.isDirectory` follows links, so the check below
     * happens before the descent for that reason too.
     */
    fun select(
        root: File,
        prefix: String,
        maxFileBytes: Long = MAX_FILE_BYTES,
        allowedExtensions: Set<String> = TEXT_EXTENSIONS,
        includeAllDirs: (parentRel: String, name: String) -> Boolean,
    ): List<MirrorFile> {
        val out = ArrayList<MirrorFile>()
        if (!root.isDirectory) return out

        fun walk(dir: File, rel: String) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                val name = child.name
                // A path we cannot represent in the manifest must not be
                // selected at all — the encoding is one record per line.
                if (name.contains('\n') || name.contains('\t')) continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                // Files.isSymbolicLink is the only reliable test here: comparing
                // toRealPath() misrebinds relative paths and PRoot-translated
                // ones, which would reject every ordinary file.
                val isLink = runCatching { java.nio.file.Files.isSymbolicLink(child.toPath()) }
                    .getOrDefault(true)
                if (isLink) continue
                if (child.isDirectory) {
                    if (includeAllDirs(rel, name)) walk(child, childRel)
                    continue
                }
                if (!child.isFile) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in allowedExtensions) continue
                val size = runCatching { child.length() }.getOrNull() ?: continue
                if (size > maxFileBytes) continue
                val modified = runCatching { child.lastModified() }.getOrDefault(0L)
                out.add(MirrorFile("$prefix/$childRel", size, modified, child))
            }
        }

        walk(root, "")
        // Stable order: the manifest diff and the tests both benefit, and the
        // copy order becomes deterministic across devices.
        return out.sortedBy { it.relPath }
    }
}
