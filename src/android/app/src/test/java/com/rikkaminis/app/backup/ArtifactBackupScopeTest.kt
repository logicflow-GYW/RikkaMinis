package com.rikkaminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-auto-backup-assets] Selection + archive framing for the artifact
 * (output-asset) backup: what gets picked from shared/ & mcp roots, what is
 * deliberately skipped (oversized, non-text, excluded dirs), and the zip
 * prefix-routing that lands "shared/…" and "mcp/…" entries in their roots on
 * restore — with traversal/absolute-path entries rejected.
 *
 * [da1034-A1] Also pins the credential edge: the MCP OAuth token bridge
 * (`mcp-servers/oauth/` — live bearer tokens + client secrets in plaintext)
 * must never be selected/archived, and a restore of an archive that still
 * carries it (pre-fix backup) must not clobber the live bridge.
 */
class ArtifactBackupScopeTest {

    private fun writeFile(root: File, rel: String, size: Int): File {
        val f = File(root, rel).apply { parentFile?.mkdirs() }
        f.writeBytes(ByteArray(size) { 'a'.code.toByte() })
        return f
    }

    // ── Selection ────────────────────────────────────────────────────────

    @Test
    fun selectsTextFilesRecursivelyAndSkipsNonText() {
        val root = File.createTempFile("scope-sel", "").apply { delete(); mkdirs() }
        try {
            val md = writeFile(root, "report.md", 10)
            val py = writeFile(root, "tools/scan.py", 10)
            writeFile(root, "nested/deep/notes.json", 10)
            val png = writeFile(root, "img.png", 10)
            val sqlite = writeFile(root, "db.sqlite", 10)

            val sel = ArtifactBackupScope.select(root)
            assertTrue(sel.files.any { it.relPath == "report.md" && it.size == 10L })
            assertTrue(sel.files.any { it.relPath == "tools/scan.py" })
            assertTrue(sel.files.any { it.relPath == "nested/deep/notes.json" })
            assertFalse(sel.files.any { it.relPath == "img.png" })
            assertFalse(sel.files.any { it.relPath == "db.sqlite" })
            assertEquals(2, sel.nonTextSkipped)
            assertEquals(3, sel.files.size)
            assertEquals(30, sel.totalBytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun skipsExcludedDirectoriesAndDotFiles() {
        val root = File.createTempFile("scope-excl", "").apply { delete(); mkdirs() }
        try {
            writeFile(root, "keep.md", 10)
            writeFile(root, "sandbox-env/parts/alpine-minirootfs.tar.gz", 10)
            writeFile(root, "sandbox-env/apk-world.txt", 10)
            // nested dirs are walked even when their name collides with an
            // excluded top-level dir (the exclusion is root-only, where the
            // environment payloads live)
            writeFile(root, "nested/sandbox-env/notes.md", 10)

            val sel = ArtifactBackupScope.select(root)
            assertEquals(
                listOf("keep.md", "nested/sandbox-env/notes.md"),
                sel.files.map { it.relPath },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizeFileIsSkippedAndCounted() {
        val root = File.createTempFile("scope-size", "").apply { delete(); mkdirs() }
        try {
            writeFile(root, "big.md", 300)
            writeFile(root, "small.md", 5)

            val sel = ArtifactBackupScope.select(root, maxFileBytes = 100)
            assertEquals(listOf("small.md"), sel.files.map { it.relPath })
            assertEquals(1, sel.oversizedSkipped)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mcpRootUsesRestrictedExtensionSet() {
        val root = File.createTempFile("scope-mcp", "").apply { delete(); mkdirs() }
        try {
            writeFile(root, "servers.json", 10)
            writeFile(root, "knowledge.jsonl", 10)
            writeFile(root, "notes.md", 10)
            writeFile(root, "daemon.log", 10)

            val sel = ArtifactBackupScope.select(
                root, allowedExtensions = setOf("json", "jsonl", "md"),
            )
            assertEquals(
                setOf("servers.json", "knowledge.jsonl", "notes.md"),
                sel.files.map { it.relPath }.toSet(),
            )
            assertEquals(1, sel.nonTextSkipped)
        } finally {
            root.deleteRecursively()
        }
    }

    // ── [FIX-R3-D / F-06] symlinks are resolved before they are trusted ──

    private fun symlinkOrSkip(target: File, link: File) {
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (e: Exception) {
            org.junit.Assume.assumeNoException("symlinks unavailable here", e)
        }
    }

    @Test
    fun symlinkEscapingTheSharedRootIsNotArchived() {
        // The reported shape: `shared/bridge_bak -> <minis-global>/mcp-servers/oauth`
        // — a *relative* escape that never touches the link's own name, so both
        // DENIED_DIRS and the restore-side `mcp/oauth/` filter missed it and
        // plaintext OAuth tokens rode every automatic backup.
        val base = File.createTempFile("scope-link", "").apply { delete(); mkdirs() }
        try {
            val root = File(base, "shared").apply { mkdirs() }
            val outside = File(base, "mcp-servers/oauth").apply { mkdirs() }
            writeFile(outside, "linear.json", 10)
            writeFile(root, "keep.md", 10)
            symlinkOrSkip(outside, File(root, "bridge_bak"))

            val sel = ArtifactBackupScope.select(root)
            assertEquals(listOf("keep.md"), sel.files.map { it.relPath })
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun symlinkedFileEscapingTheSharedRootIsNotArchived() {
        val base = File.createTempFile("scope-link-file", "").apply { delete(); mkdirs() }
        try {
            val root = File(base, "shared").apply { mkdirs() }
            val secret = writeFile(base, "outside/credentials.json", 10)
            writeFile(root, "keep.md", 10)
            symlinkOrSkip(secret, File(root, "innocent.json"))

            val sel = ArtifactBackupScope.select(root)
            assertEquals(listOf("keep.md"), sel.files.map { it.relPath })
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun symlinkToADeniedDirectoryInsideTheRootIsStillDenied() {
        // Inside the root but renamed: the LINK is called `archive`, the bytes
        // are `oauth/` — the old name-only check walked it.
        val base = File.createTempFile("scope-link-denied", "").apply { delete(); mkdirs() }
        try {
            val root = File(base, "shared").apply { mkdirs() }
            val oauth = File(root, "mcp/oauth").apply { mkdirs() }
            writeFile(oauth, "linear.json", 10)
            writeFile(root, "keep.md", 10)
            symlinkOrSkip(oauth, File(root, "archive"))

            val sel = ArtifactBackupScope.select(root)
            assertEquals(listOf("keep.md"), sel.files.map { it.relPath })
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun symlinkInsideTheRootIsStillFollowed() {
        // Fail-closed must not become "no symlinks at all": a link to a real
        // sibling artifact is a legitimate layout. Because the cycle guard is
        // keyed on the canonical path, the first name visited wins and the
        // other one is skipped — one copy of the content, never two.
        val base = File.createTempFile("scope-link-inside", "").apply { delete(); mkdirs() }
        try {
            val root = File(base, "shared").apply { mkdirs() }
            writeFile(root, "real/report.md", 10)
            symlinkOrSkip(File(root, "real"), File(root, "alias"))

            val sel = ArtifactBackupScope.select(root)
            assertEquals(1, sel.files.size)
            assertTrue(
                sel.files.single().relPath in setOf("alias/report.md", "real/report.md"),
            )
        } finally {
            base.deleteRecursively()
        }
    }

    // ── Archive round trip with prefix routing ───────────────────────────

    @Test
    fun archiveExtractRoundTripsByPrefix() {
        val sharedRoot = File.createTempFile("arch-shared", "").apply { delete(); mkdirs() }
        val mcpRoot = File.createTempFile("arch-mcp", "").apply { delete(); mkdirs() }
        val out1 = File.createTempFile("arch-out1", "").apply { delete(); mkdirs() }
        val out2 = File.createTempFile("arch-out2", "").apply { delete(); mkdirs() }
        try {
            writeFile(sharedRoot, "report.md", 10)
            writeFile(sharedRoot, "sub/tool.py", 20)
            writeFile(mcpRoot, "servers.json", 5)
            val zip = File.createTempFile("arch-zip", ".zip").apply { delete() }
            try {
                java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
                    ArtifactArchiver.writeEntries(
                        zos, sharedRoot,
                        listOf(
                            ArtifactBackupScope.SelectedFile("report.md", 10),
                            ArtifactBackupScope.SelectedFile("sub/tool.py", 20),
                        ),
                        entryPrefix = "shared/",
                    )
                    ArtifactArchiver.writeEntries(
                        zos, mcpRoot,
                        listOf(ArtifactBackupScope.SelectedFile("servers.json", 5)),
                        entryPrefix = "mcp/",
                    )
                }

                val dest = mapOf("shared" to out1, "mcp" to out2)
                val n = ArtifactArchiver.extractZipByPrefix(zip, dest)
                assertEquals(3, n)
                assertTrue(File(out1, "report.md").isFile)
                assertTrue(File(out1, "sub/tool.py").isFile)
                assertTrue(File(out2, "servers.json").isFile)
                assertEquals(10, File(out1, "report.md").length())
                assertEquals(20, File(out1, "sub/tool.py").length())
                assertEquals(5, File(out2, "servers.json").length())
            } finally {
                zip.delete()
            }
        } finally {
            sharedRoot.deleteRecursively(); mcpRoot.deleteRecursively()
            out1.deleteRecursively(); out2.deleteRecursively()
        }
    }

    @Test
    fun singleRootArchivePrefixesAllEntries() {
        val root = File.createTempFile("arch-root", "").apply { delete(); mkdirs() }
        val out = File.createTempFile("arch-out", "").apply { delete(); mkdirs() }
        try {
            writeFile(root, "report.md", 10)
            val zip = File.createTempFile("arch-zip", ".zip").apply { delete() }
            try {
                val n = ArtifactArchiver.archiveToZip(
                    root,
                    listOf(ArtifactBackupScope.SelectedFile("report.md", 10)),
                    zip,
                    entryPrefix = "shared/",
                )
                assertEquals(10, n)
                val restored = ArtifactArchiver.extractZipByPrefix(
                    zip, mapOf("shared" to out),
                )
                assertEquals(1, restored)
                assertTrue(File(out, "report.md").isFile)
            } finally {
                zip.delete()
            }
        } finally {
            root.deleteRecursively(); out.deleteRecursively()
        }
    }

    @Test
    fun extractRejectsTraversalAndUnknownPrefix() {
        val out = File.createTempFile("arch-out", "").apply { delete(); mkdirs() }
        try {
            val zip = File.createTempFile("arch-zip", ".zip").apply { delete() }
            try {
                java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
                    // traversal attempt
                    zos.putNextEntry(java.util.zip.ZipEntry("shared/../../evil.txt"))
                    zos.write("x".toByteArray()); zos.closeEntry()
                    // unknown prefix without fallback
                    zos.putNextEntry(java.util.zip.ZipEntry("other/thing.md"))
                    zos.write("x".toByteArray()); zos.closeEntry()
                }
                val n = ArtifactArchiver.extractZipByPrefix(zip, mapOf("shared" to out))
                assertEquals(0, n)
                assertFalse(File(out, "evil.txt").exists())
            } finally {
                zip.delete()
            }
        } finally {
            out.deleteRecursively()
        }
    }

    // ── [da1034-A1] credential edge: the MCP OAuth token bridge ──────────

    @Test
    fun oauthBridgeDirIsNeverSelectedAtAnyDepth() {
        val sharedRoot = File.createTempFile("scope-oauth-shared", "").apply { delete(); mkdirs() }
        val mcpRoot = File.createTempFile("scope-oauth-mcp", "").apply { delete(); mkdirs() }
        try {
            writeFile(sharedRoot, "report.md", 10)
            writeFile(sharedRoot, "tools/scan.py", 10)
            // [da1034-A1] the deny-list is name-based at every level, so a
            // shared-side dir of the same name is dropped too — see the
            // negative-decision note in the fix report.
            writeFile(sharedRoot, "oauth/notes.md", 10)
            // the real bridge dir: <mcp-servers>/oauth/<server>.json holds
            // access_token / refresh_token / client_secret in plaintext
            writeFile(mcpRoot, "oauth/linear.json", 40)
            writeFile(mcpRoot, "oauth/linear.secret", 8)
            writeFile(mcpRoot, "servers.json", 5)
            writeFile(mcpRoot, "knowledge.jsonl", 7)
            writeFile(mcpRoot, "group/oauth/nested.json", 9)

            val sharedSel = ArtifactBackupScope.select(sharedRoot)
            assertEquals(listOf("report.md", "tools/scan.py"), sharedSel.files.map { it.relPath })

            val mcpSel = ArtifactBackupScope.select(
                mcpRoot, allowedExtensions = setOf("json", "jsonl", "md"),
            )
            assertEquals(
                listOf("knowledge.jsonl", "servers.json"),
                mcpSel.files.map { it.relPath },
            )

            val zip = File.createTempFile("scope-oauth-zip", ".zip").apply { delete() }
            try {
                java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
                    ArtifactArchiver.writeEntries(zos, sharedRoot, sharedSel.files, "shared/")
                    ArtifactArchiver.writeEntries(zos, mcpRoot, mcpSel.files, "mcp/")
                }
                val names = ArrayList<String>()
                java.util.zip.ZipFile(zip).use { zf ->
                    val it = zf.entries()
                    while (it.hasMoreElements()) names.add(it.nextElement().name)
                }
                assertEquals(
                    listOf(
                        "mcp/knowledge.jsonl",
                        "mcp/servers.json",
                        "shared/report.md",
                        "shared/tools/scan.py",
                    ),
                    names.sorted(),
                )
                assertFalse(names.any { it.contains("oauth") })
                // [da1034-A1] 必须保住：shared 侧与 mcp 数据文件的字节逐字节不变
                // （writeFile 写入 size 个 'a'，这里按字面量断言，不从被测代码推导）
                java.util.zip.ZipFile(zip).use { zf ->
                    assertEquals(
                        "aaaaaaaaaa",
                        zf.getInputStream(zf.getEntry("shared/report.md")).readBytes()
                            .toString(Charsets.UTF_8),
                    )
                    assertEquals(
                        "aaaaaaaaaa",
                        zf.getInputStream(zf.getEntry("shared/tools/scan.py")).readBytes()
                            .toString(Charsets.UTF_8),
                    )
                    assertEquals(
                        "aaaaa",
                        zf.getInputStream(zf.getEntry("mcp/servers.json")).readBytes()
                            .toString(Charsets.UTF_8),
                    )
                    assertEquals(
                        "aaaaaaa",
                        zf.getInputStream(zf.getEntry("mcp/knowledge.jsonl")).readBytes()
                            .toString(Charsets.UTF_8),
                    )
                }
            } finally {
                zip.delete()
            }
        } finally {
            sharedRoot.deleteRecursively(); mcpRoot.deleteRecursively()
        }
    }

    @Test
    fun sandboxEnvExclusionStillHoldsNextToOauthDenyList() {
        val root = File.createTempFile("scope-both", "").apply { delete(); mkdirs() }
        try {
            writeFile(root, "keep.md", 10)
            writeFile(root, "sandbox-env/apk-world.txt", 10)
            writeFile(root, "oauth/token.json", 10)
            // nested sandbox-env is still walked (root-only exclusion), while
            // the oauth deny-list applies at any depth
            writeFile(root, "nested/sandbox-env/notes.md", 10)
            writeFile(root, "nested/oauth/notes.md", 10)

            val sel = ArtifactBackupScope.select(root)
            assertEquals(
                listOf("keep.md", "nested/sandbox-env/notes.md"),
                sel.files.map { it.relPath },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mcpOAuthEntryNameRecognisesEveryRouteIntoTheBridgeDir() {
        assertTrue(ConfigBackup.isMcpOAuthEntryName("mcp/oauth/linear.json"))
        assertTrue(ConfigBackup.isMcpOAuthEntryName("mcp/oauth/"))
        assertTrue(ConfigBackup.isMcpOAuthEntryName("mcp//oauth/linear.json"))
        assertTrue(ConfigBackup.isMcpOAuthEntryName("mcp/./oauth/linear.json"))
        assertTrue(ConfigBackup.isMcpOAuthEntryName("./mcp/oauth/linear.json"))
        assertFalse(ConfigBackup.isMcpOAuthEntryName("mcp/servers.json"))
        assertFalse(ConfigBackup.isMcpOAuthEntryName("mcp/servers/oauth.json"))
        assertFalse(ConfigBackup.isMcpOAuthEntryName("shared/oauth/notes.md"))
        assertFalse(ConfigBackup.isMcpOAuthEntryName("oauth/linear.json"))
    }

    @Test
    fun stage9FilterKeepsRestoreFromClobberingTheLiveBridge() {
        val mcpRoot = File.createTempFile("stage9-mcp", "").apply { delete(); mkdirs() }
        val sharedRoot = File.createTempFile("stage9-shared", "").apply { delete(); mkdirs() }
        try {
            // this device's live bridge + a normal mcp data file
            writeFile(mcpRoot, "servers.json", 5)
            File(mcpRoot, "oauth").mkdirs()
            val liveBridge = File(mcpRoot, "oauth/linear.json").apply { writeText("LIVE") }
            assertFalse(File(mcpRoot, "oauth/linear.secret").exists())

            val zip = File.createTempFile("stage9-zip", ".zip").apply { delete() }
            val filtered = File.createTempFile("stage9-filtered", ".zip").apply { delete() }
            val dests = mapOf("mcp" to mcpRoot, "shared" to sharedRoot)
            try {
                // an archive written by a PRE-FIX build: bridge entries intact
                java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
                    zos.putNextEntry(java.util.zip.ZipEntry("mcp/oauth/linear.json"))
                    zos.write("STALE".toByteArray()); zos.closeEntry()
                    zos.putNextEntry(java.util.zip.ZipEntry("mcp/oauth/linear.secret"))
                    zos.write("stale-secret".toByteArray()); zos.closeEntry()
                    zos.putNextEntry(java.util.zip.ZipEntry("mcp/servers.json"))
                    zos.write("S".toByteArray()); zos.closeEntry()
                    zos.putNextEntry(java.util.zip.ZipEntry("shared/report.md"))
                    zos.write("R".toByteArray()); zos.closeEntry()
                }

                // negative control: without the Stage 9 filter the archive
                // really does overwrite the live bridge (so the test has teeth)
                val wouldClobber = ArtifactArchiver.extractZipByPrefix(zip, dests)
                assertEquals(4, wouldClobber)
                assertEquals("STALE", liveBridge.readText())

                liveBridge.writeText("LIVE")
                File(mcpRoot, "oauth/linear.secret").delete()
                assertEquals(2, ConfigBackup.stripMcpOAuthEntries(zip, filtered).oauthDropped)
                assertEquals(2, ArtifactArchiver.extractZipByPrefix(filtered, dests))

                assertEquals("LIVE", liveBridge.readText())
                assertFalse(File(mcpRoot, "oauth/linear.secret").exists())
                assertEquals("S", File(mcpRoot, "servers.json").readText())
                assertEquals("R", File(sharedRoot, "report.md").readText())

                // a post-fix archive carries no bridge entries → nothing dropped
                val clean = File.createTempFile("stage9-clean", ".zip").apply { delete() }
                val cleanOut = File.createTempFile("stage9-clean-out", ".zip").apply { delete() }
                try {
                    java.util.zip.ZipOutputStream(clean.outputStream()).use { zos ->
                        zos.putNextEntry(java.util.zip.ZipEntry("mcp/servers.json"))
                        zos.write("S".toByteArray()); zos.closeEntry()
                    }
                    assertEquals(0, ConfigBackup.stripMcpOAuthEntries(clean, cleanOut).dropped)
                } finally {
                    clean.delete(); cleanOut.delete()
                }
            } finally {
                zip.delete(); filtered.delete()
            }
        } finally {
            mcpRoot.deleteRecursively(); sharedRoot.deleteRecursively()
        }
    }

    @Test
    fun serverListEntryNamePinsOnlyTheOneFileUnderTheMcpRoot() {
        // The drop must be per-file, not per-prefix: everything else the agent
        // wrote under mcp/ still restores.
        assertTrue(ConfigBackup.isMcpServerListEntryName("mcp/servers.json"))
        assertTrue(ConfigBackup.isMcpServerListEntryName("mcp//servers.json"))
        assertTrue(ConfigBackup.isMcpServerListEntryName("mcp/./servers.json"))
        assertFalse(ConfigBackup.isMcpServerListEntryName("./mcp/servers.json/x"))
        assertFalse(ConfigBackup.isMcpServerListEntryName("mcp/other.json"))
        assertFalse(ConfigBackup.isMcpServerListEntryName("mcp/servers/oauth.json"))
        assertFalse(ConfigBackup.isMcpServerListEntryName("mcp/oauth/servers.json"))
        assertFalse(ConfigBackup.isMcpServerListEntryName("shared/servers.json"))
        assertFalse(ConfigBackup.isMcpServerListEntryName("servers.json"))
    }

    /**
     * [dv28-a5 A-05] Stage 7 merges the backup's `mcpServers` into this
     * device's list; Stage 9 then restored `mcp/servers.json` verbatim, so the
     * merged file was replaced by the backup's list and every server the backup
     * did not carry vanished (on disk — the in-memory merge hid it until the
     * next cold start). The flag is Stage 7's answer to "did the merge run?".
     */
    @Test
    fun stage9SkipsTheServerListOnlyWhenStage7MergedIt() {
        val mcpRoot = File.createTempFile("stage9b-mcp", "").apply { delete(); mkdirs() }
        val sharedRoot = File.createTempFile("stage9b-shared", "").apply { delete(); mkdirs() }
        val zip = File.createTempFile("stage9b-zip", ".zip").apply { delete() }
        try {
            // this device's merged servers.json (Stage 7 already wrote it)
            File(mcpRoot, "servers.json").writeText("MERGED")
            val dests = mapOf("mcp" to mcpRoot, "shared" to sharedRoot)
            java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("mcp/servers.json"))
                zos.write("BACKUP".toByteArray()); zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("mcp/notes.md"))
                zos.write("N".toByteArray()); zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("mcp/oauth/linear.json"))
                zos.write("STALE".toByteArray()); zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry("shared/report.md"))
                zos.write("R".toByteArray()); zos.closeEntry()
            }

            // Stage 7 merged ⇒ the raw copy must not land.
            val merged = File.createTempFile("stage9b-merged", ".zip").apply { delete() }
            try {
                val res = ConfigBackup.stripMcpOAuthEntries(zip, merged, dropPrestagedServerList = true)
                assertEquals(1, res.oauthDropped)
                assertEquals(1, res.prestagedDropped)
                assertEquals(2, res.dropped)
                assertEquals(2, ArtifactArchiver.extractZipByPrefix(merged, dests))
                assertEquals("MERGED", File(mcpRoot, "servers.json").readText())
                assertEquals("N", File(mcpRoot, "notes.md").readText())
                assertEquals("R", File(sharedRoot, "report.md").readText())
                assertFalse(File(mcpRoot, "oauth/linear.json").exists())
            } finally {
                merged.delete()
            }

            // no `mcpServers` section in the backup ⇒ Stage 7 never merged, and
            // the archive is the only carrier of the list: restore it.
            val raw = File.createTempFile("stage9b-raw", ".zip").apply { delete() }
            try {
                val res = ConfigBackup.stripMcpOAuthEntries(zip, raw)
                assertEquals(0, res.prestagedDropped)
                assertEquals(1, res.oauthDropped)
                assertEquals("MERGED", File(mcpRoot, "servers.json").readText())
                assertEquals(3, ArtifactArchiver.extractZipByPrefix(raw, dests))
                assertEquals("BACKUP", File(mcpRoot, "servers.json").readText())
            } finally {
                raw.delete()
            }
        } finally {
            zip.delete()
            mcpRoot.deleteRecursively(); sharedRoot.deleteRecursively()
        }
    }
}
