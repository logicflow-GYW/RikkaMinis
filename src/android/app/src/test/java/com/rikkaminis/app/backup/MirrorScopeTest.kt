package com.rikkaminis.app.backup

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [feat/auto-backup-external-mirror] Selection rules + the plain-file I/O
 * target. Every decision that can be wrong in a harmful way is pushed into
 * these two objects (which are Android-free) and pinned here, and the I/O target
 * itself is plain `java.io`, so the write/read/delete contract is pinned here
 * too. `MirrorStore` keeps only the Context-dependent shell.
 */
class MirrorScopeTest {

    private val root: File = Files.createTempDirectory("mirror-scope").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun write(rel: String, text: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    // ---------------------------------------------------------------- memory

    @Test
    fun selectMemory_takesTextSkipsBinariesAndOversize() {
        write("memory/2026-10-29.md", "hello")           // 5 bytes
        write("memory/sub/GLOBAL.md", "abc")             // 3 bytes
        write("memory/notes.txt", "xy")                  // 2 bytes
        write("memory/photo.png", "not really a png")
        write("memory/huge.md", "x".repeat((4 * 1024 * 1024) + 1))
        write("memory/.git/config.md", "ignored")

        val picked = MirrorScope.selectMemory(File(root, "memory"))

        assertEquals(
            listOf("memory/2026-10-29.md", "memory/notes.txt", "memory/sub/GLOBAL.md"),
            picked.map { it.relPath },
        )
        assertEquals(listOf(5L, 2L, 3L), picked.map { it.size })
        // The source File is carried so the sync never re-resolves the host
        // path from relPath; pin that it points at the real selected file.
        assertTrue(picked.all { it.file.isFile })
        assertEquals("2026-10-29.md", picked[0].file.name)
    }

    @Test
    fun selectMemory_skipsSymlinks() {
        val real = write("memory/real.md", "real")
        val link = File(root, "memory/link.md")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
        } catch (t: Throwable) {
            return // filesystem without symlink support: nothing to pin here
        }
        val picked = MirrorScope.selectMemory(File(root, "memory"))
        assertEquals(listOf("memory/real.md"), picked.map { it.relPath })
    }

    // ---------------------------------------------------------------- shared

    @Test
    fun includeSharedDir_depthRules() {
        // root: only work/ descends
        assertTrue(MirrorScope.includeSharedDir("", "work"))
        assertFalse(MirrorScope.includeSharedDir("", "archive"))
        assertFalse(MirrorScope.includeSharedDir("", "tools"))
        // one level of batch names: always
        assertTrue(MirrorScope.includeSharedDir("work", "witness-1027"))
        assertTrue(MirrorScope.includeSharedDir("work", "anything-new"))
        // inside a batch: only the document directories
        assertTrue(MirrorScope.includeSharedDir("work/witness-1027", "fix-tasks"))
        assertTrue(MirrorScope.includeSharedDir("work/witness-1027", "reports"))
        assertFalse(MirrorScope.includeSharedDir("work/witness-1027", "repo"))
        assertFalse(MirrorScope.includeSharedDir("work/witness-1027", "wt-r4-p"))
        // below a document directory: everything, so nested trees are included
        assertTrue(MirrorScope.includeSharedDir("work/witness-1027/fix-tasks", "day-1"))
        // .git never, at any depth
        assertFalse(MirrorScope.includeSharedDir("work/witness-1027/fix-tasks", ".git"))
    }

    @Test
    fun selectShared_takesTopDocsAndWorkDocumentsOnly() {
        write("shared/backlog.md", "1")                                   // in
        write("shared/sandbox-limits.md", "2")                            // in
        write("shared/tools/helper.py", "3")                              // out: tools/
        write("shared/work/batch-a/fix-tasks/task.md", "4")               // in
        write("shared/work/batch-a/reports/r.md", "5")                    // in
        write("shared/work/batch-a/repo/README.md", "6")                  // out: repo/
        write("shared/work/batch-a/wt-p/src/Main.kt", "7")                // out
        write("shared/work/batch-a/probe/run.py", "8")                    // out: not a key dir
        write("shared/work/batch-a/fix-tasks/nested/deep.md", "9")        // in

        val picked = MirrorScope.selectShared(File(root, "shared"))

        assertEquals(
            listOf(
                "shared/backlog.md",
                "shared/sandbox-limits.md",
                "shared/work/batch-a/fix-tasks/nested/deep.md",
                "shared/work/batch-a/fix-tasks/task.md",
                "shared/work/batch-a/reports/r.md",
            ),
            picked.map { it.relPath },
        )
    }

    // ------------------------------------------------------------ FileTarget
    //
    // [mirror-filetarget-1114] Moved to [MirrorFileTargetTest]. These four cases
    // outlived their original reason: they pinned the API 26–28 fallback that
    // nobody ran, while the API 29+ writer — the one that actually shipped —
    // could only be exercised on a device. The file target is now the only
    // writer, so its contract lives in its own class with the link-safety and
    // temp-file cases this file never had. Keeping both would mean two copies of
    // the same assertions drifting apart.

    // ------------------------------------------------- credential red line (P0)

    @Test
    fun credentialNamedFilesAreNeverMirroredWhereverTheySit() {
        // The tierflow ledgers carry phone numbers and passwords in the clear
        // and live at the shared/ top level — squarely inside this scope.
        write("shared/tierflow-keys-2026-1015.md", "15000000000----secret")
        write("shared/tierflow-keys-2026-1015.csv", "15000000000,secret")
        write("shared/work/deep-audit-1028/reports/oauth-secret.md", "k=v")
        write("memory/api-tokens.md", "k=v")
        write("memory/.env.production", "K=v")

        val shared = MirrorScope.selectShared(File(root, "shared"))
        val memory = MirrorScope.selectMemory(File(root, "memory"))

        assertEquals(emptyList<String>(), shared.map { it.relPath })
        assertEquals(emptyList<String>(), memory.map { it.relPath })
    }

    @Test
    fun ordinaryDocumentsStillMirrorAfterTheDenyRule() {
        // Negative control for the rule above: the documents the mirror exists
        // for must keep flowing, otherwise the red line would have "solved" the
        // leak by disabling the feature.
        write("shared/backlog.md", "sections")
        write("shared/sandbox-limits.md", "symptoms")
        write("memory/2026-10-29.md", "log line")

        val picked = (MirrorScope.selectShared(File(root, "shared")) +
            MirrorScope.selectMemory(File(root, "memory"))).map { it.relPath }.sorted()

        assertEquals(
            listOf("memory/2026-10-29.md", "shared/backlog.md", "shared/sandbox-limits.md"),
            picked,
        )
    }

    @Test
    fun isDeniedName_table() {
        assertTrue(MirrorScope.isDeniedName("tierflow-keys-2026-1015.md"))
        assertTrue(MirrorScope.isDeniedName("api-tokens.json"))
        assertTrue(MirrorScope.isDeniedName("server_credentials.txt"))
        assertTrue(MirrorScope.isDeniedName(".env"))
        // Ordinary names must not be caught.
        assertFalse(MirrorScope.isDeniedName("backlog.md"))
        assertFalse(MirrorScope.isDeniedName("sandbox-limits.md"))
        assertFalse(MirrorScope.isDeniedName("SKILL.md"))
        assertFalse(MirrorScope.isDeniedName("keyboard-notes.md"))
        assertFalse(MirrorScope.isDeniedName("monkeys.md"))
    }

    // ------------------------------------- [FIX-R5-A2 / A-F-04] case + roots

    @Test
    fun isDeniedName_table_coversCaseAndTheMissingRoots() {
        // The spellings a person actually types: the first cut matched
        // case-sensitively, so all of these shipped to public Download/.
        assertTrue(MirrorScope.isDeniedName("API_KEYS.md"))
        assertTrue(MirrorScope.isDeniedName("Secrets.json"))
        assertTrue(MirrorScope.isDeniedName(".ENV"))
        assertTrue(MirrorScope.isDeniedName("Credentials.txt"))
        // camelCase and the auth/cookie/bearer families
        assertTrue(MirrorScope.isDeniedName("apiKey.json"))
        assertTrue(MirrorScope.isDeniedName("auth.json"))
        assertTrue(MirrorScope.isDeniedName("cookies.txt"))
        assertTrue(MirrorScope.isDeniedName("bearer_token.txt"))
        // Word-boundary discipline for the added roots: a prefix or suffix
        // that merely STARTS like a root is an ordinary word.
        assertFalse(MirrorScope.isDeniedName("author-notes.md"))
        assertFalse(MirrorScope.isDeniedName("authored-by-agent.md"))
        assertFalse(MirrorScope.isDeniedName("cookiecutter.md"))
        assertFalse(MirrorScope.isDeniedName("bearing-capacity.md"))
    }

    @Test
    fun capitalisedCredentialNamesAreNeverMirrored() {
        write("memory/2026-10-29.md", "log")
        write("memory/API_KEYS.md", "k=v")
        write("memory/.ENV", "K=V")
        write("shared/Secrets.json", "{}")
        write("shared/backlog.md", "doc")

        assertEquals(
            listOf("memory/2026-10-29.md"),
            MirrorScope.selectMemory(File(root, "memory")).map { it.relPath },
        )
        assertEquals(
            listOf("shared/backlog.md"),
            MirrorScope.selectShared(File(root, "shared")).map { it.relPath },
        )
    }

    @Test
    fun credentialDirectoriesAreNotWalkedAtAnyDepth() {
        // `oauth/linear.json` and `secrets/config.json` carry tokens under
        // entirely ordinary file names — only the directory tells the truth.
        write("memory/oauth/linear.json", "{}")
        write("memory/Secrets/config.json", "{}")
        write("memory/credentials/nested/deep.json", "{}")
        write("memory/keep.md", "doc")

        assertEquals(
            listOf("memory/keep.md"),
            MirrorScope.selectMemory(File(root, "memory")).map { it.relPath },
        )
    }

    @Test
    fun skillDirectoryWithKeyInItsNameStillMirrors() {
        // Negative control for the directory rule, and the reason it is a
        // whole-name container list instead of isDeniedName(): this directory
        // DOES match the file rule ("-key-"), so reusing that rule on
        // directories would silently drop the user's most-used skill from the
        // mirror — a functional regression dressed up as a red line.
        assertTrue(MirrorScope.isDeniedName("tierflow-key-harvest"))
        write("skills/tierflow-key-harvest/SKILL.md", "skill")
        write("skills/tierflow-key-harvest/scripts/tf_status.py", "print(1)")
        write("skills/tierflow-key-harvest/keys/ledger.json", "{}")

        assertEquals(
            listOf(
                "skills/tierflow-key-harvest/SKILL.md",
                "skills/tierflow-key-harvest/scripts/tf_status.py",
            ),
            MirrorScope.selectSkills(File(root, "skills")).map { it.relPath },
        )
    }
}
