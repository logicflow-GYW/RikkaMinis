package com.rikkaminis.app.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArtifactIntegrityTest {
    private fun tree(block: (File) -> Unit) {
        val tmp = Files.createTempDirectory("artifact-integrity-").toFile()
        try { block(tmp) } finally { tmp.deleteRecursively() }
    }
    private fun zip(tmp: File, vararg entries: Pair<String, String>): File = File(tmp, "in.zip").apply {
        ZipOutputStream(outputStream()).use { out -> for ((name, text) in entries) {
            out.putNextEntry(ZipEntry(name)); out.write(text.toByteArray()); out.closeEntry()
        } }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("must reject oversized archive") } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("byte limit"))
        }
    }
    @Test fun singleMemberOverBudgetLeavesAllExistingTargetsUntouched() = tree { tmp ->
        val shared = File(tmp, "shared").apply { mkdirs() }
        File(shared, "first.txt").writeText("OLD-FIRST"); File(shared, "late.txt").writeText("OLD-LATE")
        val input = zip(tmp, "shared/first.txt" to "ok", "shared/late.txt" to "123456789")
        rejected { ArtifactArchiver.extractZipByPrefix(input, mapOf("shared" to shared), maxFileBytes = 8, maxTotalBytes = 100) }
        assertEquals("OLD-FIRST", File(shared, "first.txt").readText()); assertEquals("OLD-LATE", File(shared, "late.txt").readText())
        assertEquals(listOf("first.txt", "late.txt"), shared.list()!!.sorted())
    }
    @Test fun cumulativeBudgetIsIndependentOfSingleFileBudget() = tree { tmp ->
        val shared = File(tmp, "shared").apply { mkdirs() }; File(shared, "a.txt").writeText("ORIGINAL")
        val input = zip(tmp, "shared/a.txt" to "12345", "shared/b.txt" to "6789")
        rejected { ArtifactArchiver.extractZipByPrefix(input, mapOf("shared" to shared), maxFileBytes = 8, maxTotalBytes = 8) }
        assertEquals("ORIGINAL", File(shared, "a.txt").readText()); assertFalse(File(shared, "b.txt").exists())
    }
    @Test fun exactBudgetPublishesBothRootsAndSkipsUnknownPrefix() = tree { tmp ->
        val shared = File(tmp, "shared").apply { mkdirs() }; val mcp = File(tmp, "mcp-servers").apply { mkdirs() }
        File(shared, "a.txt").writeText("OLD")
        val input = zip(tmp, "shared/a.txt" to "1234", "mcp/b.json" to "5678", "unknown/x.txt" to "")
        assertEquals(2, ArtifactArchiver.extractZipByPrefix(input, mapOf("shared" to shared, "mcp" to mcp), maxFileBytes = 4, maxTotalBytes = 8))
        assertEquals("1234", File(shared, "a.txt").readText()); assertEquals("5678", File(mcp, "b.json").readText())
        assertFalse(shared.list()!!.any { it.startsWith(".artifact-restore-") })
    }
    @Test fun excludedMembersAreAlsoCharged() = tree { tmp ->
        val shared = File(tmp, "shared").apply { mkdirs() }; File(shared, "a.txt").writeText("OLD")
        val input = zip(tmp, "shared/a.txt" to "1234", "unknown/large.txt" to "56789")
        rejected { ArtifactArchiver.extractZipByPrefix(input, mapOf("shared" to shared), maxFileBytes = 8, maxTotalBytes = 8) }
        assertEquals("OLD", File(shared, "a.txt").readText())
    }
    @Test fun invalidParentDetectedBeforeAnyPublish() = tree { tmp ->
        val shared = File(tmp, "shared").apply { mkdirs() }; File(shared, "a.txt").writeText("OLD")
        File(shared, "parent").writeText("KEEP")
        val input = zip(tmp, "shared/a.txt" to "NEW", "shared/parent/b.txt" to "b")
        try { ArtifactArchiver.extractZipByPrefix(input, mapOf("shared" to shared)); fail("parent is a file") }
        catch (_: IllegalArgumentException) { }
        assertEquals("OLD", File(shared, "a.txt").readText()); assertEquals("KEEP", File(shared, "parent").readText())
    }
    @Test fun firstFilterBudgetsKeptOAuthAndPrestagedMembersBeforeExtraction() = tree { tmp ->
        val shared = File(tmp, "shared").apply { mkdirs() }
        File(shared, "a.txt").writeText("ORIGINAL")
        for (name in listOf("shared/a.txt", "mcp/oauth/secret.json", "mcp/servers.json")) {
            val input = zip(tmp, name to "123456789")
            rejected { ConfigBackup.stripMcpOAuthEntries(input, File(tmp, "filtered.zip"),
                dropPrestagedServerList = true, maxFileBytes = 8, maxTotalBytes = 100) }
            assertEquals("ORIGINAL", File(shared, "a.txt").readText())
        }
        val input = zip(tmp, "shared/a.txt" to "12345", "mcp/oauth/secret.json" to "6789")
        rejected { ConfigBackup.stripMcpOAuthEntries(input, File(tmp, "filtered.zip"), maxFileBytes = 8, maxTotalBytes = 8) }
    }
    @Test fun defaultExpansionLimitsMatchTwoExportRoots() {
        val budget = ArtifactArchiver.ExpansionBudget()
        assertEquals(4194304L, budget.maxFileBytes)
        assertEquals(25165824L, budget.maxTotalBytes)
    }
    @Test fun mcpAliasCannotBypassTransformAndOrdinaryJsonPassesThrough() = tree { tmp ->
        val root = File(tmp, "mcp-servers").apply { mkdirs() }
        val sentinel = "SENTINEL-CREDENTIAL-1120"
        File(root, "servers.json").writeText("""{"mcpServers":{"s":{"url":"https://mcp.example/sse","headers":{"Authorization":"$sentinel"}}}}""")
        File(root, "plain.json").writeText("""{"value":123}""")
        Files.createSymbolicLink(File(root, "ordinary.json").toPath(), File(root, "servers.json").toPath())
        Files.createSymbolicLink(File(root, "dangling.json").toPath(), File(root, "missing.json").toPath())
        val outside = File(tmp, "outside.json").apply { writeText(sentinel) }
        Files.createSymbolicLink(File(root, "escape.json").toPath(), outside.toPath())
        val selection = ArtifactBackupScope.select(root, rejectFileSymlinks = true)
        assertEquals(listOf("plain.json", "servers.json"), selection.files.map { it.relPath })
        assertEquals(3, selection.nonTextSkipped)
        val out = File(tmp, "safe.zip")
        val dropped = mutableListOf<String>()
        // Bypass selection deliberately: the actual read boundary is a second wall.
        val staleSelection = selection.files + ArtifactBackupScope.SelectedFile("ordinary.json", 1)
        ZipOutputStream(out.outputStream()).use { ArtifactArchiver.writeEntries(it, root, staleSelection, "mcp/", ConfigBackup::redactMcpArtifactEntry, dropped) }
        assertEquals(listOf("ordinary.json"), dropped)
        ZipFile(out).use { zf ->
            assertNull(zf.getEntry("mcp/ordinary.json"))
            for (entry in zf.entries().asSequence()) assertFalse(zf.getInputStream(entry).reader().readText().contains(sentinel))
            assertEquals("""{"value":123}""", zf.getInputStream(zf.getEntry("mcp/plain.json")).reader().readText())
        }
        val raw = File(tmp, "with-secrets.zip")
        ArtifactArchiver.archiveToZip(root, ArtifactBackupScope.select(root).files, raw, "mcp/")
        ZipFile(raw).use { assertTrue(it.getInputStream(it.getEntry("mcp/servers.json")).reader().readText().contains(sentinel)) }
    }
}
