package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/da1032-e] — deep-audit-1032 ISSUE-06 (P1).
 *
 * Two defects on the Skills / MCP settings screens, both visible only as
 * *shape*:
 *
 * 1. **Main-thread disk I/O on entry.** Both screens re-scan their repository
 *    the moment they appear (`LaunchedEffect(Unit) { repository.reloadFromDisk() }`).
 *    `SkillRepository.loadAll()` is a raw `SQLiteDatabase.rawQuery` over every
 *    skill row plus a per-row `parseSkillMdOnDisk` (file read) and a directory
 *    walk; `MCPRepository.load()` reads `servers.json` and may write it back.
 *    Running that inline in a `LaunchedEffect` put the whole scan on the Main
 *    dispatcher ([audit-1032 ISSUE-06]: Settings-screen jank / ANR window).
 *    NOTE: the audit report framed this as "Room `.allowMainThreadQueries()`".
 *    That is not what this codebase does — `SkillRepository.db` is
 *    `SkillDbHelper(context).writableDatabase`, a plain `SQLiteOpenHelper`; the
 *    only `allowMainThreadQueries()` hits in the tree are in `androidTest`
 *    in-memory `AppDatabase` builders. The fix reason is "offline I/O does not
 *    belong on Main", not "a Room assertion would have thrown".
 *
 * 2. **Editor remember-key gaps.** `editName` was `remember { … }` with no key
 *    while the screen re-resolves `skill` from the repository StateFlow — so an
 *    out-of-band frontmatter rename (agent `file_write` / shell `sed`, re-synced
 *    into the row) left the rename dialog holding the pre-edit name, and saving
 *    wrote the old name back. `editContent` was keyed on
 *    `skillId, relativePath` while the `initialContent` it seeds from is
 *    additionally keyed on `skill.updatedAt` — so an out-of-band edit while the
 *    editor is open refreshed `initialContent` and left `editContent` on the
 *    pre-edit snapshot forever.
 *
 * The screens are Compose + repository (not JVM-instantiable), so both
 * contracts are pinned by source-shape assertions — the approach this repo
 * already uses (`ThinkingRulesRememberKeyTest`, `SkillFileViewerSaveShapeTest`,
 * `SharedCacheExportPruneTest`). Expected values are hardcoded literals, never
 * derived from the code under test, so a mutation cannot masquerade as PASS.
 *
 * The threading criterion binds the DECISION POINT (which dispatcher the call
 * runs on), not one spelling of it: a call site that is lexically inside
 * `withContext(Dispatchers.IO)`, **or** a repository method that wraps
 * internally, both satisfy it ([da1031-D]: an equivalent refactor must not
 * turn the criterion red).
 */
class SkillsMcpReloadOffMainTest {

    private val skillsScreen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/SkillsManagementScreen.kt")
    }
    private val mcpScreen: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/ui/settings/MCPIntegrationsScreen.kt")
    }
    private val skillRepo: String by lazy {
        readRepoFile(
            "app/src/main/java/com/rikkaminis/app/data/repository/SkillRepository.kt",
        )
    }
    private val mcpRepo: String by lazy {
        readRepoFile("app/src/main/java/com/rikkaminis/app/data/repository/MCPRepository.kt")
    }

    // ── ① the entry re-scan runs off Main ───────────────────────────────

    @Test
    fun skillsReload_runsOffMain() {
        assertCallIsOffMain(
            codeOnly(skillsScreen),
            "skillRepository.reloadFromDisk()",
            codeOnly(skillRepo),
            "fun reloadFromDisk()",
            "SkillsManagementScreen",
        )
    }

    @Test
    fun mcpReload_runsOffMain() {
        assertCallIsOffMain(
            codeOnly(mcpScreen),
            "mcpRepository.reloadFromDisk()",
            codeOnly(mcpRepo),
            "fun reloadFromDisk()",
            "MCPIntegrationsScreen",
        )
    }

    // ── ② editor remember keys ──────────────────────────────────────────

    @Test
    fun `editName remember keys the skill it drafts from`() {
        assertEquals(
            "the name dialog drafts from skill.name at the moment it opens; without both " +
                "keys it shows the previously edited skill's draft (or a stale name after " +
                "an out-of-band rename)",
            setOf("skill.id", "skill.name"),
            rememberKeys(codeOnly(skillsScreen), "var editName by remember"),
        )
    }

    @Test
    fun `editContent remember keys updatedAt`() {
        assertEquals(
            "initialContent re-derives on skill.updatedAt; editContent must move with it or " +
                "an out-of-band edit leaves the editor on the pre-edit snapshot",
            setOf("skillId", "relativePath", "skill.updatedAt", "loadedDiskBase"),
            rememberKeys(codeOnly(skillsScreen), "var editContent by remember"),
        )
    }

    @Test
    fun `editContent keys the same sources as the content it seeds from`() {
        // [dv17-4] deep-audit-1038 B-04. `initialContent` derives from BOTH
        // skill.updatedAt and the async sibling-file read (loadedDiskBase); the
        // editor draft was keyed on the first only, so an out-of-band edit that
        // landed while the editor was open left the draft frozen on the
        // pre-read snapshot and Save wrote it back over the external write.
        // Pinning the equality (not just the presence of one key) keeps the two
        // sets from drifting apart again — the failure mode is always "one
        // remember grew a key and the sibling didn't".
        val code = codeOnly(skillsScreen)
        assertEquals(
            "the editor draft must seed from the same key set as the content it copies " +
                "(a key added to one remember and not the other is exactly B-04)",
            rememberKeys(code, "val initialContent = remember"),
            rememberKeys(code, "var editContent by remember"),
        )
    }

    @Test
    fun initialContentAnchorLiteral_survives() {
        assertTrue(
            "skill_frontmatter_roundtrip_check.py pins this line prefix — the fix adds " +
                "keys, it does not rename or reshape the line",
            codeOnly(skillsScreen).contains("val initialContent = remember("),
        )
    }

    // ── ③ anchors the fix must NOT move (regression controls) ───────────

    @Test
    fun savePathLiterals_untouched() {
        val code = codeOnly(skillsScreen)
        for (literal in listOf("val contentToSave = editContent", "editContent = initialContent")) {
            assertTrue(
                "skill_file_save_result_check.py pins `$literal`; threading/keys work must not " +
                    "rewrite the save path",
                code.contains(literal),
            )
        }
    }

    @Test
    fun pasteImport_stillWrapsItsRepositoryWrite() {
        val code = codeOnly(skillsScreen)
        assertTrue(
            "the paste-import branch was already off Main ([audit-0917]) — the reload fix " +
                "must not have moved its wrap away",
            isInsideIoWrap(code, "skillRepository.importFromContent(pasteContent)"),
        )
    }

    @Test
    fun keylessUiFlags_stayKeyless() {
        // Precision control: the criterion keys on the two editor drafts, it is not
        // "every remember in the file must carry keys".
        val code = codeOnly(skillsScreen)
        for (flag in listOf("showDeleteDialog", "showEditNameDialog")) {
            assertTrue(
                "`var $flag by remember {` is a screen-local UI flag and must stay keyless",
                code.contains("var $flag by remember {"),
            )
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Passes when the call site is lexically inside `withContext(Dispatchers.IO)`,
     * or the repository entry point wraps internally (one delegation hop).
     */
    private fun assertCallIsOffMain(
        screen: String,
        call: String,
        repo: String,
        repoDecl: String,
        label: String,
    ) {
        assertTrue("call site not found in $label: $call", screen.contains(call))
        val bare = bareCallLines(screen, call)
        if (bare.isEmpty()) return
        assertTrue(
            "$label: `$call` runs on Main at line(s) $bare and the repository entry point " +
                "($repoDecl) does not wrap the work either — wrap the call in " +
                "withContext(Dispatchers.IO), or move the IO into the repository method " +
                "(the criterion is the dispatcher the call runs on, not the spelling)",
            repoDecl in repo && repoMethodWrapsIo(repo, repoDecl),
        )
    }

    /** 1-based line numbers of `call` occurrences NOT inside an IO wrap. */
    private fun bareCallLines(code: String, call: String): List<Int> {
        val out = mutableListOf<Int>()
        var from = 0
        while (true) {
            val idx = code.indexOf(call, from)
            if (idx < 0) break
            if (!isInsideIoWrap(code, idx)) {
                out.add(code.substring(0, idx).count { it == '\n' } + 1)
            }
            from = idx + 1
        }
        return out
    }

    private fun isInsideIoWrap(code: String, call: String): Boolean {
        val idx = code.indexOf(call)
        return idx >= 0 && isInsideIoWrap(code, idx)
    }

    /**
     * True when `offset` sits inside a `withContext(Dispatchers.IO) { … }` block:
     * masked, brace-depth stack, same three steps the scanner gate uses.
     */
    private fun isInsideIoWrap(raw: String, offset: Int): Boolean {
        val code = maskStringsAndComments(raw)
        val op = "withContext(Dispatchers.IO)"
        val stack = ArrayDeque<Boolean>()
        var i = 0
        while (i < offset && i < code.length) {
            when (code[i]) {
                '{' -> {
                    val head = code.substring(0, i).trimEnd().removeSuffix("{").trimEnd()
                    stack.addLast(head.endsWith(op))
                }
                '}' -> if (stack.isNotEmpty()) stack.removeLast()
            }
            i++
        }
        return stack.any { it }
    }

    private fun repoMethodWrapsIo(code: String, decl: String, depth: Int = 0): Boolean {
        if (depth > 2) return false
        val start = code.indexOf(decl)
        if (start < 0) return false
        val paramOpen = code.indexOf('(', start)
        if (paramOpen < 0) return false
        var i = paramOpen
        var nesting = 0
        while (i < code.length) {
            when (code[i]) {
                '(' -> nesting++
                ')' -> {
                    nesting--
                    if (nesting == 0) break
                }
            }
            i++
        }
        var j = i + 1
        while (j < code.length && code[j].isWhitespace()) j++
        if (code.getOrNull(j) == '=') { // expression body: follow one delegation hop
            val eol = code.indexOf('\n', j).let { if (it < 0) code.length else it }
            val callee = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
                .find(code.substring(j + 1, eol))?.groupValues?.get(1) ?: return false
            return repoMethodWrapsIo(code, "fun $callee(", depth + 1)
        }
        if (code.getOrNull(j) != '{') return false
        var braceDepth = 0
        var k = j
        while (k < code.length) {
            when (code[k]) {
                '{' -> braceDepth++
                '}' -> {
                    braceDepth--
                    if (braceDepth == 0) {
                        val body = code.substring(j, k)
                        if (body.contains("withContext(Dispatchers.IO)")) return true
                        val callees = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
                            .findAll(body).map { it.groupValues[1] }
                        return callees.any { it != declCallee(decl) && repoMethodWrapsIo(code, "fun $it(", depth + 1) }
                    }
                }
            }
            k++
        }
        return false
    }

    private fun declCallee(decl: String): String =
        Regex("fun\\s+([A-Za-z_][A-Za-z0-9_]*)").find(decl)?.groupValues?.get(1) ?: ""

    /** Drops `//` comment lines so prose cannot satisfy a shape probe. */
    private fun codeOnly(src: String): String = src
        .split("\n")
        .map { if (it.trimStart().startsWith("//")) " ".repeat(it.length) else it }
        .joinToString("\n")

    /** Blanks double-quoted string bodies (length-preserving) so braces inside
     *  `${...}` templates cannot skew the brace walk. */
    private fun maskStringsAndComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '"') {
                if (src.startsWith("\"\"\"", i)) {
                    val j = src.indexOf("\"\"\"", i + 3).let { if (it < 0) src.length else it + 3 }
                    out.append("x".repeat(j - i))
                    i = j
                } else {
                    var j = i + 1
                    while (j < src.length) {
                        if (src[j] == '\\') { j += 2; continue }
                        if (src[j] == '"' || src[j] == '\n') { j++; break }
                        j++
                    }
                    out.append("x".repeat(j - i))
                    i = j
                }
            } else if (src.startsWith("//", i)) {
                val j = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                out.append(" ".repeat(j - i))
                i = j
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** `(a, b, c)` key list of the `remember` in the statement starting at `marker`. */
    private fun rememberKeys(code: String, marker: String): Set<String> {
        val start = code.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val open = code.indexOf('(', start + marker.length)
        assertTrue("no key list after: $marker", open >= 0)
        val close = code.indexOf(")", open)
        assertTrue("key list not closed after: $marker", close >= 0)
        return code.substring(open + 1, close)
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun readRepoFile(relative: String): String {
        // Platform type: under the Android stubs `System.getProperty` is
        // inferred nullable, and JDK 21's java.io.File(String) is not — pin it
        // so this helper compiles warning-free (CI logs a `Java type mismatch`
        // for every shape test that passes it straight through).
        val cwd = System.getProperty("user.dir") ?: "."
        var dir: java.io.File? = java.io.File(cwd)
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=$cwd")
    }
}
