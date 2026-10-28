package com.rikkaminis.app.data.repository

import android.content.Context
import com.rikkaminis.app.tools.SkillInfo
import com.rikkaminis.app.tools.SubagentSkill
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages skill metadata (SQLite) and SKILL.md files on disk.
 * Mirrors iOS SkillStore architecture:
 *   - Metadata in `skills.db` (name, description, version, source, enabled)
 *   - SKILL.md in `minis-global/skills/<id>/SKILL.md`
 *   - Session overrides in `session_skill_overrides` table
 *   - Prompt fragment generation for system prompt injection
 */

/**
 * [dv16-A05] Pure decision core of the sparse-clear branch in
 * [SkillRepository.setSessionOverride]. When the requested value equals the
 * CURRENT global default, the override row is DELETED instead of pinned — so
 * a later flip of the global default propagates to the session (a
 * permanently-pinned row would leave the session stuck at the old value).
 * `null` global (unknown / not-yet-loaded skill) conservatively keeps the
 * row (write). Mirrors [MCPRepository.setSessionOverride]'s three branches —
 * same shape, same precedence; the twin keeps its own copy only because its
 * SQL names differ (mcp_session_overrides), the decision itself is this
 * function.
 *
 * Top-level (not a member) so it is JVM-testable with zero Android deps.
 */
internal fun shouldDeleteSessionSkillOverride(globalEnabled: Boolean?, requestedEnabled: Boolean): Boolean =
    globalEnabled != null && requestedEnabled == globalEnabled

/**
 * [dv22-B4] Pure merge core of [SkillRepository.sessionEnablementMap] — the
 * batched read that replaced one [SkillRepository.isEnabledForSession]
 * rawQuery PER SKILL in the slash menu (N queries per keystroke → one per
 * list). Precedence is IDENTICAL to [SkillRepository.isEnabledForSession]:
 * session override row → global `isEnabled` → absent (false).
 *
 * Top-level (not a member) so the precedence is JVM-testable with zero
 * Android deps, and so a counting fake can prove the merge itself issues no
 * queries: [overrideRows] is the result of ONE batched SELECT, [globalEnabled]
 * reads the in-memory `_skills` list.
 */
internal fun mergeSessionSkillEnablement(
    ids: List<String>,
    overrideRows: Map<String, Boolean>,
    globalEnabled: (id: String) -> Boolean?,
): Map<String, Boolean> = ids.associateWith { id ->
    overrideRows[id] ?: globalEnabled(id) ?: false
}

class SkillRepository(private val context: Context) {

    companion object {
        private const val TAG = "SkillRepository"
        private const val DB_NAME = "skills.db"
        private const val DB_VERSION = 3
        // [s38a] 20 -> 40: 8 of 28 skills never entered the prompt under the
        // old budget (4 of them core engineering skills — cloudflare-fullright-ops
        // is the standard path in GLOBAL.md). Token cost grows linearly; the
        // 3-tier priority below is unchanged. Upgrade path if skills > 40:
        // keyword prefilter -> embedding retrieval (see backlog §38-A).
        private const val MAX_SKILLS_IN_PROMPT = 40
        // [s39] 200 -> 400: 2 of 31 shipped skills were cut mid-sentence
        // (tierflow-key-harvest 243 / rikkaminis-dev-methodology 206), and the
        // cut tail is always the trigger clause — exactly the part the agent
        // needs to decide whether to read the skill at all. Truncation happens
        // at render time in [skillPromptFragment], never on the stored row, so
        // no migration is involved. 400 covers the longest shipped description
        // with headroom; revisit (sentence-boundary cut) only if a real
        // description exceeds it.
        private const val MAX_SKILL_DESC_LENGTH = 400
        /** [dv22-B4] Ids per batched `IN (…)` SELECT in
         *  [sessionEnablementMap]. SQLITE_MAX_VARIABLE_NUMBER is 999 on older
         *  SQLite builds; 400 leaves rom for the session id and keeps the
         *  query count constant (a skill list this long is already an
         *  anomaly, not a path to optimise). */
        private const val SESSION_OVERRIDE_QUERY_CHUNK = 400
        private const val RECENT_WINDOW_MS = 7L * 24 * 3600 * 1000
        private const val RECENT_SLOTS = 10
        private const val NORMALIZE_THRESHOLD = 1000.0

        /** [T-android-skill-export] How long an exported zip stays on disk
         *  before the next export sweeps it. 24h matches iOS — long enough that
         *  any Save-to-Files / AirDrop / upload consumer has finished. */
        private const val EXPORT_TTL_MS = 24L * 3600 * 1000

        /**
         * [F-223] Rebuild a SKILL.md, preserving every frontmatter key that isn't
         * one of the three managed ones. Pure string work (no Android deps) so it is
         * covered by JVM tests — the same split
         * [SubagentSkill.extractFrontmatterBlock] uses.
         *
         * Managed keys are replaced in place (keeping their position and any sibling
         * keys); unmanaged keys are carried through verbatim. When [existing] has no
         * frontmatter, a fresh 3-key block is emitted — identical to the pre-fix
         * behaviour for that case.
         *
         * [F-06] Both sides now speak YAML block scalars: `key: |` / `key: >` (with
         * any chomping indicator) opens a block whose blank-or-indented lines belong
         * to that key. [parseSkillMd] has resolved those blocks into real newlines
         * since T151, so a document carrying `description: |` reaches this function
         * with a MULTI-LINE value — writing it raw put the continuation lines at
         * column 0 inside the frontmatter, where the next read treated them as keys
         * (a description line `version: 9.9` silently rewrote the version) and
         * dropped the rest (the description came back truncated to its first line).
         * Contract: a managed value is emitted as `key: <value>` when it is a single
         * line and as `key: |` + 2-space-indented lines otherwise, and a managed key
         * that arrived as a block is emitted with its old BODY removed.
         */
        internal fun mergeFrontmatter(
            existing: String?,
            name: String,
            description: String,
            version: String,
            body: String,
        ): String {
            val raw = existing?.trimStart().orEmpty()
            val src = raw.lines()
            var frontmatterEnd = -1
            if (raw.startsWith("---") && src.isNotEmpty()) {
                for (i in 1 until src.size) {
                    if (src[i].trim() == "---") {
                        frontmatterEnd = i
                        break
                    }
                }
            }
            // No frontmatter to preserve: emit the canonical 3-key block.
            // [F-06] Through the same emitter — a description carrying newlines
            // needs the block form here too, or the very first write of a new skill
            // puts its continuation lines at column 0 inside the frontmatter.
            if (frontmatterEnd < 0) {
                return buildString {
                    appendLine("---")
                    for (entry in frontmatterEntry("name", name)) appendLine(entry)
                    for (entry in frontmatterEntry("description", description)) appendLine(entry)
                    for (entry in frontmatterEntry("version", version)) appendLine(entry)
                    appendLine("---")
                    append(body)
                }
            }
            // Walk the frontmatter, swapping the managed keys in place and
            // passing every other line (comments, subagent, max_turns,
            // allowed_tools, ...) through verbatim. [fix/da1023-E] The walk
            // starts at line 1: line 0 is the OPENING `---` delimiter, and
            // emitting it again below doubled the delimiter on every rewrite
            // whose base already carried a block — `---\n---\nname: …` — which
            // parseSkillMd reads as a 2-line frontmatter with no `name:` key
            // and returns null for, and extractFrontmatterBlock truncates to
            // `---\n---`, dropping every extended key. In other words the
            // first in-app rewrite of any skill used to corrupt its SKILL.md
            // into an unparsable document — the exact class of silent
            // frontmatter loss this branch exists to close.
            val managed = mapOf(
                "name" to name,
                "description" to description,
                "version" to version,
            )
            val seen = mutableSetOf<String>()
            val fm = mutableListOf<String>()
            // [F-06] A managed BLOCK SCALAR loses its body together with its
            // header: the value is rebuilt from [managed] below, so keeping the
            // old `  line one` / `  line two` lines left them behind under a now
            // single-line key — stale text that never goes away on its own, and
            // that a strict YAML reader folds back into the value
            // ("fresh desc line one line two"). [blockBodyUntil] is the first
            // line after the block that is NOT part of it.
            var blockBodyUntil = -1
            for (i in 1 until frontmatterEnd) {
                if (i < blockBodyUntil) continue
                val line = src[i]
                val key = frontmatterKey(line)
                val newValue = if (key != null) managed[key] else null
                if (key != null && newValue != null) {
                    if (isBlockScalarHeader(line.substringAfter(':').trim())) {
                        blockBodyUntil = blockScalarBodyEnd(src, i + 1, frontmatterEnd)
                    }
                    for (entry in frontmatterEntry(key, newValue)) fm.add(entry)
                    seen.add(key)
                } else {
                    fm.add(line)
                }
            }
            // Managed keys absent from the original block still need to exist.
            for ((key, value) in managed) {
                if (key !in seen) for (entry in frontmatterEntry(key, value)) fm.add(entry)
            }
            return buildString {
                appendLine("---")
                for (line in fm) appendLine(line)
                appendLine("---")
                append(body)
            }
        }

        /**
         * The frontmatter KEY a line declares, or null when the line declares no
         * key at all.
         *
         * [F-06] Keys are recognized only at column 0. The pre-fix rule
         * (`line.indexOf(':') > 0`, then `trim()`) counted an INDENTED line as a
         * key, which made a `description: |` body line like `  name: x` look like
         * a second managed key and clobbered nested mappings — while a body line
         * without a colon was silently ignored. Column 0 is also YAML's own rule,
         * and it is what lets [mergeFrontmatter] tell a block body from a key.
         */
        private fun frontmatterKey(line: String): String? {
            if (line.isEmpty() || line[0].isWhitespace()) return null
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            return line.substring(0, colon).trim().lowercase().ifEmpty { null }
        }

        /**
         * `|`, `>`, `>-`, `|+`, `>2`, … — a YAML block-scalar header (the marker
         * followed only by a chomping indicator and/or an explicit indentation
         * digit). [parseSkillMd] resolves both markers into real newlines, so the
         * writer has to recognize the same set ([F-06]).
         */
        private fun isBlockScalarHeader(value: String): Boolean {
            if (value.isEmpty() || (value[0] != '|' && value[0] != '>')) return false
            return value.drop(1).all { it.isDigit() || it == '-' || it == '+' }
        }

        /**
         * First line index at or after [from] that is NOT part of the block scalar
         * opened one line earlier: the body runs while lines are blank or indented,
         * the same rule [parseSkillMd] applies — so the writer and the reader agree
         * on where a block ends.
         */
        private fun blockScalarBodyEnd(src: List<String>, from: Int, limit: Int): Int {
            var j = from
            while (j < limit && (src[j].isEmpty() || src[j][0].isWhitespace())) j++
            return j
        }

        /**
         * Emit one managed key: `key: value` for a single-line value, the block form
         * `key: |` + 2-space-indented lines when the value carries newlines.
         *
         * The block form is not cosmetic ([F-06]): [parseSkillMd] resolves a block
         * into a value WITH newlines, so an imported document hands that value back
         * here on the next write. Emitting it raw put its continuation lines at
         * column 0 inside the frontmatter — the next read then took them for keys
         * (`version: 9.9` rewrote the version) and the value itself came back
         * truncated to its first line.
         */
        private fun frontmatterEntry(key: String, value: String): List<String> {
            if (!value.contains('\n')) return listOf("$key: $value")
            // A trailing newline needs chomping juggling the reader does not model
            // (it trims the resolved block anyway), so the canonical form is a plain
            // `|` with every line indented.
            val lines = value.lines().map { it.trimEnd('\r') }
            return buildList {
                add("$key: |")
                for (line in lines) add(if (line.isEmpty()) "" else "  $line")
            }
        }

        /**
         * [fix/da1023-E] Which frontmatter block a SKILL.md rewrite merges
         * into — the single exit for that decision, and it is memory-first:
         *
         *  - a caller-supplied block (the NEW value being landed: an import,
         *    a URL / file update, a bundled upgrade) wins whenever it is
         *    non-blank, so remote `subagent` / `max_turns` / `allowed_tools`
         *    changes actually reach the disk;
         *  - otherwise the on-disk document is the base, so a caller that only
         *    touches a managed key (rename, metadata edit) preserves every
         *    extended key exactly as the disk holds it — including edits made
         *    outside the app (`file_write` / a shell `sed` in the sandbox);
         *  - both absent → `null`, and [mergeFrontmatter] emits the canonical
         *    3-key block.
         *
         * The pre-fix call site read `existing ?: skill.frontmatter`, i.e. disk
         * first: as soon as a SKILL.md existed, the caller's block was ignored
         * entirely — the opposite of what [mergeFrontmatter]'s own seeding note
         * and the bundled-upgrade KDoc ("writeSkillMd merges against this
         * field") both describe, and the reason an "Update from URL" refreshed
         * name/description/body while silently keeping the stale extended keys.
         *
         * Pure nullability work (no Android deps) so the polarity itself lives
         * under JVM tests; the `writeSkillMd` call site that must use it is
         * pinned by `scripts/scan/skill_frontmatter_roundtrip_check.py`.
         */
        internal fun resolveMergeBase(
            newFrontmatter: String?,
            diskContent: String?,
        ): String? = newFrontmatter?.ifBlank { null } ?: diskContent

        /**
         * [B14b] Rewrite the `name:` line inside the frontmatter block of a
         * SKILL.md. Pure string work (no Android deps) so it is covered by JVM
         * tests — the same split [mergeFrontmatter] uses. The previous inline
         * full-document `replaceFirst` fired on the whole file: when the file
         * has no frontmatter, a `name:` example line in the markdown body was
         * silently rewritten instead of the (absent) frontmatter key.
         *
         * Returns the updated document, or null when there is nothing to
         * rewrite (no frontmatter block, or a frontmatter block without a
         * `name:` line) — callers leave the file untouched in that case.
         *
         * [F-09] The rewrite walks (line, terminator) pairs instead of slicing the
         * document at a char offset. The pre-fix offset (`Σ line.length + 1`, the
         * `+1` being the newline `String.lines()` strips) is correct for LF only:
         * on a CRLF document every line contributes one char more than assumed, so
         * the split point drifted INTO the block — with four frontmatter lines it
         * landed inside the `name:` value, the head ended mid-value, and the rename
         * produced `name: new` immediately followed by the old value's leftover
         * tail (`name: newx`). Splitting per line keeps every terminator byte for
         * byte, so mixed `\r\n` / `\n` documents survive untouched too.
         */
        internal fun renameFrontmatterName(md: String, newName: String): String? {
            val parts = splitKeepingLineEndings(md).toMutableList()
            if (parts.isEmpty() || !parts[0].first.trimStart().startsWith("---")) return null
            var frontmatterEnd = -1
            for (i in 1 until parts.size) {
                if (parts[i].first.trim() == "---") {
                    frontmatterEnd = i
                    break
                }
            }
            if (frontmatterEnd <= 0) return null
            for (i in 1 until frontmatterEnd) {
                val (content, terminator) = parts[i]
                if (!content.startsWith("name:")) continue
                parts[i] = "name: $newName" to terminator
                val rebuilt = buildString {
                    for ((line, eol) in parts) {
                        append(line)
                        append(eol)
                    }
                }
                return if (rebuilt == md) null else rebuilt
            }
            return null
        }

        /**
         * Split [text] into (line content, its own terminator) pairs, so a rewrite
         * can rejoin the document byte-for-byte apart from what it meant to change.
         * Terminators can differ line by line (`\r\n`, `\n`, `\r`) — each is kept
         * exactly as it arrived ([F-09]).
         */
        private fun splitKeepingLineEndings(text: String): List<Pair<String, String>> {
            val out = mutableListOf<Pair<String, String>>()
            var start = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n' || c == '\r') {
                    val end = if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1
                    out.add(text.substring(start, i) to text.substring(i, end))
                    start = end
                    i = end
                } else {
                    i++
                }
            }
            if (start < text.length) out.add(text.substring(start) to "")
            return out
        }

        /**
         * [F-10] The files a skill directory must give up after a manifest-backed
         * sync: everything present locally that the incoming manifest does NOT list,
         * except the top-level SKILL.md (owned by [writeSkillMd], never a sibling).
         *
         * Both landing paths only ever WROTE files — an overwrite import
         * ([importFromArchive]) or an "Update from URL" ([updateFromURL]) re-fetched
         * what upstream still ships and left everything upstream had DELETED on
         * disk, so a skill kept running a script its source no longer has while the
         * UI reported a completed update.
         *
         * Deleting is the destructive half of a sync, so the caller has to hand over
         * a manifest it can prove COMPLETE (every entry written / downloaded). An
         * empty manifest yields no deletions at all: an empty directory listing is
         * indistinguishable from a truncated response, and guessing wrong wipes the
         * skill's payload.
         */
        internal fun planStaleSiblingDeletions(
            existingRelPaths: Collection<String>,
            keepRelPaths: Collection<String>,
        ): List<String> {
            if (keepRelPaths.isEmpty()) return emptyList()
            fun normalize(path: String): String =
                path.replace('\\', '/').trimStart('/').removePrefix("./")
            val keep = keepRelPaths.mapTo(mutableSetOf()) { normalize(it) }
            return existingRelPaths
                .map { normalize(it) }
                .filter { it.isNotEmpty() && it !in keep && !it.equals("SKILL.md", ignoreCase = true) }
                .distinct()
                .sorted()
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Repository-owned scope for fire-and-forget background work that must
     * outlive the calling Composable / ViewModel — notably the sibling-file
     * download that runs after a SKILL.md import. SupervisorJob so a single
     * download failure doesn't poison the next one.
     */
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -- Data Types --

    enum class ImportSource(val value: String) {
        URL("url"),
        FILE("file"),
        BUNDLED("bundled"),
        SESSION("session");

        companion object {
            fun from(value: String): ImportSource =
                entries.find { it.value == value } ?: FILE
        }
    }

    /**
     * Decoded `requirements.json` of a skill: the runtime deps (apk/pip)
     * and the mapping of deployment tier → capability description, per
     * env var. See `loadSkillRequirements()`.
     */
    data class SkillRequirements(
        val apk: List<String> = emptyList(),
        val pip: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val tiers: Map<String, String> = emptyMap(),
    )

    data class Skill(
        val id: String = UUID.randomUUID().toString(),
        override val name: String,
        override val description: String = "",
        val version: String = "1.0.0",
        val importSource: ImportSource = ImportSource.FILE,
        val isEnabled: Boolean = true,
        val installedAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = System.currentTimeMillis(),
        override val body: String = "",
        /**
         * [fix/subagent-frontmatter] Frontmatter block of the on-disk SKILL.md
         * (`---` … `---` included). [body] is frontmatter-stripped by
         * [parseSkillMd], so frontmatter-only fields — `subagent: true`,
         * `max_turns`, `allowed_tools` — must ride on this field or
         * SubagentSkill can never see them. Populated by [loadAll] from disk.
         */
        override val frontmatter: String = "",
        /** Original GitHub URL when importSource is URL (null otherwise). */
        val sourceURL: String? = null,
        /** Cumulative read count of this skill's SKILL.md; normalized to 0–100 after exceeding 1000. */
        val useCount: Double = 0.0,
    ) : SkillInfo

    enum class UsageFrequency { NEVER, LOW, REGULAR, HIGH }

    // -- State --

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    /**
     * [dv22-B4] Bumped by every write to `session_skill_overrides`
     * ([setSessionOverride] / [clearSessionOverrides] /
     * [renameSessionOverrides]). The overrides table is the only part of the
     * session-scoped enablement that does NOT flow through [_skills], so
     * before this counter the two had no shared invalidation channel: the
     * slash menu memo keyed on `.skills` and never re-ran after a toggle
     * inside the SAME session (B-04). Collectors re-read the table when this
     * moves — it is a pure revision number, never an enablement value.
     */
    private val _sessionOverridesRevision = MutableStateFlow(0L)
    val sessionOverridesRevision: StateFlow<Long> = _sessionOverridesRevision.asStateFlow()

    /** Lock serializing the check-then-act sequence in [add] so concurrent
     *  calls with the same id don't create duplicate DB rows / SKILL.md
     *  files or lose updates to the _skills StateFlow. */
    private val addLock = Any()

    private val db: SQLiteDatabase by lazy {
        SkillDbHelper(context).writableDatabase
    }

    private val skillsDir: File
        get() = File(context.filesDir, "minis-global/skills")

    init {
        loadAll()
        installBundledSkills()
    }

    // -- CRUD --

    /**
     * Adds a skill. The whole check-then-act sequence (existence check →
     * create → insertDb → writeSkillMd → StateFlow update) runs inside
     * [addLock] so concurrent calls with the same slug can't produce
     * duplicate DB rows / on-disk dirs or lose the _skills update.
     */
    fun add(
        name: String,
        description: String,
        body: String,
        version: String = "1.0.0",
        source: ImportSource = ImportSource.FILE,
        sourceURL: String? = null,
        // [fix/subagent-frontmatter-newskill] Raw frontmatter block of the
        // source SKILL.md (`---` … `---` included), or "". Callers that parsed
        // a full markdown document (importFromContent, installBundledSkill)
        // pass the block through so frontmatter-only keys (`subagent: true`,
        // `max_turns`, `allowed_tools`) survive the first landing — see
        // writeSkillMd, which seeds its merge with this when the skill is new.
        frontmatter: String = "",
        // [fix/da1021-A] The caller's already-resolved id. Callers that
        // resolved the landing id through [SkillRestorePolicy.resolveImportId]
        // (importFromContent's preferredId parameter, installBundledSkill's
        // assets dirName) pass it through: without this the add branch below
        // recomputed `slugify(name)` and dropped the resolved id — a renamed
        // skill (pure-CJK name → blank slug → add returned null) was silently
        // lost, or landed under a fresh slug while its backup payload and
        // session_skill_overrides still pointed at the old id (dir split).
        // Null keeps the legacy slugify behavior for callers that have no
        // preferred id. Unsafe values (blank, `/`, `\`, `..`) are rejected
        // inside resolveImportId — they fall back to the name slug.
        preferredId: String? = null,
    ): Skill? {
        // [fix/da1021-A] Never recompute the landing id here: a caller with an
        // already-resolved id (backup restore / bundled dirName) must land
        // under it. resolveImportId rejects unsafe values and falls back to
        // the name slug, so an illegal preferredId can't be adopted verbatim.
        val id = SkillRestorePolicy.resolveImportId(preferredId, slugify(name))
        if (id.isBlank()) return null

        return synchronized(addLock) {
            // Double-check inside the lock — a concurrent call may have just
            // inserted the same id between our first read and lock acquisition.
            if (_skills.value.any { it.id == id }) return@synchronized null

            val skill = Skill(
                id = id,
                name = name,
                description = description,
                version = version,
                importSource = source,
                body = body,
                sourceURL = sourceURL,
                frontmatter = frontmatter,
            )

            insertDb(skill)
            writeSkillMd(skill)
            _skills.value = _skills.value + skill
            Log.i(TAG, "Added skill: ${skill.id}")
            skill
        }
    }

    fun update(
        id: String,
        name: String? = null,
        description: String? = null,
        body: String? = null,
        version: String? = null,
        // [fix/da1023-E] The frontmatter block carried by the landing document.
        // updateFromURL / update-from-file pass it so remote `subagent` /
        // `max_turns` / `allowed_tools` changes reach the disk instead of being
        // shadowed by the old block (writeSkillMd merges memory-first now).
        // `null` — or a blank block, fail-closed — keeps the pre-fix behaviour
        // for every existing caller: the copy re-seeds from the on-disk
        // SKILL.md, so the merge base still resolves to the disk block and a
        // plain rename preserves extended keys, including edits made outside
        // the app while it was running.
        frontmatter: String? = null,
    ): Boolean {
        val current = _skills.value.find { it.id == id } ?: return false
        val updated = current.copy(
            name = name ?: current.name,
            description = description ?: current.description,
            body = body ?: current.body,
            version = version ?: current.version,
            frontmatter = frontmatter?.ifBlank { null }
                ?: readSkillMdFrontmatter(id).ifBlank { current.frontmatter },
            updatedAt = System.currentTimeMillis(),
        )

        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(updated.name, updated.description, updated.version ?: "", updated.updatedAt, id)
        )
        writeSkillMd(updated)
        _skills.value = _skills.value.map { if (it.id == id) updated else it }
        return true
    }

    fun delete(id: String) {
        db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(id))
        db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(id))
        val dir = File(skillsDir, id)
        dir.deleteRecursively()
        _skills.value = _skills.value.filter { it.id != id }
        Log.i(TAG, "Deleted skill: $id")
    }

    fun setEnabled(id: String, enabled: Boolean) {
        db.execSQL("UPDATE skills SET is_enabled=? WHERE id=?", arrayOf<Any>(if (enabled) 1 else 0, id))
        _skills.value = _skills.value.map {
            if (it.id == id) it.copy(isEnabled = enabled) else it
        }
    }

    /**
     * [fix/audit-1015-b2b] Restore usage metadata (installedAt/updatedAt/
     * useCount) carried by a backup. Export wrote all three but import never
     * read them back: a restore reset useCount to 0 and re-stamped both
     * timestamps, silently losing usage history. Only fields the backup
     * actually carries (>0) are applied — see [SkillRestorePolicy.restoreUsage].
     */
    fun restoreUsageMetadata(id: String, installedAt: Long, updatedAt: Long, useCount: Double) {
        val current = _skills.value.find { it.id == id } ?: return
        val restored = SkillRestorePolicy.restoreUsage(
            current = SkillRestorePolicy.Usage(current.installedAt, current.updatedAt, current.useCount),
            backup = SkillRestorePolicy.Usage(installedAt, updatedAt, useCount),
        )
        db.execSQL(
            "UPDATE skills SET installed_at=?, updated_at=?, use_count=? WHERE id=?",
            arrayOf<Any>(restored.installedAt, restored.updatedAt, restored.useCount, id)
        )
        _skills.value = _skills.value.map {
            if (it.id == id) {
                it.copy(installedAt = restored.installedAt, updatedAt = restored.updatedAt, useCount = restored.useCount)
            } else it
        }
    }

    // -- Session Overrides --

    fun isEnabledForSession(skillId: String, sessionId: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT is_enabled FROM session_skill_overrides WHERE session_id=? AND skill_id=?",
            arrayOf(sessionId, skillId)
        )
        val override = if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        cursor.close()
        if (override != null) return override
        return _skills.value.find { it.id == skillId }?.isEnabled ?: false
    }

    /**
     * [dv22-B4] Batched twin of [isEnabledForSession]: ONE `IN (…)` SELECT for
     * the whole id list (chunked at [SESSION_OVERRIDE_QUERY_CHUNK] to stay
     * under SQLITE_MAX_VARIABLE_NUMBER) instead of one rawQuery per skill —
     * the slash menu walks the full list on every keystroke, so the per-item
     * shape cost N synchronous queries per producer run (B-04). Precedence
     * identical: session override row → global `isEnabled` → absent (false),
     * via the pure [mergeSessionSkillEnablement] core.
     */
    fun sessionEnablementMap(sessionId: String, ids: List<String>): Map<String, Boolean> {
        if (ids.isEmpty()) return emptyMap()
        val overrideRows = mutableMapOf<String, Boolean>()
        ids.chunked(SESSION_OVERRIDE_QUERY_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = db.rawQuery(
                "SELECT skill_id, is_enabled FROM session_skill_overrides WHERE session_id=? AND skill_id IN ($placeholders)",
                arrayOf(sessionId) + chunk.toTypedArray(),
            )
            if (cursor.moveToFirst()) {
                do {
                    overrideRows[cursor.getString(0)] = cursor.getInt(1) == 1
                } while (cursor.moveToNext())
            }
            cursor.close()
        }
        val globals = _skills.value.associate { it.id to it.isEnabled }
        return mergeSessionSkillEnablement(ids, overrideRows) { id -> globals[id] }
    }

    /**
     * [dv16-A05] Sparse-clear, mirroring [MCPRepository.setSessionOverride]:
     * when [enabled] equals the skill's CURRENT global enabled, DELETE the
     * override row instead of pinning it — so a later flip of the global
     * default propagates to this session (a permanently-pinned row would
     * leave the session stuck at the old value after the user toggles the
     * switch back to the global default in [SessionSkillsSheet]). Only store
     * a row when the session value genuinely diverges from the global
     * default. Read precedence (override → global → false) in
     * [isEnabledForSession] is unchanged, and an unknown / not-yet-loaded
     * skill (`globalEnabled == null`) still writes the row.
     */
    fun setSessionOverride(sessionId: String, skillId: String, enabled: Boolean) {
        val globalEnabled = _skills.value.find { it.id == skillId }?.isEnabled
        if (shouldDeleteSessionSkillOverride(globalEnabled, enabled)) {
            db.execSQL(
                "DELETE FROM session_skill_overrides WHERE session_id=? AND skill_id=?",
                arrayOf(sessionId, skillId)
            )
        } else {
            db.execSQL(
                "INSERT OR REPLACE INTO session_skill_overrides (session_id, skill_id, is_enabled) VALUES (?, ?, ?)",
                arrayOf<Any>(sessionId, skillId, if (enabled) 1 else 0)
            )
        }
        // [dv22-B4] The table moved — publish the revision so async consumers
        // (the slash menu's producer) re-query without polling.
        _sessionOverridesRevision.value = _sessionOverridesRevision.value + 1L
    }

    fun clearSessionOverrides(sessionId: String) {
        db.execSQL("DELETE FROM session_skill_overrides WHERE session_id=?", arrayOf(sessionId))
        _sessionOverridesRevision.value = _sessionOverridesRevision.value + 1L
    }

    /**
     * [T-android-session-skill-override-init-timing] Re-point every
     * `session_skill_overrides` row that was written against the draft
     * session id ([fromDraft], e.g. `__new__<uuid>`) onto the persisted
     * session id ([toReal]) once `ensureSession()` creates the real DB row.
     * Without this hop, a pre-first-message skill toggle stays bound to the
     * draft key and becomes invisible the next time the chat is opened
     * under its real id — exactly the symptom XIN reported. Paired with
     * [MCPRepository.renameSessionOverrides].
     */
    fun renameSessionOverrides(fromDraft: String, toReal: String) {
        if (fromDraft == toReal) return
        db.execSQL(
            "UPDATE OR REPLACE session_skill_overrides SET session_id=? WHERE session_id=?",
            arrayOf<Any>(toReal, fromDraft),
        )
        _sessionOverridesRevision.value = _sessionOverridesRevision.value + 1L
    }

    // -- Prompt Fragment --

    /**
     * Build the system-prompt fragment that makes skills discoverable.
     * Discloses up to [MAX_SKILLS_IN_PROMPT] skills with 3-tier priority
     * (bundled > 7-day recent > most-used), matching iOS SkillStore.
     * Returns null when the session has no enabled skills.
     */
    fun skillPromptFragment(sessionId: String): String? {
        val enabled = _skills.value.filter { isEnabledForSession(it.id, sessionId) }
        if (enabled.isEmpty()) return null

        val total = enabled.size
        val selected: List<Skill>
        val hasMore: Boolean

        if (total <= MAX_SKILLS_IN_PROMPT) {
            selected = enabled.sortedByDescending { it.updatedAt }
            hasMore = false
        } else {
            val picked = linkedMapOf<String, Skill>() // preserves insertion order + id dedupe
            // Priority 1: bundled
            enabled.filter { it.importSource == ImportSource.BUNDLED }
                .forEach { picked.putIfAbsent(it.id, it) }
            // Priority 2: recently updated (within 7 days), up to RECENT_SLOTS more
            val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
            val recentLimit = minOf(RECENT_SLOTS, MAX_SKILLS_IN_PROMPT - picked.size).coerceAtLeast(0)
            enabled.asSequence()
                .filter { it.updatedAt > cutoff && it.id !in picked }
                .sortedByDescending { it.updatedAt }
                .take(recentLimit)
                .forEach { picked.putIfAbsent(it.id, it) }
            // Priority 3: fill remaining slots by useCount (desc)
            if (picked.size < MAX_SKILLS_IN_PROMPT) {
                val remaining = MAX_SKILLS_IN_PROMPT - picked.size
                enabled.asSequence()
                    .filter { it.id !in picked }
                    .sortedByDescending { it.useCount }
                    .take(remaining)
                    .forEach { picked.putIfAbsent(it.id, it) }
            }
            selected = picked.values.toList()
            hasMore = total > selected.size
        }

        val xml = buildString {
            append("<available_skills>\n")
            for (skill in selected) {
                var desc = skill.description
                if (desc.length > MAX_SKILL_DESC_LENGTH) {
                    desc = desc.substring(0, MAX_SKILL_DESC_LENGTH) + "…"
                }
                append("  <skill>\n")
                append("    <name>").append(escapeXml(skill.name)).append("</name>\n")
                append("    <description>").append(escapeXml(desc)).append("</description>\n")
                append("    <path>/var/minis/skills/").append(skill.id).append("/SKILL.md</path>\n")
                append("  </skill>\n")
            }
            append("</available_skills>")
        }

        return buildString {
            append("Skills:\n")
            append("Reusable instruction sets stored at /var/minis/skills/<name>/SKILL.md. Read the SKILL.md file to load full instructions before using a skill.\n\n")
            append(xml)
            if (hasMore) {
                val selectedIds = selected.mapTo(HashSet(selected.size)) { it.id }
                val omitted = enabled.filter { it.id !in selectedIds }
                val maxUndisclosed = (100 - selected.size).coerceAtLeast(0)
                val names = omitted.take(maxUndisclosed).joinToString(", ") { it.name }
                append("\n\n")
                append(omitted.size).append(" more skills not shown above: ").append(names)
                append(". List /var/minis/skills/ or grep to search all.")
            }
        }
    }

    /**
     * Record that a skill's SKILL.md was read. Matches iOS `SkillStore.recordSkillUse`:
     * bumps `useCount` by 1 and normalizes all counts to 0–100 when any exceeds 1000,
     * so long-lived installs don't drift into multi-thousand-read territory.
     */
    fun recordSkillUse(skillId: String) {
        // [audit-0917] The in-memory mirror must be updated under the same lock
        // as `add`: the DB write below is an atomic SQL increment, but the
        // StateFlow update is a read-modify-write over `_skills.value`. Two
        // concurrent calls (parallel tool executions on Dispatchers.IO) both
        // read the pre-bump snapshot → both write useCount+1 → memory drifts
        // one behind the DB, and normalizeUseCounts later persists a value
        // derived from that stale snapshot.
        // [da1029-C] The write-back itself now happens INSIDE the lock too:
        // computing `mapped` under the lock while publishing it after the
        // release left a window where a concurrent recordSkillUse read the
        // pre-publish snapshot (one increment lost) and a concurrent add()
        // appended to a list that our stale publish then overwrote.
        synchronized(addLock) {
            val current = _skills.value.find { it.id == skillId } ?: return
            val bumped = current.copy(useCount = current.useCount + 1.0)
            db.execSQL(
                "UPDATE skills SET use_count = use_count + 1 WHERE id=?",
                arrayOf<Any>(skillId)
            )
            var mapped = _skills.value.map { if (it.id == skillId) bumped else it }
            if (bumped.useCount > NORMALIZE_THRESHOLD) {
                mapped = normalizeUseCounts(mapped)
            }
            _skills.value = mapped
        }
    }

    private fun normalizeUseCounts(list: List<Skill>): List<Skill> {
        val maxCount = list.maxOfOrNull { it.useCount } ?: 0.0
        if (maxCount <= 0.0) return list
        val scaled = list.map { it.copy(useCount = it.useCount / maxCount * 100.0) }
        db.beginTransaction()
        try {
            for (s in scaled) {
                db.execSQL(
                    "UPDATE skills SET use_count=? WHERE id=?",
                    arrayOf<Any>(s.useCount, s.id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return scaled
    }

    /** Coarse-grained usage band for UI surfacing (matches iOS UsageFrequency). */
    fun usageFrequency(skillId: String): UsageFrequency {
        val skill = _skills.value.find { it.id == skillId } ?: return UsageFrequency.NEVER
        if (skill.useCount == 0.0) return UsageFrequency.NEVER
        val maxCount = _skills.value.maxOfOrNull { it.useCount } ?: 0.0
        if (maxCount <= 0.0) return UsageFrequency.NEVER
        val normalized = skill.useCount / maxCount * 100.0
        return when {
            normalized < 20.0 -> UsageFrequency.LOW
            normalized < 60.0 -> UsageFrequency.REGULAR
            else -> UsageFrequency.HIGH
        }
    }

    /**
     * Extract skill id from `/var/minis/skills/<id>/SKILL.md`. Returns null unless
     * the path is exactly a SKILL.md read of a known skill — sub-resource reads
     * under `scripts/` etc. don't count toward usage.
     */
    fun skillIdFromPath(path: String): String? {
        val prefix = "/var/minis/skills/"
        if (!path.startsWith(prefix)) return null
        if (!path.endsWith("/SKILL.md")) return null
        val rest = path.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val candidate = rest.substring(0, slash)
        return if (_skills.value.any { it.id == candidate }) candidate else null
    }

    // -- Import from SKILL.md Content --

    /**
     * Parse SKILL.md content (YAML frontmatter + markdown body) and import.
     * Returns the created Skill or null on failure.
     */
    fun importFromContent(
        content: String,
        source: ImportSource = ImportSource.FILE,
        sourceURL: String? = null,
        // [fix/audit-b22 / T4-L8] Backup restore knows the exported id. Matching
        // by name-slug alone created a duplicate whenever the skill had been
        // renamed locally after the backup was taken: the incoming name slug
        // no longer equals the local id, so restore added a second copy
        // instead of refreshing the existing one.
        preferredId: String? = null,
    ): Skill? {
        val parsed = parseSkillMd(content) ?: return null
        // [fix/audit-1015-b2c] A backup restore is a trusted, user-initiated
        // flow: the exported id is the user's own, so any non-blank,
        // directory-name-safe id is adopted verbatim. The old check required
        // the skill to already exist — on a clean install `_skills` was empty,
        // the preferred id was silently dropped, and a renamed skill came back
        // under a fresh slugify id, breaking session_skill_overrides and
        // /var/minis/skills/<id>/ references. Existence is still checked in
        // the replace/add branch below.
        val id = SkillRestorePolicy.resolveImportId(preferredId, slugify(parsed.name))
        if (id.isNotBlank() && _skills.value.any { it.id == id }) {
            // Replace existing: update in place so URL updates refresh contents.
            val current = _skills.value.first { it.id == id }
            val replaced = current.copy(
                name = parsed.name,
                description = parsed.description,
                version = parsed.version,
                importSource = source,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
                sourceURL = sourceURL ?: current.sourceURL,
                // [fix/subagent-frontmatter-newskill] Refresh the in-memory
                // frontmatter from the incoming content so frontmatter-only
                // keys (`subagent: true`) the caller edited land without
                // waiting for the next full loadAll. [fix/da1023-E] This block is
                // no longer merely a cache of the incoming document: it IS the
                // merge base now (writeSkillMd resolves memory-first), so a
                // paste / restore / file import replaces the extended keys
                // wholesale — including dropping a `subagent: true` the incoming
                // document removed. Keep the previous block when the incoming
                // document has none: a blank resolves to the disk anyway.
                frontmatter = parsed.frontmatter.ifBlank { current.frontmatter },
            )
            db.execSQL(
                "UPDATE skills SET name=?, description=?, version=?, import_source=?, source_url=?, updated_at=? WHERE id=?",
                arrayOf<Any?>(
                    replaced.name, replaced.description, replaced.version,
                    replaced.importSource.value, replaced.sourceURL,
                    replaced.updatedAt, id
                )
            )
            writeSkillMd(replaced)
            _skills.value = _skills.value.map { if (it.id == id) replaced else it }
            return replaced
        }
        return add(
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
            version = parsed.version,
            source = source,
            sourceURL = sourceURL,
            // [fix/subagent-frontmatter-newskill] Carry the incoming block so
            // a brand-new skill keeps its `subagent: true` / max_turns /
            // allowed_tools on the first landing (writeSkillMd seeds its merge
            // with this when no on-disk file exists). [fix/da1023-E] The block
            // now travels on ParsedSkill — one extraction per document instead
            // of every frontmatter-aware call site re-running
            // extractFrontmatterBlock over the raw content.
            frontmatter = parsed.frontmatter,
            // [fix/da1021-A] Pass the resolved id through to the add branch:
            // on a clean install this is the add path, and without it the id
            // resolved above (backup's exported id) was recomputed as
            // slugify(parsed.name) — renamed skills came back under a fresh
            // slug, splitting the dir from their payload and overrides.
            preferredId = id,
        )
    }

    /**
     * Returns the SKILL.md file path for a given skill (for display in UI).
     */
    fun skillMdPath(id: String): String = "/var/minis/skills/$id/SKILL.md"

    // -- Import from Zip Archive --

    /**
     * Import a skill from a .zip archive (mirrors iOS `SkillStore.importFromArchive`).
     * The archive must contain a `SKILL.md` at the root or one directory deep;
     * bundled sibling files (scripts/, references/, assets/, etc.) are extracted
     * alongside it into `skillsDir/<id>/`.
     */
    fun importFromArchive(
        input: InputStream,
        preferredId: String? = null,
        // [fix/audit-1015-b2a] ConfigBackup's archive branch carries the
        // exported importSource/sourceURL. Hard-coding FILE here rewrote every
        // restored skill's source to "file": a URL-imported skill then failed
        // updateFromURL's importSource != URL guard and permanently lost its
        // online updates. Defaults keep direct zip imports unchanged.
        source: ImportSource = ImportSource.FILE,
        sourceURL: String? = null,
    ): Skill? {
        val entries = try { readZipEntries(input) } catch (e: Exception) {
            Log.w(TAG, "Failed to read zip archive: ${e.message}")
            return null
        }
        if (entries.isEmpty()) return null

        // Accept SKILL.md at root or one level deep (matches iOS behavior).
        val skillMdEntry = entries.firstOrNull { entry ->
            val name = entry.name
            name == "SKILL.md" || (name.endsWith("/SKILL.md") && name.count { it == '/' } == 1)
        } ?: return null

        val skillContent = try { String(skillMdEntry.data, Charsets.UTF_8) } catch (_: Exception) { return null }
        if (skillContent.isBlank()) return null

        val skill = importFromContent(
            skillContent,
            source,
            preferredId = preferredId,
            sourceURL = sourceURL,
        ) ?: return null

        val prefix = if (skillMdEntry.name == "SKILL.md") "" else skillMdEntry.name.dropLast("SKILL.md".length)
        val skillDir = File(skillsDir, skill.id)
        skillDir.mkdirs()
        // [fix-audit-p1-3] Zip-slip guard via canonical-path containment,
        // matching ProviderImportZip's implementation. The old check only
        // rejected names containing ".." — it missed absolute-path entries
        // (e.g. "/data/..."), which File(parent, child) resolves outside the
        // skill dir. canonicalPath resolves both ".." and absolute prefixes,
        // so any escape lands outside rootPrefix and is dropped.
        val rootCanonical = skillDir.canonicalPath
        val rootPrefix = rootCanonical + File.separator
        // [F-10] The archive is the skill's FULL manifest, so what it no longer
        // ships has to leave the directory as well — the pre-fix loop only wrote,
        // and a script dropped upstream kept sitting in `scripts/` forever.
        val writtenRelPaths = mutableSetOf<String>()
        var writeFailures = 0

        for (entry in entries) {
            if (entry.isDirectory) continue
            var relativePath = entry.name
            if (prefix.isNotEmpty() && relativePath.startsWith(prefix)) {
                relativePath = relativePath.drop(prefix.length)
            }
            if (relativePath == "SKILL.md" || relativePath.startsWith(".") || relativePath.isEmpty()) continue

            val destFile = File(skillDir, relativePath)
            val canonical = destFile.canonicalPath
            if (canonical != rootCanonical && !canonical.startsWith(rootPrefix)) {
                Log.w(TAG, "importFromArchive: rejected zip-slip entry $relativePath")
                continue
            }
            destFile.parentFile?.mkdirs()
            try {
                destFile.writeBytes(entry.data)
                writtenRelPaths.add(relativePath.replace('\\', '/'))
            } catch (e: Exception) {
                writeFailures++
                Log.w(TAG, "Failed to write $relativePath: ${e.message}")
            }
        }
        // [F-10] Only after a clean write of a non-empty manifest: a partial
        // extraction means our idea of "what this skill ships" is incomplete, and
        // a SKILL.md-only archive says nothing about the payload (deleting on that
        // evidence would wipe a skill's scripts on a paste-shaped import).
        if (writeFailures == 0 && writtenRelPaths.isNotEmpty()) {
            deleteStaleSiblingFiles(skillDir, writtenRelPaths)
        } else if (writeFailures > 0) {
            Log.w(TAG, "importFromArchive: $writeFailures write failure(s) — skipped stale cleanup")
        }
        return skill
    }

    private data class ZipEntryData(val name: String, val isDirectory: Boolean, val data: ByteArray)

    private fun readZipEntries(input: InputStream): List<ZipEntryData> {
        val out = mutableListOf<ZipEntryData>()
        // [fix-audit-p1-2] Decompression-bomb guard: a zip advertises tiny
        // compressed sizes but can expand enormously. readZipEntries holds
        // EVERY entry's bytes in memory at once (the caller then writes them
        // out), so cap the cumulative expanded size. Exceeding it aborts the
        // whole read — the caller treats an empty result as unreadable.
        var totalExpanded = 0L
        val maxExpanded = 32L * 1024 * 1024
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                val isDir = entry.isDirectory
                val data = if (isDir) ByteArray(0) else {
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = zis.read(chunk)
                        if (n <= 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > maxExpanded) {
                            Log.w(TAG, "readZipEntries: expanded size exceeds ${maxExpanded} bytes, aborting")
                            return emptyList()
                        }
                    }
                    buf.toByteArray()
                }
                totalExpanded += data.size
                if (totalExpanded > maxExpanded) {
                    Log.w(TAG, "readZipEntries: cumulative expanded size exceeds ${maxExpanded} bytes, aborting")
                    return emptyList()
                }
                out.add(ZipEntryData(name, isDir, data))
                zis.closeEntry()
            }
        }
        return out
    }

    // -- Import from GitHub URL --

    data class GitHubInfo(
        val user: String,
        val repo: String,
        val branch: String,
        /** Directory path (without SKILL.md leaf) */
        val dirPath: String,
    )

    /**
     * Download SKILL.md from a GitHub URL and recursively download sibling files
     * from the same directory. Mirrors iOS SkillStore.importFromGitHub.
     *
     * [dv22-B6 §1042-B-07] `preferredId` routes the import to the
     * replace-in-place branch of [importFromContent] under an EXISTING local
     * id. Without it the landing id was always `slugify(name)`: importing a
     * catalogue entry whose local record sits under a different id (renamed
     * skill, custom id) silently created a duplicate record instead of
     * refreshing the installed one, and the re-import of an already-installed
     * skill reported "imported" although the repository had replaced it.
     * The replace branch refreshes name / description / version / body /
     * frontmatter / importSource / sourceURL in place, so the sibling
     * recursion below lands in the existing skill's directory.
     */
    suspend fun importFromGitHub(urlString: String, preferredId: String? = null): Skill? = withContext(Dispatchers.IO) {
        val rawURL = githubToRawURL(urlString) ?: return@withContext null
        val content = httpGetString(rawURL) ?: return@withContext null
        val skill = importFromContent(content, ImportSource.URL, sourceURL = urlString, preferredId = preferredId) ?: return@withContext null

        val ghInfo = parseGitHubURL(urlString) ?: return@withContext skill
        val skillDir = File(skillsDir, skill.id)
        // Detach the sibling-file download from the caller's coroutine scope.
        // The previous design ran the recursion inline, so any navigation
        // away from the import screen — or a ViewModel finishing its initial
        // composition — would cancel the recursion mid-flight and leave the
        // skill with only SKILL.md (T152 root cause).
        backgroundScope.launch {
            val outcome = downloadSiblingFiles(ghInfo, skillDir, skill)
            val bumped = skill.copy(updatedAt = System.currentTimeMillis())
            db.execSQL(
                "UPDATE skills SET updated_at=? WHERE id=?",
                arrayOf<Any>(bumped.updatedAt, skill.id)
            )
            _skills.value = _skills.value.map { if (it.id == skill.id) bumped else it }
            if (!outcome.isComplete) {
                Log.w(TAG, "importFromGitHub partial for ${skill.id}: ${outcome.reason ?: "${outcome.filesFailed} file(s) failed"}")
            }
        }
        skill
    }

    /** Outcome of [updateFromURL]: either an updated skill or a human-readable reason. */
    sealed class UpdateResult {
        data class Success(val skill: Skill) : UpdateResult()
        /**
         * SKILL.md fetched + persisted, but the sibling-file recursion
         * (scripts/, references/, …) ran into trouble. The skill is usable
         * but its bundled resources may be incomplete. T152 fix: this state
         * used to be silently squashed into a plain Success.
         */
        data class PartialSuccess(val skill: Skill, val reason: String) : UpdateResult()
        data class Failure(val reason: String) : UpdateResult()
    }

    /**
     * Aggregate outcome of a sibling-file download walk — surfaced through
     * [UpdateResult.PartialSuccess] when SKILL.md imported but sibling files
     * (scripts/, references/, …) could not all be retrieved. `reason` is
     * non-null only when [filesFailed] > 0 or the recursion bailed before
     * listing the directory at all.
     */
    data class SiblingDownloadOutcome(
        val filesWritten: Int,
        val filesFailed: Int,
        val reason: String?,
    ) {
        val isComplete: Boolean get() = filesFailed == 0 && reason == null
    }

    /**
     * Re-fetch the SKILL.md for an existing URL-sourced skill and update its
     * record in place — does NOT create a new skill. Also re-downloads sibling
     * files from the same GitHub directory so bundled scripts/references stay
     * in sync with upstream. Returns a typed result with an explicit reason on
     * failure so the UI can surface *why* the update failed.
     */
    suspend fun updateFromURL(skillId: String): UpdateResult = withContext(Dispatchers.IO) {
        val existing = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill not found")
        if (existing.importSource != ImportSource.URL) {
            return@withContext UpdateResult.Failure("Skill was not imported from a URL")
        }
        val urlString = existing.sourceURL
        if (urlString.isNullOrBlank()) {
            // Older imports (pre-fix) never persisted the source URL. Users need
            // to re-import the skill from Minis Skills so the URL gets saved.
            return@withContext UpdateResult.Failure(
                "No source URL on file. Re-import this skill from Minis Skills to enable updates."
            )
        }

        val rawURL = githubToRawURL(urlString)
            ?: return@withContext UpdateResult.Failure("Could not build a raw download URL from $urlString")
        val content = try { httpGetString(rawURL) } catch (e: Exception) {
            return@withContext UpdateResult.Failure("Network error: ${e.message ?: "unknown"}")
        }
        if (content == null) {
            return@withContext UpdateResult.Failure("Download failed (HTTP error) for $rawURL")
        }
        val parsed = parseSkillMd(content)
            ?: return@withContext UpdateResult.Failure("Downloaded SKILL.md is not valid (missing YAML frontmatter or 'name:' field)")

        // [fix/da1023-E] The remote document's frontmatter has to travel with
        // the managed fields. Without it update() kept the old block, so an
        // "Update from URL" refreshed name / description / body / version while
        // `subagent`, `max_turns` and `allowed_tools` stayed whatever the disk
        // already said — a catalogue that changed those keys under an installed
        // skill never caught up, and nothing was ever reported.
        val ok = update(
            id = skillId,
            name = parsed.name,
            description = parsed.description,
            body = parsed.body,
            version = parsed.version,
            frontmatter = parsed.frontmatter.ifBlank { null },
        )
        if (!ok) return@withContext UpdateResult.Failure("Failed to write updated skill")

        // Re-download sibling files (scripts/, references/, etc.). Unlike
        // importFromGitHub, the user invoked this synchronously and is
        // looking at a spinner — so we await the outcome here and surface
        // any failure as PartialSuccess so the UI can show *what* went
        // wrong (e.g. "GitHub API 403 (likely rate limited)") instead of
        // claiming success while scripts/ stays empty.
        val ghInfo = parseGitHubURL(urlString)
        var siblingOutcome: SiblingDownloadOutcome? = null
        if (ghInfo != null) {
            val skillDir = File(skillsDir, skillId)
            val current = _skills.value.first { it.id == skillId }
            siblingOutcome = downloadSiblingFiles(ghInfo, skillDir, current)
        }

        val fresh = _skills.value.find { it.id == skillId }
            ?: return@withContext UpdateResult.Failure("Skill disappeared during update")
        if (siblingOutcome != null && !siblingOutcome.isComplete) {
            val reason = siblingOutcome.reason
                ?: "${siblingOutcome.filesFailed} sibling file(s) failed to download"
            return@withContext UpdateResult.PartialSuccess(fresh, reason)
        }
        UpdateResult.Success(fresh)
    }

    // -- Export to Zip Archive --

    /**
     * [T-android-skill-export] Zip the whole `skillsDir/<id>/` directory for
     * sharing. Android port of iOS `SkillDetailView.shareSkill`; the round-trip
     * partner of [importFromArchive], which already accepts exactly this shape
     * (SKILL.md at the archive root plus any sibling files).
     *
     * Three safeguards carried over from iOS, each of which fixed a real bug
     * there — do not simplify them away:
     *
     *  1. **Unique per-export directory** (`share/skill-export-<uuid>/`).
     *     Consecutive shares of the same skill must not overwrite or delete a
     *     zip that an earlier share's consumer (Save to Files / Drive / a chat
     *     app) may still be reading.
     *  2. **Non-empty verification** before returning. iOS shipped an "exported
     *     an empty file" bug precisely because a failed copy was swallowed;
     *     here any failure throws or returns null rather than vending a zip
     *     nobody can import.
     *  3. **Stale sweep** of exports older than [EXPORT_TTL_MS], run before each
     *     new export instead of deleting on share-sheet dismissal — by then any
     *     pending consumer of an older export has long finished.
     *
     * Written under `cacheDir/share/` because that is a root already declared
     * in `file_provider_paths.xml`; a file outside a declared root makes
     * `FileProvider.getUriForFile` throw and the user just sees a share failure.
     *
     * @return the zip file, or null when the skill has no directory / no files.
     */
    fun exportSkillToZip(skillId: String): File? {
        val skill = _skills.value.find { it.id == skillId }
        val dir = File(skillsDir, skillId)
        if (!dir.isDirectory) {
            Log.w(TAG, "exportSkillToZip: no directory for $skillId")
            return null
        }
        val relPaths = listSkillFiles(skillId)
        if (relPaths.isEmpty()) {
            Log.w(TAG, "exportSkillToZip: $skillId has no files to export")
            return null
        }

        sweepStaleExports()

        val exportDir = File(File(context.cacheDir, "share"), "skill-export-${UUID.randomUUID()}")
        if (!exportDir.mkdirs() && !exportDir.isDirectory) {
            Log.w(TAG, "exportSkillToZip: could not create $exportDir")
            return null
        }
        // Filesystem-safe, single-component zip name (mirrors iOS's sanitizing).
        val safeName = (skill?.name ?: skillId)
            .replace('/', '-')
            .replace(':', '-')
            .replace('\\', '-')
            .trim()
            .ifEmpty { "skill" }
        val zipFile = File(exportDir, "$safeName.zip")

        return try {
            ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zos ->
                for (rel in relPaths) {
                    val src = File(dir, rel)
                    if (!src.isFile) continue
                    // Store entries with forward slashes — the portable
                    // separator every unzip tool (and importFromArchive) expects.
                    zos.putNextEntry(ZipEntry(rel.replace(File.separatorChar, '/')))
                    FileInputStream(src).buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            // Safeguard 2: never vend an empty/missing archive.
            if (!zipFile.isFile || zipFile.length() <= 0L) {
                Log.w(TAG, "exportSkillToZip: produced an empty zip for $skillId")
                zipFile.delete()
                return null
            }
            Log.i(TAG, "exportSkillToZip: $skillId -> ${zipFile.name} (${zipFile.length()} bytes, ${relPaths.size} files)")
            zipFile
        } catch (e: Exception) {
            Log.w(TAG, "exportSkillToZip failed for $skillId: ${e.message}")
            runCatching { zipFile.delete() }
            null
        }
    }

    /**
     * Safeguard 3: delete `skill-export-*` directories older than
     * [EXPORT_TTL_MS]. Called before each new export rather than when the share
     * sheet closes, so a consumer still reading a previous export is never
     * pulled out from under.
     */
    private fun sweepStaleExports() {
        val root = File(context.cacheDir, "share")
        val now = System.currentTimeMillis()
        val entries = root.listFiles() ?: return
        for (entry in entries) {
            if (!entry.isDirectory || !entry.name.startsWith("skill-export-")) continue
            if (now - entry.lastModified() < EXPORT_TTL_MS) continue
            runCatching { entry.deleteRecursively() }
                .onFailure { Log.w(TAG, "sweepStaleExports: could not delete ${entry.name}") }
        }
    }

    /**
     * List every file inside `skillsDir/<id>/` (recursive). Returns relative
     * paths from the skill root, with SKILL.md first and the rest sorted by
     * name. Used by the Skill detail UI to enumerate bundled scripts.
     */
    fun listSkillFiles(skillId: String): List<String> {
        val files = listFilesUnder(File(skillsDir, skillId))
        return files.sortedWith(compareBy(
            { if (it.equals("SKILL.md", ignoreCase = true)) 0 else 1 },
            { it },
        ))
    }

    /**
     * Recursively list [root]'s files as `/`-separated paths relative to it.
     * Hidden entries are skipped (dotted files are host bookkeeping — `.DS_Store`,
     * editor swap files — never skill payload). Empty when [root] is not a
     * directory, which is how every caller distinguishes "no skill dir" from "an
     * empty one".
     */
    private fun listFilesUnder(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        val rootPath = root.absolutePath
        // `absolutePath` of a directory has no trailing separator, except for "/".
        val prefixLen = if (rootPath.endsWith(File.separator)) rootPath.length else rootPath.length + 1
        return root.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.absolutePath.substring(prefixLen) }
            .toList()
    }

    /**
     * [F-10] Delete the files [planStaleSiblingDeletions] flags under [root], then
     * prune the directories they leave empty (an empty `scripts/` still reads as
     * "this skill ships scripts" in the file browser). Returns how many files were
     * removed; a delete failure is logged, never thrown — a stale file must not turn
     * an otherwise successful sync into a failure.
     *
     * Callers must only reach here with a manifest they can prove complete; the
     * decision itself lives in the pure planner.
     */
    private fun deleteStaleSiblingFiles(root: File, keepRelPaths: Collection<String>): Int {
        val stale = planStaleSiblingDeletions(listFilesUnder(root), keepRelPaths)
        if (stale.isEmpty()) return 0
        val rootCanonical = runCatching { root.canonicalPath }.getOrNull() ?: return 0
        var deleted = 0
        for (rel in stale) {
            val target = File(root, rel)
            // The same containment rule the sibling writers apply: a manifest path
            // never escapes the skill directory (a `..` entry must not delete
            // somebody else's file).
            val canonical = runCatching { target.canonicalPath }.getOrNull() ?: continue
            if (!canonical.startsWith(rootCanonical + File.separator)) {
                Log.w(TAG, "[F-10] refusing to delete out-of-tree path $rel")
                continue
            }
            if (runCatching { target.delete() }.getOrDefault(false)) {
                deleted++
                pruneEmptyParents(target.parentFile, root)
            } else {
                Log.w(TAG, "[F-10] could not delete stale $rel")
            }
        }
        if (deleted > 0) Log.i(TAG, "[F-10] removed $deleted stale file(s) under ${root.name}")
        return deleted
    }

    /** Delete [dir] and its ancestors while they are empty; never touches [root]. */
    private fun pruneEmptyParents(dir: File?, root: File) {
        val rootCanonical = runCatching { root.canonicalPath }.getOrNull() ?: return
        var current = dir ?: return
        while (runCatching { current.canonicalPath }.getOrNull() != rootCanonical) {
            val children = current.list()
            if (children == null || children.isNotEmpty()) return
            if (!current.delete()) return
            current = current.parentFile ?: return
        }
    }

    /**
     * Re-read SKILL.md from disk and push any frontmatter changes back into the
     * DB + in-memory state. Call after the agent (or user) edits SKILL.md on
     * disk so the store reflects the new name/description/version/body.
     * Returns the refreshed skill or null if the file is gone or malformed.
     */
    fun rescanFromDisk(skillId: String): Skill? {
        val current = _skills.value.find { it.id == skillId } ?: return null
        val file = File(File(skillsDir, skillId), "SKILL.md")
        if (!file.exists()) return null
        val raw = file.readText()
        val parsed = parseSkillMd(raw) ?: return null
        // [B14b] Refresh the frontmatter snapshot alongside name/description/...
        // — SKILL.md edits (agent writeSkillFile, user) ride through here, and
        // dropping the block from the copy() emptied the in-memory frontmatter,
        // so SubagentSkill lost frontmatter-only keys (subagent, max_turns,
        // allowed_tools) after the first rescan.
        val refreshed = current.copy(
            name = parsed.name,
            description = parsed.description,
            version = parsed.version,
            body = parsed.body,
            frontmatter = SubagentSkill.extractFrontmatterBlock(raw),
            updatedAt = System.currentTimeMillis(),
        )
        db.execSQL(
            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
            arrayOf<Any>(refreshed.name, refreshed.description, refreshed.version, refreshed.updatedAt, skillId)
        )
        _skills.value = _skills.value.map { if (it.id == skillId) refreshed else it }
        return refreshed
    }

    /**
     * Rename a skill in place — updates the `name:` line in frontmatter and DB
     * but keeps the slugged id (and therefore the path) stable so references
     * from existing sessions don't break.
     */
    fun renameSkill(skillId: String, newName: String): Boolean {
        val current = _skills.value.find { it.id == skillId } ?: return false
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return false
        val file = File(File(skillsDir, skillId), "SKILL.md")
        if (file.exists()) {
            val original = file.readText()
            // [B14b] Only rewrite the `name:` line inside the frontmatter block —
            // the previous full-document replaceFirst fired on the whole file, so
            // when a SKILL.md has no frontmatter a `name:` example line in the
            // markdown body was silently rewritten. Pure string work lives in the
            // companion so JVM tests exercise the real code (same split as
            // [mergeFrontmatter]). null → file left untouched.
            renameFrontmatterName(original, trimmed)?.let { updated ->
                if (updated != original) file.writeText(updated)
            }
        }
        val refreshed = current.copy(name = trimmed, updatedAt = System.currentTimeMillis())
        db.execSQL(
            "UPDATE skills SET name=?, updated_at=? WHERE id=?",
            arrayOf<Any>(refreshed.name, refreshed.updatedAt, skillId)
        )
        _skills.value = _skills.value.map { if (it.id == skillId) refreshed else it }
        return true
    }

    /** Read an arbitrary file inside a skill's directory (e.g. scripts/foo.py). */
    fun readSkillFile(skillId: String, relativePath: String): String? {
        val file = resolveSkillFile(skillId, relativePath) ?: return null
        if (!file.isFile) return null
        return try { file.readText() } catch (_: Exception) { null }
    }

    /** Write an arbitrary file inside a skill's directory. Creates parents. */
    fun writeSkillFile(skillId: String, relativePath: String, content: String): Boolean {
        val file = resolveSkillFile(skillId, relativePath) ?: return false
        file.parentFile?.mkdirs()
        return try {
            file.writeText(content)
            // SKILL.md edits must flow back to DB metadata.
            if (relativePath.equals("SKILL.md", ignoreCase = true)) rescanFromDisk(skillId)
            true
        } catch (_: Exception) { false }
    }

    /**
     * Resolve a relative path against `skillsDir/<skillId>/` and reject any
     * escape via `..`, absolute paths (`/abs/...`), or symlink tricks — the
     * same canonical-path containment guard used by [importFromArchive]'s
     * zip-slip check. Returns null when the result would land outside the
     * skill directory (or when canonicalization fails).
     */
    private fun resolveSkillFile(skillId: String, relativePath: String): File? {
        val root = File(skillsDir, skillId)
        val rootCanonical = try { root.canonicalPath } catch (_: Exception) { return null }
        val rootPrefix = rootCanonical + File.separator
        val candidate = File(root, relativePath)
        val canonical = try { candidate.canonicalPath } catch (_: Exception) { return null }
        // Allow the skill root itself only for the record path; file callers
        // never legitimately target the directory, but keep the check precise.
        if (canonical != rootCanonical && !canonical.startsWith(rootPrefix)) return null
        return candidate
    }

    private suspend fun downloadSiblingFiles(ghInfo: GitHubInfo, destDir: File, skill: Skill): SiblingDownloadOutcome {
        Log.i(TAG, "[siblings] start skill=${skill.id} dir=${ghInfo.dirPath} repo=${ghInfo.user}/${ghInfo.repo}@${ghInfo.branch}")
        val agg = AggregateOutcome()
        downloadGitHubDirectory(
            user = ghInfo.user,
            repo = ghInfo.repo,
            branch = ghInfo.branch,
            remotePath = ghInfo.dirPath,
            localDir = destDir,
            relativeTo = "",
            skill = skill,
            depth = 0,
            outcome = agg,
        )
        val finalReason = agg.firstReason
        // [F-10] Upstream-removed files leave the directory too — but only on a walk
        // we can prove COMPLETE (no failed download, no bail-out before listing) and
        // only with a non-empty manifest. A truncated listing is indistinguishable
        // from "upstream has nothing", and acting on that guess would delete the
        // skill's whole payload.
        if (agg.filesFailed == 0 && finalReason == null && agg.seenRelPaths.isNotEmpty()) {
            deleteStaleSiblingFiles(destDir, agg.seenRelPaths)
        }
        Log.i(TAG, "[siblings] done skill=${skill.id} written=${agg.filesWritten} failed=${agg.filesFailed} reason=${finalReason ?: "(none)"}")
        return SiblingDownloadOutcome(agg.filesWritten, agg.filesFailed, finalReason)
    }

    /**
     * Mutable accumulator threaded through the recursion. Tracks a
     * machine-friendly count plus the FIRST human-readable reason so the UI
     * can show one specific cause (e.g. "GitHub API 403 (rate limited)")
     * rather than a generic "some files failed".
     */
    private class AggregateOutcome {
        var filesWritten = 0
        var filesFailed = 0
        var firstReason: String? = null
        /**
         * [F-10] Every skill-relative path the walk actually landed (written or
         * byte-identical), i.e. the manifest upstream currently ships. Consumed by
         * [downloadSiblingFiles] to drop files upstream no longer has.
         */
        val seenRelPaths = mutableSetOf<String>()
        fun recordFailure(reason: String) {
            filesFailed += 1
            if (firstReason == null) firstReason = reason
        }
        fun recordReason(reason: String) {
            if (firstReason == null) firstReason = reason
        }
    }

    private suspend fun downloadGitHubDirectory(
        user: String,
        repo: String,
        branch: String,
        remotePath: String,
        localDir: File,
        relativeTo: String,
        skill: Skill,
        depth: Int,
        outcome: AggregateOutcome,
    ) {
        if (depth > 5) {
            val msg = "Max recursion depth (5) at $remotePath — stopping"
            Log.w(TAG, "[siblings] $msg")
            outcome.recordReason(msg)
            return
        }
        val encodedPath = URLEncoder.encode(remotePath, "UTF-8").replace("+", "%20").replace("%2F", "/")
        val apiURL = "https://api.github.com/repos/$user/$repo/contents/$encodedPath?ref=$branch"

        // 1-shot retry on transient failure (HTTP 403 rate-limited, network
        // exception). The retry waits 1.5s — enough to clear short-lived
        // OkHttp connection-pool issues, not enough to fix a real rate-limit
        // window (which is per-hour) but cheap enough that it's worth trying.
        val body = fetchContentsWithRetry(apiURL, outcome) ?: return

        val items = try {
            JSONArray(body)
        } catch (e: Exception) {
            // Some endpoints return a JSON object on error (e.g. {"message":...})
            // even with HTTP 200. Capture the first-line snippet for triage.
            val snippet = body.lineSequence().firstOrNull()?.take(160) ?: "(empty)"
            val msg = "GitHub contents API returned non-array JSON for $remotePath: $snippet"
            Log.w(TAG, "[siblings] $msg (${e.javaClass.simpleName}: ${e.message})")
            outcome.recordReason(msg)
            return
        }

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            val type = item.optString("type")
            val relPath = if (relativeTo.isEmpty()) name else "$relativeTo/$name"

            if (type == "dir") {
                val subRemote = if (remotePath.isEmpty()) name else "$remotePath/$name"
                downloadGitHubDirectory(
                    user = user, repo = repo, branch = branch,
                    remotePath = subRemote, localDir = localDir, relativeTo = relPath,
                    skill = skill, depth = depth + 1, outcome = outcome,
                )
                continue
            }

            if (type != "file") continue
            // Skip SKILL.md at root (already imported)
            if (relativeTo.isEmpty() && name.equals("SKILL.md", ignoreCase = true)) continue
            val downloadURL = item.optString("download_url")
            if (downloadURL.isEmpty()) {
                Log.w(TAG, "[siblings] missing download_url for $relPath — skipping")
                outcome.recordFailure("Missing download_url for $relPath")
                continue
            }

            val fileData = fetchBytesWithRetry(downloadURL)
            if (fileData == null) {
                val msg = "Failed to download $relPath from $downloadURL"
                Log.w(TAG, "[siblings] $msg")
                outcome.recordFailure(msg)
                continue
            }
            val destFile = File(localDir, relPath)
            destFile.parentFile?.mkdirs()
            val existing = if (destFile.exists()) destFile.readBytes() else null
            if (existing == null || !existing.contentEquals(fileData)) {
                destFile.writeBytes(fileData)
                Log.i(TAG, "[siblings] wrote $relPath (${fileData.size}B)")
            } else {
                Log.i(TAG, "[siblings] kept $relPath (unchanged, ${fileData.size}B)")
            }
            outcome.seenRelPaths.add(relPath.replace('\\', '/'))
            outcome.filesWritten += 1
        }
    }

    /**
     * Fetch a GitHub Contents-API URL, retrying once on transient failure
     * (HTTP 403/429/5xx, IOException). Returns the response body string on
     * success, or null after both attempts fail — in which case the failure
     * reason is recorded into [outcome] so the UI can surface it.
     */
    private suspend fun fetchContentsWithRetry(apiURL: String, outcome: AggregateOutcome): String? {
        var lastReason: String? = null
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url(apiURL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (body != null) return body
                        lastReason = "GitHub contents API returned empty body"
                    } else {
                        val isTransient = resp.code == 403 || resp.code == 429 || resp.code in 500..599
                        // 403 from api.github.com is almost always rate-limit;
                        // call it out specifically so users know waiting helps.
                        val hint = if (resp.code == 403) " (likely anonymous rate limit — wait an hour or sign in)" else ""
                        lastReason = "GitHub contents API HTTP ${resp.code}$hint for $apiURL"
                        if (!isTransient) {
                            Log.w(TAG, "[siblings] non-retryable ${lastReason}")
                            outcome.recordReason(lastReason!!)
                            return null
                        }
                    }
                }
            } catch (e: Exception) {
                lastReason = "GitHub contents API ${e.javaClass.simpleName}: ${e.message ?: "unknown"} for $apiURL"
            }
            if (attempt == 0) {
                Log.w(TAG, "[siblings] retrying after transient failure: $lastReason")
                try { delay(1500) } catch (_: Exception) {}
            }
        }
        Log.w(TAG, "[siblings] both attempts failed: $lastReason")
        outcome.recordReason(lastReason ?: "GitHub contents API unavailable")
        return null
    }

    /** Same retry policy as [fetchContentsWithRetry], for raw file blobs. */
    private suspend fun fetchBytesWithRetry(url: String): ByteArray? {
        repeat(2) { attempt ->
            try {
                val req = Request.Builder().url(url).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return resp.body?.bytes()
                    val isTransient = resp.code == 403 || resp.code == 429 || resp.code in 500..599
                    if (!isTransient) {
                        Log.w(TAG, "[siblings] HTTP ${resp.code} non-retryable for $url")
                        return null
                    }
                    Log.w(TAG, "[siblings] HTTP ${resp.code} on $url (attempt ${attempt + 1})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[siblings] ${e.javaClass.simpleName}: ${e.message} for $url (attempt ${attempt + 1})")
            }
            if (attempt == 0) {
                try { delay(1500) } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * Parse a GitHub URL into components. Returns null for non-GitHub URLs.
     * Supports:
     *   - github.com/user/repo/blob|tree/branch/path/SKILL.md
     *   - raw.githubusercontent.com/user/repo/branch/path/SKILL.md
     */
    private fun parseGitHubURL(urlString: String): GitHubInfo? {
        val normalized = normalizeURL(urlString)
        val uri = try { Uri.parse(normalized) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val parts = uri.pathSegments.filter { it.isNotEmpty() }

        if (host == "raw.githubusercontent.com") {
            // /user/repo/branch/path/to/SKILL.md
            if (parts.size < 3) return null
            val user = parts[0]; val repo = parts[1]; val branch = parts[2]
            val dirParts = parts.drop(3).toMutableList()
            if (dirParts.isNotEmpty() && dirParts.last().equals("SKILL.md", ignoreCase = true)) {
                dirParts.removeAt(dirParts.size - 1)
            }
            return GitHubInfo(user, repo, branch, dirParts.joinToString("/"))
        }

        if (host != "github.com") return null
        // /user/repo/blob|tree/branch/path/...
        if (parts.size < 4) return null
        val user = parts[0]; val repo = parts[1]; val branch = parts[3]
        val dirParts = parts.drop(4).toMutableList()
        if (dirParts.isNotEmpty() && dirParts.last().equals("SKILL.md", ignoreCase = true)) {
            dirParts.removeAt(dirParts.size - 1)
        }
        return GitHubInfo(user, repo, branch, dirParts.joinToString("/"))
    }

    /**
     * Convert a GitHub URL to a raw.githubusercontent.com SKILL.md URL.
     * Returns null if the URL cannot be parsed.
     */
    private fun githubToRawURL(urlString: String): String? {
        val normalized = normalizeURL(urlString)
        val uri = try { Uri.parse(normalized) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val parts = uri.pathSegments.filter { it.isNotEmpty() }

        if (host == "raw.githubusercontent.com") {
            return if (parts.lastOrNull()?.equals("SKILL.md", ignoreCase = true) == true) {
                normalized
            } else {
                val base = normalized.trimEnd('/')
                "$base/SKILL.md"
            }
        }

        if (host != "github.com") return null
        if (parts.size < 4) return null
        val user = parts[0]; val repo = parts[1]; val branch = parts[3]
        val pathParts = parts.drop(4).toMutableList()
        var path = pathParts.joinToString("/")
        if (!path.endsWith("SKILL.md", ignoreCase = true)) {
            path = if (path.isEmpty()) "SKILL.md" else "$path/SKILL.md"
        }
        return "https://raw.githubusercontent.com/$user/$repo/$branch/$path"
    }

    private fun normalizeURL(urlString: String): String {
        val trimmed = urlString.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    private fun httpGetString(url: String): String? = try {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (e: Exception) {
        Log.w(TAG, "httpGetString failed $url: ${e.message}")
        null
    }

    private fun httpGetBytes(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).get().build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (e: Exception) {
        Log.w(TAG, "httpGetBytes failed $url: ${e.message}")
        null
    }


    // -- Bundled Skills --

    /**
     * Install all skills bundled in the APK's `assets/skills/` directory.
     *
     * Each subdirectory under `assets/skills/<name>/` is treated as one
     * skill: `SKILL.md` (required, carries name/description/version in the
     * YAML frontmatter) is installed into the registry, and every sibling
     * file (`scripts/`, `*.py`, `*.pkl`, `requirements.json`, …) is
     * extracted to the skill's sandbox directory. This replaces the old
     * single hardcoded `skill-creator` bundle so platform skills
     * (semantic-memory, github-ops, cloudflare-fullright-ops) ship
     * in the app out of the box.
     *
     * Two gates, deliberately different scopes:
     *  - **SKILL.md / registry row** — version-gated (same-or-newer local
     *    version is left untouched; preserves user edits and blocks silent
     *    downgrades of a newer user-imported copy).
     *  - **sibling payload** (`scripts/`, `*.py`, `*.pkl`, …) — content-hash
     *    gated via [SkillAssetSync]'s manifest, so a script-only fix ships
     *    without anyone bumping the version string. See that file for the
     *    2026-10-14 incident this fixes.
     *  - **row source label** — a row we can prove is our own copy (its
     *    declaration still equals the shipped one) is adopted to
     *    [ImportSource.BUNDLED] even when it is labelled something else, so the
     *    payload gate above is actually reachable. See [adoptBundledRow].
     */
    private fun installBundledSkills() {
        try {
            val skillDirs = context.assets.list("skills") ?: return
            for (dirName in skillDirs) {
                installBundledSkill(dirName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "installBundledSkills failed: ${e.message}")
        }
    }

    private fun installBundledSkill(dirName: String) {
        val content: String
        try {
            content = context.assets.open("skills/$dirName/SKILL.md").bufferedReader().readText()
        } catch (e: Exception) {
            Log.w(TAG, "Bundled skill $dirName has no SKILL.md, skipping")
            return
        }

        val parsed = parseSkillMd(content) ?: return
        val bundledVersion = parsed.version.ifBlank { "1.0.0" }
        val rowBefore = _skills.value.find { it.id == dirName }

        // [fix/bundled-row-adoption] A shipped skill's row can carry a foreign
        // source label — the maintainer device has all four labelled `file`
        // (「文件导入」), version equal to the shipped one, on-disk SKILL.md
        // byte-identical to the APK's. Without this the guard inside the early
        // return below skipped exactly those installs, so the content-hash gate
        // never ran and script-only fixes stayed in the APK. Adopt once, then
        // the row takes the normal BUNDLED path on every later launch.
        val existing = if (rowBefore != null && isAdoptableBundledRow(rowBefore, parsed, bundledVersion)) {
            adoptBundledRow(rowBefore, dirName)
        } else {
            rowBefore
        }

        // Skip if local version is same or newer. Semantic comparison, not
        // lexical — a lexical `">="` would treat "1.10.0" as older than
        // "1.9.0" and re-downgrade a newer install on every launch.
        // [fix/bundled-skill-asset-hash-gate] The early return only covers the
        // SKILL.md/row update: the sibling payload is gated by a content hash
        // inside syncBundledSiblings, so a script-only fix reaches existing
        // installs without a version bump. Only BUNDLED-sourced rows are
        // synced — a user-imported copy at the same version is theirs to keep.
        if (existing != null && compareVersions(existing.version, bundledVersion) >= 0) {
            if (existing.importSource == ImportSource.BUNDLED) syncBundledSiblings(dirName)
            return
        }

        if (existing != null) {
            val updated = existing.copy(
                description = parsed.description,
                version = bundledVersion,
                body = parsed.body,
                updatedAt = System.currentTimeMillis(),
                // [fix/audit-1015-b2d] Refresh the frontmatter from the
                // incoming content, mirroring the replace path in
                // importFromContent (see [fix/subagent-frontmatter-newskill]):
                // writeSkillMd merges against this field, and a stale block
                // here made a version upgrade shadow frontmatter-only keys
                // (`subagent: true`, max_turns, allowed_tools) until the next
                // full reload. [fix/da1023-E] That sentence is only true after
                // this batch: with the pre-fix `existing ?: skill.frontmatter`
                // base the field was ignored whenever a SKILL.md already
                // existed — i.e. on EVERY upgrade — so this refresh changed
                // memory only and the disk kept the previous block. Keep the
                // previous block when the incoming document has none.
                // importSource is deliberately NOT touched:
                // if the row was adopted to BUNDLED it already is; a
                // user-imported row that reached this branch (their version is
                // older) keeps its own source label — that is the version
                // gate's contract, not ours to overwrite.
                frontmatter = parsed.frontmatter.ifBlank { existing.frontmatter },
            )
            db.execSQL(
                "UPDATE skills SET description=?, version=?, updated_at=? WHERE id=?",
                arrayOf<Any>(updated.description, bundledVersion, updated.updatedAt, dirName)
            )
            writeSkillMd(updated)
            syncBundledSiblings(dirName)
            _skills.value = _skills.value.map { if (it.id == dirName) updated else it }
            Log.i(TAG, "Upgraded bundled skill: $dirName → v$bundledVersion")
        } else {
            add(
                name = parsed.name,
                description = parsed.description,
                body = parsed.body,
                version = bundledVersion,
                source = ImportSource.BUNDLED,
                // [fix/subagent-frontmatter-newskill] Carry the bundled block
                // so a first install keeps `subagent: true` / max_turns /
                // allowed_tools on disk and in memory.
                frontmatter = parsed.frontmatter,
                // [fix/da1021-A] A bundled skill must land under its assets
                // dirName — the sibling payload was extracted into
                // <dirName>/ by syncBundledSiblings. Without this, a SKILL.md
                // whose name slugified differently from the dirName (e.g. a
                // CJK name) landed under a different slug than the payload.
                preferredId = dirName,
            )
            syncBundledSiblings(dirName)
            Log.i(TAG, "Installed bundled skill: $dirName (v$bundledVersion)")
        }
    }

    /**
     * [fix/bundled-row-adoption] Wiring half of the adoption decision — see
     * [SkillAssetSync.isAdoptableBundledRow] for the incident (all four shipped
     * skills labelled `file` on the maintainer device) and for why the
     * declaration, not the file bytes, is what gets compared.
     */
    private fun isAdoptableBundledRow(existing: Skill, parsed: ParsedSkill, bundledVersion: String): Boolean =
        SkillAssetSync.isAdoptableBundledRow(
            storedSource = existing.importSource.value,
            bundledSource = ImportSource.BUNDLED.value,
            stored = SkillAssetSync.Declaration(existing.name, existing.description, existing.version),
            bundled = SkillAssetSync.Declaration(parsed.name, parsed.description, bundledVersion),
        )

    /**
     * Relabel a row we have proven to be our own copy of a shipped skill.
     *
     * Only the source column moves: the declaration already equals the shipped
     * one (that is what the caller proved) and [syncBundledSiblings] owns the
     * payload. `updated_at` is deliberately left alone — adoption is not a
     * content change, and stamping it would erase the row's provenance in the
     * skills UI.
     */
    private fun adoptBundledRow(existing: Skill, dirName: String): Skill {
        val adopted = existing.copy(importSource = ImportSource.BUNDLED)
        db.execSQL(
            "UPDATE skills SET import_source=? WHERE id=?",
            arrayOf<Any>(adopted.importSource.value, dirName)
        )
        _skills.value = _skills.value.map { if (it.id == dirName) adopted else it }
        Log.i(TAG, "Adopted mislabeled bundled skill: $dirName (source was ${existing.importSource.value})")
        return adopted
    }

    /** Sidecar manifest recording the payload hash of the last extraction. */
    private val bundledAssetManifest: File
        get() = File(skillsDir, SkillAssetSync.MANIFEST_NAME)

    /**
     * Sync the sibling payload under `assets/skills/<dirName>/` (scripts/,
     * data/, *.py, *.pkl, requirements.json, …) into the skill's sandbox
     * directory — but only when the payload actually changed.
     *
     * [fix/bundled-skill-asset-hash-gate] Content-hash gated (see
     * [SkillAssetSync]): the manifest holds the hash of what we last wrote, so
     * a script-only fix in a new APK is picked up even when nobody bumped the
     * SKILL.md version. Conversely, user edits to a bundled script survive
     * every launch (and every version bump) whose payload matches the one we
     * extracted. SKILL.md is skipped at the top level — the version-gated path
     * (`writeSkillMd`) owns that file.
     */
    private fun syncBundledSiblings(dirName: String) {
        val entries = collectAssetEntries("skills/$dirName", relPrefix = "", skipTopLevelMd = true)
        val current = SkillAssetSync.payloadHash(entries)
        val stored = readBundledAssetHashes()[dirName]
        if (!SkillAssetSync.needsExtraction(stored, current)) return

        val destDir = File(skillsDir, dirName)
        destDir.mkdirs()
        var written = 0
        for (e in entries) {
            val target = File(destDir, e.path)
            target.parentFile?.mkdirs()
            try {
                target.writeBytes(e.bytes)
                written++
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to extract ${e.path}: ${ex.message}")
            }
        }
        if (written != entries.size) {
            // A partial extraction (disk full, kill mid-write) must not be
            // frozen as "current": leaving the manifest untouched makes the
            // next launch retry instead of shipping a half-written script.
            Log.w(TAG, "Bundled sibling sync for $dirName incomplete ($written/${entries.size} files); retrying next launch")
            return
        }
        recordBundledAssetHash(dirName, current)
        Log.i(TAG, "Synced bundled siblings for $dirName ($written files, hash=${current.take(12)})")
    }

    /** Read the sibling payload of a bundled skill into memory as (relPath, bytes). */
    private fun collectAssetEntries(
        assetPath: String,
        relPrefix: String,
        skipTopLevelMd: Boolean,
    ): List<SkillAssetSync.Entry> {
        val out = mutableListOf<SkillAssetSync.Entry>()
        val entries = try {
            context.assets.list(assetPath) ?: return out
        } catch (e: Exception) {
            Log.w(TAG, "list($assetPath): ${e.message}")
            return out
        }
        for (entry in entries) {
            if (relPrefix.isEmpty() && skipTopLevelMd && entry == "SKILL.md") continue
            val childAsset = "$assetPath/$entry"
            val rel = if (relPrefix.isEmpty()) entry else "$relPrefix/$entry"
            // A path under assets that lists child entries is a directory.
            val isDir = try {
                val sub = context.assets.list(childAsset)
                !(sub == null || sub.isEmpty())
            } catch (_: Exception) { false }
            if (isDir) {
                out += collectAssetEntries(childAsset, relPrefix = rel, skipTopLevelMd = false)
            } else {
                try {
                    context.assets.open(childAsset).use { input ->
                        out += SkillAssetSync.Entry(rel, input.readBytes())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read $childAsset: ${e.message}")
                }
            }
        }
        return out
    }

    private fun readBundledAssetHashes(): Map<String, String> = try {
        val f = bundledAssetManifest
        if (f.exists()) SkillAssetSync.parseManifest(f.readText()) else emptyMap()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read ${SkillAssetSync.MANIFEST_NAME}: ${e.message}")
        emptyMap()
    }

    private fun recordBundledAssetHash(dirName: String, hash: String) {
        try {
            val merged = readBundledAssetHashes() + (dirName to hash)
            bundledAssetManifest.parentFile?.mkdirs()
            bundledAssetManifest.writeText(SkillAssetSync.renderManifest(merged))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write ${SkillAssetSync.MANIFEST_NAME}: ${e.message}")
        }
    }

    /**
     * Load a skill's `requirements.json` (dependency + deployment-tiers
     * manifest). Returns null when the file is missing or unparseable.
     *
     * Consumed by the Android-side integration-status builder to surface
     * each platform skill's capabilities + the tier each env var unlocks;
     * also the seed for the future sandbox dependency pre-flight.
     */
    fun loadSkillRequirements(skillId: String): SkillRequirements? {
        return try {
            val reqFile = File(File(skillsDir, skillId), "requirements.json")
            if (!reqFile.exists()) return null
            val json = JSONObject(reqFile.readText())
            SkillRequirements(
                apk = json.optJSONArray("apk")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                pip = json.optJSONArray("pip")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                env = json.optJSONObject("env")?.let { obj ->
                    obj.keys().asSequence().associate { key -> key to obj.getString(key) }
                } ?: emptyMap(),
                tiers = json.optJSONObject("tiers")?.let { obj ->
                    obj.keys().asSequence().associate { key -> key to obj.getString(key) }
                } ?: emptyMap(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "loadSkillRequirements($skillId): ${e.message}")
            null
        }
    }

    // -- Reload --

    /**
     * Re-scan `skillsDir` and the SQLite registry, re-publishing the
     * resulting list via the [skills] StateFlow. Mirrors iOS
     * `SkillStore.reload()` (Agent/Session/SkillStore.swift). Use this after
     * an out-of-band install (agent shell `git clone`, agent file_write of a
     * SKILL.md, on Skills screen entry) so newly dropped directories are
     * promoted from disk into the registry without requiring an app restart.
     *
     * Compat: does not delete or reset enabled state — DB rows are the
     * source of truth for `is_enabled`, and disk-only directories are
     * auto-discovered as ImportSource.SESSION (matching the existing init
     * path), not overwriting any preserved DB toggle.
     */
    fun reloadFromDisk() {
        loadAll()
    }

    // -- Internal --

    private fun loadAll() {
        // Load from DB
        val dbSkills = mutableListOf<Skill>()
        val cursor = db.rawQuery("SELECT * FROM skills ORDER BY installed_at DESC", null)
        while (cursor.moveToNext()) {
            val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
            // One on-disk parse per row: `body` comes from it, and the same
            // parse feeds the metadata re-sync further down.
            val diskSkill = parseSkillMdOnDisk(id)
            val body = diskSkill?.body ?: ""
            // [fix/subagent-frontmatter] Frontmatter-only fields (subagent:
            // true, max_turns, allowed_tools) never reach `body` — parseSkillMd
            // strips the block — so read it separately for consumers that
            // re-inspect frontmatter (SubagentSkill.parseSubagentConfig).
            val frontmatter = readSkillMdFrontmatter(id)
            val sourceUrlIdx = cursor.getColumnIndex("source_url")
            val useCountIdx = cursor.getColumnIndex("use_count")
            var description = cursor.getString(cursor.getColumnIndexOrThrow("description"))
            var name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
            var version = cursor.getString(cursor.getColumnIndexOrThrow("version"))
            // Self-heal rows persisted by an earlier parser that treated YAML
            // block scalars (`|` and `>`) as literal one-character description
            // values. Re-parse the on-disk SKILL.md whenever the stored
            // description is just `|` or `>` and update the row in place so
            // the skills list shows the real frontmatter description.
            if (description == ">" || description == "|") {
                val skillMd = File(skillsDir, "$id/SKILL.md")
                if (skillMd.exists()) {
                    val reparsed = parseSkillMd(runCatching { skillMd.readText() }.getOrNull() ?: "")
                    if (reparsed != null) {
                        description = reparsed.description
                        if (name.isBlank()) name = reparsed.name
                        if (version.isBlank()) version = reparsed.version
                        db.execSQL(
                            "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
                            arrayOf<Any>(name, description, version, System.currentTimeMillis(), id),
                        )
                        Log.i(TAG, "Self-healed skill description for $id")
                    }
                }
            }
            // [T-skillmeta-resync] Reconcile the row with the on-disk
            // frontmatter. Rows are written at import time and
            // reloadFromDisk() only re-reads the DB, so a SKILL.md edited
            // outside the in-app editor (agent file_write/file_edit, or a
            // shell sed in the sandbox — both already route through
            // reloadFromDisk via maybeReloadSkillsForPath /
            // SkillsManagementScreen) kept its original description forever.
            // That description is what the model reads in
            // <available_skills>, so a stale row means it triggers on
            // outdated text. Disk wins for the fields it carries; see
            // SkillMetadataSync for the guards.
            val synced = SkillMetadataSync.reconcile(
                stored = SkillMetadataSync.Metadata(name, description, version),
                disk = diskSkill?.let {
                    SkillMetadataSync.Metadata(it.name, it.description, it.version)
                },
            )
            if (synced != null) {
                name = synced.name
                description = synced.description
                version = synced.version
                db.execSQL(
                    "UPDATE skills SET name=?, description=?, version=?, updated_at=? WHERE id=?",
                    arrayOf<Any>(name, description, version, System.currentTimeMillis(), id),
                )
                Log.i(TAG, "Re-synced skill metadata from disk: $id (v$version)")
            }
            dbSkills.add(Skill(
                id = id,
                name = name,
                description = description,
                version = version,
                importSource = ImportSource.from(cursor.getString(cursor.getColumnIndexOrThrow("import_source"))),
                isEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("is_enabled")) == 1,
                installedAt = cursor.getLong(cursor.getColumnIndexOrThrow("installed_at")),
                updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
                body = body,
                frontmatter = frontmatter,
                sourceURL = if (sourceUrlIdx >= 0 && !cursor.isNull(sourceUrlIdx)) cursor.getString(sourceUrlIdx) else null,
                useCount = if (useCountIdx >= 0) cursor.getDouble(useCountIdx) else 0.0,
            ))
        }
        cursor.close()

        // [fix-backup-feedback] Prune stale DB rows whose on-disk directory is
        // gone (e.g. a bundled skill renamed on disk — github-sync-helper →
        // github-ops — while the old row lingered in SQLite). A phantom row
        // has no scripts to back up, so every export degrades it to
        // "SKILL.md only" and the restored skill is broken; and it shows up in
        // the skills list forever because nothing else removes it. The column
        // list can't be trusted after a rename (WE only discover disk→DB), so
        // sweep DB rows whose directory no longer exists. Only prune when the
        // directory is fully absent — a dir without SKILL.md still deserves
        // the auto-discover path below.
        val staleIds = dbSkills.map { it.id }.filter { id ->
            val dir = File(skillsDir, id)
            !dir.isDirectory
        }
        for (id in staleIds) {
            Log.w(TAG, "Pruning stale skill $id: on-disk directory missing (renamed or deleted?)")
            db.execSQL("DELETE FROM skills WHERE id=?", arrayOf(id))
            db.execSQL("DELETE FROM session_skill_overrides WHERE skill_id=?", arrayOf(id))
        }
        if (staleIds.isNotEmpty()) {
            // Remove pruned ids from the in-memory list; delete() would
            // re-trigger recursion into loadAll, so drop them directly.
            dbSkills.removeAll { it.id in staleIds }
        }

        // Auto-discover skills on disk without DB entries
        val onDisk = skillsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (dir in onDisk) {
            val skillMd = File(dir, "SKILL.md")
            if (skillMd.exists() && dbSkills.none { it.id == dir.name }) {
                val raw = runCatching { skillMd.readText() }.getOrNull() ?: continue
                val parsed = parseSkillMd(raw)
                if (parsed != null) {
                    val skill = Skill(
                        id = dir.name,
                        name = parsed.name,
                        description = parsed.description,
                        importSource = ImportSource.SESSION,
                        body = parsed.body,
                        // [fix/subagent-frontmatter-autodiscover] The Skill
                        // created here used to default frontmatter to "" —
                        // parseSkillMd strips the block from body — so a skill
                        // first discovered mid-session (e.g. written by an
                        // agent file_write with `subagent: true`) parsed as
                        // isSubagent=false and EVERY spawn_agent on it failed
                        // with "not a sub-agent skill" until the next full
                        // loadAll (10-08 23:44 sweep: 7/7 spawn failures on a
                        // skill whose file had the flag). Extract the block
                        // from the same raw text the parse consumed — mirrors
                        // the DB-row path above (readSkillMdFrontmatter).
                        frontmatter = SubagentSkill.extractFrontmatterBlock(raw),
                    )
                    insertDb(skill)
                    dbSkills.add(skill)
                    Log.i(TAG, "Auto-discovered skill: ${dir.name}")
                }
            }
        }

        _skills.value = dbSkills
    }

    private fun insertDb(skill: Skill) {
        db.execSQL(
            """INSERT OR REPLACE INTO skills (id, name, description, version, import_source, source_url, is_enabled, installed_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf<Any?>(
                skill.id, skill.name, skill.description, skill.version,
                skill.importSource.value, skill.sourceURL,
                if (skill.isEnabled) 1 else 0,
                skill.installedAt, skill.updatedAt,
            )
        )
    }

    private fun writeSkillMd(skill: Skill) {
        val dir = File(skillsDir, skill.id)
        dir.mkdirs()
        val file = File(dir, "SKILL.md")
        // [F-223] Merge into the existing frontmatter instead of regenerating a
        // 3-key block. `parseSkillMd` only extracts name/description/version, so
        // the old rebuild silently dropped every frontmatter-only key —
        // `subagent: true`, `max_turns`, `allowed_tools` — on ANY landing
        // (add / update / import-overwrite / bundled upgrade). Since
        // SubagentSkill.parseSubagentConfig reads those keys off disk, one
        // rename via the skills UI made the skill vanish from the spawn_agent
        // list. Preserving the original block keeps unknown keys intact.
        val existing = runCatching { file.readText() }.getOrNull()
        // [fix/subagent-frontmatter-newskill] A brand-new skill (no on-disk
        // file yet) used to emit the canonical 3-key block, silently dropping
        // every frontmatter-only key (`subagent: true`, `max_turns`,
        // `allowed_tools`) the caller carried — an import of a `subagent:
        // true` SKILL.md via the skills editor landed WITHOUT the flag, so
        // parseSubagentConfig read isSubagent=false until the source was
        // re-imported. Seed the merge with the caller's frontmatter block:
        // mergeFrontmatter swaps the three managed keys in place and carries
        // every other key verbatim.
        //
        // [fix/da1023-E] But seeding only worked while no file existed: the
        // pre-fix base was `existing ?: skill.frontmatter`, so the disk
        // shadowed the caller's block on EVERY path that already had a file —
        // updateFromURL / update-from-file refreshed name/description/body
        // while silently keeping the old extended keys, and an editor save
        // couldn't delete a `subagent: true` the user had just removed.
        // The base now resolves memory-first through [resolveMergeBase]
        // (one exit for the rule; callers without a fresh block re-seed from
        // disk, so plain renames keep disk-wins semantics there).
        val mergeBase = resolveMergeBase(skill.frontmatter, existing)
        val content = mergeFrontmatter(
            existing = mergeBase,
            name = skill.name,
            description = skill.description,
            version = skill.version,
            body = skill.body,
        )
        file.writeText(content)
    }

    /**
     * Parse the skill's on-disk SKILL.md, or null when the file is missing or
     * isn't a valid SKILL.md (no/incomplete frontmatter). Callers treat null as
     * "disk has nothing to say" — never as "the skill is empty".
     */
    private fun parseSkillMdOnDisk(id: String): ParsedSkill? {
        val file = File(skillsDir, "$id/SKILL.md")
        if (!file.exists()) return null
        return parseSkillMd(runCatching { file.readText() }.getOrNull() ?: "")
    }

    /**
     * [fix/subagent-frontmatter] Raw frontmatter block of the on-disk SKILL.md
     * (`---` … `---` inclusive), or "" when the file has no frontmatter.
     * The extraction itself lives in [SubagentSkill] so the part that used to
     * break silently — frontmatter never reaching the parser — is covered by
     * JVM unit tests; this class needs Android's Context/SQLite and can't be
     * unit-tested directly.
     */
    private fun readSkillMdFrontmatter(id: String): String {
        val file = File(skillsDir, "$id/SKILL.md")
        if (!file.exists()) return ""
        val raw = runCatching { file.readText() }.getOrNull() ?: return ""
        return SubagentSkill.extractFrontmatterBlock(raw)
    }

    data class ParsedSkill(
        val name: String,
        val description: String,
        val version: String = "1.0.0",
        val body: String,
        // [fix/da1023-E] Raw frontmatter block of the parsed document (`---`
        // delimiters included), "" when it has none. [body] is
        // frontmatter-STRIPPED, so every frontmatter-only key rides on this
        // field — without it, the callers that only get a ParsedSkill
        // (updateFromURL, update-from-file) had no way to hand the NEW block
        // to writeSkillMd, and the landing kept the old extended keys.
        val frontmatter: String = "",
    )

    /**
     * Parse a SKILL.md file into [ParsedSkill]. Mirrors iOS SkillStore.parse(skillMD:).
     *
     * Recognized YAML frontmatter forms (delimited by `---` … `---`):
     *
     *   description: short text
     *   description: |
     *     line one
     *     line two           ← preserved with newlines
     *   description: >
     *     line one
     *     line two           ← folded into a single space-joined line
     *
     * The block-scalar variants (`|` and `>`) matter in practice — many
     * authored SKILL.md files (e.g. hyperframes, bilibili-hub) use
     * `description: >` to either fold a multi-line description or as a
     * placeholder with an empty body. The previous parser stored the
     * literal string `">"` for those skills, which is what surfaced in the
     * skills list as a confusing single-character description.
     *
     * Returns null only if the document doesn't start with `---` or if
     * the closing `---` is missing — caller treats null as "not a SKILL.md".
     * If frontmatter parses but `name` is blank we also return null so
     * import paths can fall back (e.g. derive name from the GitHub URL).
     */
    /**
     * Public re-exposure for the "Update from File" UI. Same parser, same
     * `null = invalid SKILL.md` contract. Lives on the instance for symmetry
     * with the rest of the API surface, even though the parse itself is
     * stateless.
     */
    fun parseSkillMdPublic(content: String): ParsedSkill? = parseSkillMd(content)

    private fun parseSkillMd(content: String): ParsedSkill? {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return null

        // Find the closing `---` on a line by itself (matches iOS — and avoids
        // false matches against horizontal rules later in the document).
        val lines = trimmed.lines()
        var frontmatterEndLine = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                frontmatterEndLine = i
                break
            }
        }
        if (frontmatterEndLine < 0) return null

        var name = ""
        var description = ""
        var version = "1.0.0"

        var i = 1
        while (i < frontmatterEndLine) {
            val line = lines[i]
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) { i++; continue }
            val key = line.substring(0, colonIdx).trim().lowercase()
            val rawValue = line.substring(colonIdx + 1).trim()

            // YAML block scalar: `|` keeps newlines, `>` folds them into spaces.
            // Body lines continue while they are indented (or empty); a
            // dedented line ends the block.
            //
            // T151: also accept the YAML chomping indicators `>-`, `>+`, `|-`,
            // `|+` (and any explicit indentation digit suffix). Real-world
            // skill frontmatter — qbt-hub, for one — opens its description
            // with `description: >-` so trailing newlines are stripped, but
            // the previous strict `==` match treated that as a plain string
            // literal, leaving the renderer to display the raw `>-` marker.
            val isBlockScalar = rawValue.startsWith("|") || rawValue.startsWith(">")
            val resolved: String
            if (isBlockScalar && i + 1 < frontmatterEndLine) {
                val fold = rawValue.startsWith(">")
                val blockLines = mutableListOf<String>()
                var j = i + 1
                while (j < frontmatterEndLine) {
                    val next = lines[j]
                    if (next.isEmpty() || next[0].isWhitespace()) {
                        blockLines.add(next.trim())
                    } else break
                    j++
                }
                resolved = if (fold) {
                    blockLines.joinToString(" ").trim()
                } else {
                    blockLines.joinToString("\n").trim('\n')
                }
                i = j
            } else {
                resolved = rawValue
                i++
            }

            when (key) {
                "name" -> name = resolved
                "description" -> description = resolved
                "version" -> version = resolved
            }
        }

        if (name.isBlank()) return null

        val bodyStartLine = frontmatterEndLine + 1
        val body = if (bodyStartLine < lines.size) {
            lines.subList(bodyStartLine, lines.size).joinToString("\n").trim('\n')
        } else ""

        // [fix/da1023-E] Hand the raw block to the caller too (see
        // ParsedSkill.frontmatter) so update paths can seed writeSkillMd's
        // memory-first merge base with the NEW frontmatter.
        return ParsedSkill(
            name,
            description,
            version,
            body,
            frontmatter = SubagentSkill.extractFrontmatterBlock(trimmed),
        )
    }

    private fun slugify(name: String): String =
        name.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * Semantic (numeric) comparison of dotted version strings, e.g. `1.10.0`
     * vs `1.9.0`. Returns negative / zero / positive like [Comparable.compareTo].
     * Non-numeric / missing segments parse as 0 so `"1"` == `"1.0"` and
     * malformed versions don't crash the comparison; also strips a leading
     * `v` that sometimes prefixes bundled versions.
     */
    private fun compareVersions(a: String, b: String): Int {
        fun parts(raw: String): List<Int> =
            raw.trim().removePrefix("v").removePrefix("V")
                .split('.')
                .map { it.trim().toIntOrNull() ?: 0 }
        val ap = parts(a)
        val bp = parts(b)
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val av = ap.getOrElse(i) { 0 }
            val bv = bp.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    // -- Database Helper --

    private class SkillDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE skills (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    version TEXT NOT NULL DEFAULT '1.0.0',
                    import_source TEXT NOT NULL DEFAULT 'file',
                    source_url TEXT,
                    is_enabled INTEGER NOT NULL DEFAULT 1,
                    installed_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    use_count REAL NOT NULL DEFAULT 0
                )
            """)
            db.execSQL("""
                CREATE TABLE session_skill_overrides (
                    session_id TEXT NOT NULL,
                    skill_id TEXT NOT NULL,
                    is_enabled INTEGER NOT NULL,
                    PRIMARY KEY (session_id, skill_id)
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN source_url TEXT")
                } catch (_: Exception) { /* column may already exist */ }
            }
            if (oldVersion < 3) {
                try {
                    db.execSQL("ALTER TABLE skills ADD COLUMN use_count REAL NOT NULL DEFAULT 0")
                } catch (_: Exception) { /* column may already exist */ }
            }
        }
    }
}
