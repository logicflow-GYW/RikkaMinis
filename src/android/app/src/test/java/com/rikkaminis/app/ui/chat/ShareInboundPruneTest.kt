package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/da1024-A] deep-audit-1024 ISSUE-01 — the `cacheDir/share_inbound/`
 * staging dir (written by `ChatViewModel.addAttachmentFromStagedShare`) had
 * three faces:
 *
 *  1. it was never declared in `res/xml/file_provider_paths.xml`, so
 *     `FileProvider.getUriForFile` threw `IllegalArgumentException` for every
 *     staged composer attachment the user tried to open externally / re-share;
 *  2. the copy (up to 100 MB per file, `ShareReceiverActivity` cap) plus
 *     `cleanSharedFiles`'s directory sweep ran on the UI thread;
 *  3. nothing ever deleted the staged copies, so they accumulated forever.
 *
 * The prune below is the TTL half of the fix; the two shape tests pin the
 * wiring of the other two halves against the real sources (a gate does the
 * exact brace-level check — `scripts/scan/file_provider_paths_coverage_check.py`
 * criteria C8/C9 — these tests are the second, independent reading).
 *
 * Expectations are literals on purpose: deriving them from
 * `SHARE_INBOUND_TTL_MS` would let a recalibrated constant keep the suite green.
 */
class ShareInboundPruneTest {

    private val now = 1_700_000_000_000L
    private val oneDayMs = 24L * 60L * 60L * 1000L

    @Test
    fun `expired share_inbound files older than the window are deleted while fresh files survive`() {
        val dir = emptyTempDir()
        val expired = fileAt(dir, "staged-old.txt", now - oneDayMs - 60_000L)
        val fresh = fileAt(dir, "staged-fresh.txt", now - oneDayMs + 60_000L)
        assertEquals(2, dir.listFiles()!!.size)

        val deleted = pruneExpiredShareInboundFiles(dir, now, maxAgeMs = oneDayMs)

        assertEquals("exactly the one file past the window is swept", 1, deleted)
        assertFalse("the expired staged copy must be gone", expired.exists())
        assertTrue("a copy the user may still send must survive", fresh.exists())
        assertEquals("one staged copy is left on disk", 1, dir.listFiles()!!.size)
    }

    @Test
    fun `default retention window keeps 24h and drops anything older`() {
        val dir = emptyTempDir()
        val older = fileAt(dir, "older.bin", now - oneDayMs - 1L)
        val newer = fileAt(dir, "newer.bin", now - oneDayMs + 1L)

        // No maxAgeMs argument: exercises the production default.
        val deleted = pruneExpiredShareInboundFiles(dir, now)

        assertEquals(1, deleted)
        assertFalse("just past 24h must be swept", older.exists())
        assertTrue("just inside 24h must be kept", newer.exists())
    }

    @Test
    fun `non-existent or empty share_inbound dir returns 0 without throwing`() {
        val missing = java.io.File(emptyTempDir(), "share_inbound")
        assertEquals(0, pruneExpiredShareInboundFiles(missing, now))

        val empty = emptyTempDir()
        assertEquals(0, pruneExpiredShareInboundFiles(empty, now))
    }

    @Test
    fun `ChatViewModel and ChatScreen wire share_inbound through prune and Dispatchers IO`() {
        val vm = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt")
        val screen = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatScreen.kt")
        val xml = readRepoFile("app/src/main/res/xml/file_provider_paths.xml")

        // Face 1 — the provider root must be declared, and the writer site the
        // coverage registry looks for must still read exactly like this.
        assertTrue(
            "share_inbound must be declared or getUriForFile throws on every open/share",
            xml.contains("<cache-path name=\"share-inbound\" path=\"share_inbound/\" />"),
        )
        assertTrue(
            "the staging-dir writer site must stay where the coverage registry finds it",
            Regex("""File\(\s*context\.cacheDir,\s*"share_inbound"\s*\)""").containsMatchIn(vm),
        )

        // Face 2 — the copy must be able to leave the main thread.
        val signature = "suspend fun addAttachmentFromStagedShare(file: java.io.File)"
        assertTrue("addAttachmentFromStagedShare must be `suspend`", vm.contains(signature))
        val fn = vm.substringAfter(signature)
        assertTrue(
            "the staged copy must switch to Dispatchers.IO",
            fn.contains("withContext(Dispatchers.IO)"),
        )

        // Face 3 — the prune must run on that IO switch, before the copy.
        val io = fn.substringAfter("withContext(Dispatchers.IO)")
        val pruneAt = io.indexOf("pruneExpiredShareInboundFiles(")
        val copyAt = io.indexOf("copyTo(")
        assertTrue("the orphan prune must be wired into the staging path", pruneAt >= 0)
        assertTrue("the prune must precede the copy", copyAt >= 0 && pruneAt < copyAt)
        assertTrue(
            "the retention window must stay 24h",
            vm.contains("internal const val SHARE_INBOUND_TTL_MS = 24L * 60L * 60L * 1000L"),
        )

        // Face 2 (consumer side) — draining a share is disk work too.
        val drain = screen.substringAfter("LaunchedEffect(shareBufferVersion) {")
        assertTrue(
            "the share drain must leave the main thread",
            drain.contains("withContext(Dispatchers.IO)"),
        )
        assertTrue(
            "the drain must still attach staged files",
            drain.contains("addAttachmentFromStagedShare("),
        )
        val sweepAt = drain.indexOf("cleanSharedFiles(")
        assertTrue("the staging-dir sweep must stay in the drain", sweepAt >= 0)
        assertTrue(
            "the sweep must happen after the IO switch, not on the UI thread",
            drain.indexOf("withContext(Dispatchers.IO)") < sweepAt,
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun emptyTempDir(): java.io.File {
        val f = java.io.File.createTempFile("share_inbound", "test")
        f.delete()
        f.mkdirs()
        return f
    }

    private fun fileAt(dir: java.io.File, name: String, lastModified: Long): java.io.File {
        val f = java.io.File(dir, name)
        f.writeText("staged")
        f.setLastModified(lastModified)
        return f
    }

    private fun readRepoFile(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }
}
