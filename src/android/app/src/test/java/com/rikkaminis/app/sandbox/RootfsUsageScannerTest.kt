package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/**
 * [rootfs-usage-v1] JVM tests for the walk semantics of RootfsUsageScanner
 * using a java.nio-based Stat. The Android binding (Os.lstat + st_blocks)
 * plugs into the same walk, so these tests pin down the properties that fix
 * the over-counting bug: no symlink following, hardlink dedupe, per-dir
 * breakdown.
 */
class RootfsUsageScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** NIO-backed Stat: NOFOLLOW_LINKS + logical size (blocks are Android-only). */
    private fun nioStat(): RootfsUsageScanner.Stat = RootfsUsageScanner.Stat { path ->
        val p: Path = Paths.get(path)
        try {
            val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            RootfsUsageScanner.Node(
                dedupeKey = attrs.fileKey()?.hashCode()?.toLong(),
                bytes = attrs.size(),
                isDirectory = attrs.isDirectory,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun writeBytes(file: File, count: Int): File {
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(count) { 0x61 })
        return file
    }

    @Test
    fun `sums regular files recursively`() {
        val root = tmp.newFolder("rootfs")
        writeBytes(File(root, "a.txt"), 100)
        writeBytes(File(root, "sub/b.bin"), 250)
        writeBytes(File(root, "sub/deep/c.bin"), 300)

        val report = RootfsUsageScanner.scan(root, nioStat())
        assertEquals(650L, report.totalBytes)
    }

    @Test
    fun `does not follow file symlinks`() {
        val root = tmp.newFolder("rootfs")
        writeBytes(File(root, "big.bin"), 1_000_000)
        Files.createSymbolicLink(File(root, "link").toPath(), File(root, "big.bin").toPath())

        val report = RootfsUsageScanner.scan(root, nioStat())
        // The symlink itself is counted as a tiny node (its path length), the
        // target exactly once: total must stay near 1,000,000 and NOT reach
        // ~2,000,000 (which following the link would produce).
        assertTrue(
            "expected ~1,000,000 but got ${report.totalBytes}",
            report.totalBytes > 1_000_000 && report.totalBytes < 1_100_000,
        )
    }

    @Test
    fun `does not recurse into symlinked directories`() {
        val root = tmp.newFolder("rootfs")
        writeBytes(File(root, "real/file.bin"), 500)
        // dlink -> real : following it would double-count real/file.bin.
        Files.createSymbolicLink(File(root, "dlink").toPath(), File(root, "real").toPath())

        val report = RootfsUsageScanner.scan(root, nioStat())
        // 500 (target) + link overhead (< 500); following the dir symlink
        // would push the total to ~1000.
        assertTrue("expected ~500 but got ${report.totalBytes}", report.totalBytes in 500..999)
    }

    @Test
    fun `dedupes hardlinks by inode`() {
        val root = tmp.newFolder("rootfs")
        val a = writeBytes(File(root, "a.bin"), 4000)
        Files.createLink(File(root, "b.bin").toPath(), a.toPath())

        val report = RootfsUsageScanner.scan(root, nioStat())
        assertEquals(4000L, report.totalBytes)
    }

    @Test
    fun `groups breakdown by top-level directory`() {
        val root = tmp.newFolder("rootfs")
        writeBytes(File(root, "tmp/x"), 1000)
        writeBytes(File(root, "usr/lib/y"), 2000)
        writeBytes(File(root, "root/.z"), 3000)
        writeBytes(File(root, "top-level-file"), 500)

        val report = RootfsUsageScanner.scan(root, nioStat())
        val byName = report.entries.associate { it.name to it.bytes }
        assertEquals(1000L, byName["tmp"])
        assertEquals(2000L, byName["usr"])
        assertEquals(3000L, byName["root"])
        assertEquals(500L, byName["top-level-file"])
        assertEquals(6500L, report.totalBytes)
        // Sorted descending by size.
        val sizes = report.entries.map { it.bytes }
        assertEquals(sizes.sortedDescending(), sizes)
    }

    @Test
    fun `missing root yields empty report`() {
        val report = RootfsUsageScanner.scan(File("/nonexistent/rootfs"), nioStat())
        assertEquals(0L, report.totalBytes)
        assertTrue(report.entries.isEmpty())
    }
}
