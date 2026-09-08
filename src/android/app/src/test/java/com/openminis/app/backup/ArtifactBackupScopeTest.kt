package com.openminis.app.backup

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
}
