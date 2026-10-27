package com.rikkaminis.app.sandbox

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production wiring for the FIX-C path work ([deep-audit-1016]).
 *
 * The behavioural arms live in [GuestPathParseMatrixTest] (pure functions) and
 * the scan gate `scripts/scan/readonly_mount_write_guard.py` (mechanical);
 * this class pins the *delegation* — that the Android-coupled entry points and
 * the two offload write sites still go through those pieces, so a later
 * "quick fix" cannot re-inline the raw-string matching or drop a guard without
 * turning a test red.
 *
 * Source-shape assertions read the repo files (Gradle sets `user.dir` to the
 * module dir; a sandbox JVM run may start anywhere under the repo, so the
 * lookup walks up).
 */
class SandboxPathGuardWiringTest {

    // ── 2a: the resolvers normalize before matching ─────────────────────

    @Test
    fun `session resolver delegates to the pure core and never matches the raw path`() {
        val body = declarationSlice(readRepoFile(PROOT_KERNEL), "fun resolveSessionHostPath(")
        assertTrue(
            "resolveSessionHostPath no longer delegates to resolveSessionGuestHostPath:\n$body",
            body.contains("resolveSessionGuestHostPath("),
        )
        assertFalse(
            "resolveSessionHostPath must not prefix-match the raw path again:\n$body",
            body.contains("linuxPath.startsWith"),
        )
    }

    @Test
    fun `global resolver delegates to the pure core and never matches the raw path`() {
        val body = declarationSlice(readRepoFile(PROOT_KERNEL), "fun resolveHostPath(")
        assertTrue(
            "resolveHostPath no longer delegates to resolveGuestHostPath:\n$body",
            body.contains("resolveGuestHostPath("),
        )
        assertFalse(
            "resolveHostPath must not prefix-match the raw path again:\n$body",
            body.contains("linuxPath.startsWith"),
        )
        // Pre-boot state must still reach the pure core as "no rootfs/session
        // tree" instead of being read from the object inside it.
        assertTrue("delegation lost the rootfs precondition:\n$body", body.contains("::rootfsManager.isInitialized"))
        assertTrue("delegation lost sessionsRoot:\n$body", body.contains("bootContext?.filesDir"))
    }

    @Test
    fun `both pure cores normalize on entry and fail closed`() {
        val src = readRepoFile(PROOT_KERNEL)
        for (marker in listOf(
            "fun resolveSessionGuestHostPath(",
            "fun resolveGuestHostPath(",
        )) {
            val body = declarationSlice(src, marker)
            assertTrue(
                "$marker must normalize the guest path before any prefix match:\n$body",
                body.contains("normalizeLinuxPath(linuxPath) ?: return null"),
            )
            assertFalse(
                "$marker must not fall back to the raw string when normalization fails:\n$body",
                body.contains("?: linuxPath"),
            )
        }
    }

    /**
     * The other half of "one normalization policy": the read-only guard already
     * normalized (audit-1014 B5b). R10 of the matrix pins that guard and
     * resolver now name the same file; this pins that the guard keeps its own
     * normalization instead of regressing to a raw prefix test.
     */
    @Test
    fun `read-only mount guard still normalizes before matching`() {
        val body = declarationSlice(readRepoFile(PROOT_KERNEL), "fun isLinuxPathUnderReadOnlyMount(")
        assertTrue("read-only guard lost its normalization:\n$body", body.contains("normalizeLinuxPath(linuxPath)"))
    }

    // ── 2a/2b: the model-use write sites ───────────────────────────────

    @Test
    fun `session-scoped classifier normalizes before the scope match and refuses dotdot`() {
        val body = declarationSlice(readRepoFile(MODEL_USE), "fun classifySessionPath(")
        assertTrue(
            "classifier must refuse a literal `..` segment before normalizing:\n$body",
            body.contains("hasDotDotSegment(linuxPath)"),
        )
        assertTrue(
            "classifier must normalize before the scope regex:\n$body",
            body.contains("normalizeLinuxPath(linuxPath)"),
        )
        assertTrue(
            "the scope regex must run on the normalized path:\n$body",
            body.contains("SESSION_SCOPED_PATH.find(normalized)"),
        )
        assertFalse(
            "the scope regex must not run on the raw path again:\n$body",
            body.contains("SESSION_SCOPED_PATH.find(linuxPath)"),
        )
    }

    @Test
    fun `both model-use output writers call the read-only mount guard`() {
        val src = readRepoFile(MODEL_USE)
        for (marker in listOf(
            "fun shapeRawPassthrough(",
            "fun writeRunOutput(",
        )) {
            val body = declarationSlice(src, marker)
            assertTrue(
                "$marker writes --output without the read-only mount guard:\n$body",
                body.contains("isLinuxPathUnderReadOnlyMount(outputPath)"),
            )
        }
    }

    /**
     * `isLinuxPathUnderReadOnlyMount` may only be *called* outside its own
     * file: the guard is the contract the write tools reference
     * (FileWriteTool / FileEditTool), and a second copy of the rule is exactly
     * the drift [deep-audit-1019 F-214] deleted once already.
     */
    @Test
    fun `no second read-only-mount rule was introduced`() {
        for (rel in listOf(MODEL_USE, FASTIO)) {
            val src = readRepoFile(rel)
            assertFalse(
                "$rel defines its own read-only-mount decision instead of calling PRootKernel's",
                src.contains("fun isLinuxPathUnderReadOnlyMount("),
            )
        }
    }

    // ── da1033 I01: the read-only *shared folders* refusal ─────────────
    //
    // The browser lists everything under `/var/minis`, which includes the two
    // folders the settings screen labels Read-Only (Skills / Memory). Refusing
    // read-only *mounts* is not enough there: `writable = false` used to reach
    // only the AccessBadge, so the trash icon really deleted SOUL.md /
    // GLOBAL.md / SKILL.md. Four properties keep that from coming back; the
    // third is what makes the first two worth anything (a literal second path
    // list would drift exactly like the registry it copies).

    @Test
    fun `browser delete refuses both read-only domains before it deletes`() {
        val body = declarationSlice(readRepoFile(FILE_BROWSER), "fun deleteItem(")
        assertTrue(
            "deleteItem() lost the read-only mount refusal:\n$body",
            body.contains("isLinuxPathUnderReadOnlyMount("),
        )
        assertTrue(
            "deleteItem() lost the read-only shared-folder refusal (the I01 defect):\n$body",
            body.contains("isLinuxPathUnderReadOnlySharedDir("),
        )
        val deleteAt = body.indexOf("deleteRecursively()")
        assertTrue("the delete call is gone from deleteItem():\n$body", deleteAt > 0)
        val mountAt = body.indexOf("isLinuxPathUnderReadOnlyMount(")
        val sharedAt = body.indexOf("isLinuxPathUnderReadOnlySharedDir(")
        assertTrue("the mount refusal must run before the delete:\n$body", mountAt in 0 until deleteAt)
        assertTrue("the shared-folder refusal must run before the delete:\n$body", sharedAt in 0 until deleteAt)
    }

    @Test
    fun `the shared-folder predicate derives its roots from the registry`() {
        val roots = declarationSlice(readRepoFile(REGISTRY_SCREEN), "val readOnlyLinuxPaths")
        assertTrue(
            "readOnlyLinuxPaths must derive from the registry entries:\n$roots",
            roots.contains("entries.filterNot { it.writable }"),
        )
        assertFalse(
            "readOnlyLinuxPaths must not carry a second path list:\n$roots",
            roots.contains("/var/minis/"),
        )
    }

    @Test
    fun `the shared-folder matcher normalizes both sides and matches whole segments`() {
        val body = declarationSlice(readRepoFile(REGISTRY_SCREEN), "fun isUnderReadOnlyLinuxRoot(")
        assertTrue("the matcher must normalize the target:\n$body", body.contains("normalizeLinuxPath(linuxPath)"))
        assertTrue("the matcher must normalize the root it compares against:\n$body", body.contains("normalizeLinuxPath(root)"))
        assertTrue(
            "the matcher must compare whole path segments, not raw prefixes:\n$body",
            body.contains("startsWith(\"\$base/\")"),
        )
        assertFalse("the matcher must not prefix-match the raw path:\n$body", body.contains("linuxPath.startsWith("))
    }

    @Test
    fun `the shared-folder rule is not a second read-only-mount rule`() {
        val src = readRepoFile(REGISTRY_SCREEN)
        assertFalse(
            "SharedFoldersScreen.kt must call PRootKernel's mount guard, not re-declare it",
            src.contains("fun isLinuxPathUnderReadOnlyMount("),
        )
    }

    // ── dv15-B1 B-06: every fileBrowser route declares its linux root ──
    //
    // `readonly_mount_write_guard.py` (frozen) only pins that AppNavigation
    // keeps `Routes.fileBrowser(` and the mounts literal; it cannot assert
    // "every route passes a linuxRoot" — that criterion lives here, reading
    // the real tree. B-06 was the rootfs-management entry rendering the
    // Alpine placeholder dirs instead of the bind mounts; the session entry
    // is the one documented host-based exemption.

    @Test
    fun `every fileBrowser route passes a linuxRoot except the host-based session entry`() {
        val sites = callSlices(readRepoFile(APP_NAV), "Routes.fileBrowser(")
        assertTrue(
            "Routes.fileBrowser( call sites drifted (dead literal: exactly 4):\n$sites",
            sites.size == 4,
        )
        val rooted = sites.filter { it.contains("linuxRoot = ") }
        val bare = sites.filterNot { it.contains("linuxRoot = ") }
        assertTrue("expected 3 rooted routes:\n$rooted", rooted.size == 3)
        assertTrue("expected exactly 1 bare route:\n$bare", bare.size == 1)
        assertTrue(
            "shared-folder route lost its linuxRoot:\n$rooted",
            rooted.any { it.contains("linuxRoot = \"/var/minis/\$folderId\"") },
        )
        assertTrue(
            "mounts route lost its linuxRoot:\n$rooted",
            rooted.any { it.contains("linuxRoot = \"/var/minis/mounts/\${entry.name}\"") },
        )
        assertTrue(
            "rootfs route lost its linuxRoot (the B-06 defect):\n$rooted",
            rooted.any { it.contains("linuxRoot = \"/\"") },
        )
        assertTrue(
            "the bare route is not the session entry (displayPrefix marker):\n$bare",
            bare.all { it.contains("displayPrefix = \"/var/minis\"") },
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /**
     * Source lines of the declaration at [marker].
     *
     * Ends at the first non-blank line that is back at (or above) the
     * declaration's own indent **while both the parameter list's parens and the
     * body's braces are balanced**. The two simpler rules both truncate a real
     * declaration — the "next member" cut stops at a body statement 8 spaces
     * deep (`val normalized = …`), the indent cut stops at a multi-line
     * top-level signature's closing `): File? {`, and a plain brace count stops
     * at an expression body's lambda (`…?.let { File(it, …) },` balances to
     * zero on its own line). Each failure hid the very line the assertions
     * check, which is why this rule is stated as a conjunction.
     */
    private fun declarationSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val indent = lines[start].takeWhile { it == ' ' }.length
        var paren = 0
        var brace = 0
        var end = start
        var k = start
        while (k < lines.size) {
            val s = stripStrings(lines[k])
            paren += s.count { it == '(' } - s.count { it == ')' }
            brace += s.count { it == '{' } - s.count { it == '}' }
            end = k
            k++
            var peek = k
            while (peek < lines.size && lines[peek].isBlank()) peek++
            if (peek >= lines.size) { end = lines.size - 1; break }
            if (paren <= 0 && brace <= 0 && lines[peek].takeWhile { it == ' ' }.length <= indent) break
        }
        return lines.subList(start, end + 1).joinToString("\n")
    }

    /** Drops quoted spans so braces inside string literals do not unbalance the count. */
    private fun stripStrings(line: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < line.length) {
            val c = line[i]
            if (quote != null) {
                if (c == '\\') { i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '\'' || c == '"') quote = c
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** Comment-stripped call-site slices for [marker], cut at its balanced closing paren. */
    private fun callSlices(src: String, marker: String): List<String> {
        val code = stripComments(src)
        val sites = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = code.indexOf(marker, from)
            if (at < 0) break
            var depth = 0
            var end = -1
            var i = at + marker.length - 1 // at the marker's '('
            var quote: Char? = null
            while (i < code.length) {
                val c = code[i]
                if (quote != null) {
                    if (c == '\\') { i += 2; continue }
                    if (c == quote) quote = null
                    i++
                    continue
                }
                when (c) {
                    '"', '\'' -> quote = c
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) { end = i; break }
                    }
                }
                i++
            }
            assertTrue("unbalanced parens for $marker at offset $at", end > 0)
            sites.add(code.substring(at, end + 1))
            from = end + 1
        }
        return sites
    }

    /** Removes // and block comments (nesting-aware) while keeping string literals. */
    private fun stripComments(src: String): String {
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < src.length) {
            val c = src[i]
            if (quote != null) {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == quote) quote = null
                i++
                continue
            }
            if (c == '"' || c == '\'') { quote = c; sb.append(c); i++; continue }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                var nest = 1
                i += 2
                while (i < src.length && nest > 0) {
                    if (src[i] == '*' && i + 1 < src.length && src[i + 1] == '/') { nest--; i += 2 }
                    else if (src[i] == '/' && i + 1 < src.length && src[i + 1] == '*') { nest++; i += 2 }
                    else i++
                }
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private companion object {
        private const val PROOT_KERNEL =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/PRootKernel.kt"
        private const val FILE_BROWSER =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FileBrowserViewModel.kt"
        private const val REGISTRY_SCREEN =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/settings/SharedFoldersScreen.kt"
        private const val MODEL_USE =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/ModelUseOffloadHandler.kt"
        private const val FASTIO =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/FastioOffloadHandler.kt"
        private const val APP_NAV =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"
    }
}
