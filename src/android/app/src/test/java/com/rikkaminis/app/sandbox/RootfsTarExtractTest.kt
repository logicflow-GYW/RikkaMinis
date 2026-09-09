package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * JVM tests for [extractTar]'s targeted-restore filtering (onlyPrefixes).
 * Covers: prefix filtering, stream alignment when entries are skipped,
 * multi-entry prefix matches (symlink + versioned target), exec-bit
 * preservation, symlink materialization, and the no-filter regression case.
 */
class RootfsTarExtractTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tarEntry(
        name: String,
        content: ByteArray = ByteArray(0),
        typeflag: Char = '0',
        mode: Int = 0b111_101_101, // 0755
        linkName: String = "",
    ): ByteArray {
        val header = ByteArray(512)
        fun put(s: String, off: Int, len: Int) {
            val bytes = s.toByteArray(StandardCharsets.US_ASCII)
            bytes.copyInto(header, off, 0, minOf(len, bytes.size))
        }
        put(name, 0, 100)
        put(mode.toString(8).padStart(6, '0') + "\u0000", 100, 8)
        put("000000\u0000", 108, 8)
        put("000000\u0000", 116, 8)
        put(content.size.toString(8).padStart(11, '0') + "\u0000", 124, 12)
        put("00000000000\u0000", 136, 12)
        header[156] = typeflag.code.toByte()
        put(linkName, 157, 100)
        put("ustar\u0000", 257, 6)
        put("00", 263, 2)
        // Checksum: sum of the header with the checksum field as spaces.
        header.fill(0x20.toByte(), 148, 156)
        val sum = header.sumOf { it.toInt() and 0xFF }
        put(sum.toString(8).padStart(6, '0') + "\u0000 ", 148, 8)
        return header + content + ByteArray(padding(content.size))
    }

    private fun padding(size: Int): Int = if (size % 512 == 0) 0 else 512 - (size % 512)

    private fun extract(entries: List<ByteArray>, prefixes: Set<String>? = null) {
        val bytes = entries.fold(ByteArray(0)) { acc, e -> acc + e } + ByteArray(1024)
        extractTar(ByteArrayInputStream(bytes), tmp.root, prefixes)
    }

    // --- helpers ---------------------------------------------------------

    private fun assertFile(path: String, expectedContent: String) {
        val f = tmp.root.resolve(path)
        assertTrue("missing $path", f.isFile)
        assertEquals(expectedContent, f.readText())
    }

    // --- tests -----------------------------------------------------------

    @Test
    fun `onlyPrefixes extracts matching entries and skips the rest`() {
        val big = ByteArray(4096) { it.toByte() }
        extract(
            listOf(
                tarEntry("bin/bash", "#!/bin/bash\n".toByteArray()),
                tarEntry("usr/share/ignored", big), // data + padding must be consumed
                tarEntry("bin/sh", "x".toByteArray()),
            ),
            prefixes = setOf("bin/"),
        )
        assertFile("bin/bash", "#!/bin/bash\n")
        assertFile("bin/sh", "x")
        assertFalse("usr/share/ignored must be filtered out", tmp.root.resolve("usr/share/ignored").exists())
    }

    @Test
    fun `filtered entry data is fully consumed so later entries parse`() {
        val big = ByteArray(3000) { 'A'.code.toByte() } // non-512-multiple: exercises padding skip
        extract(
            listOf(
                tarEntry("bin/bash", "#!/bin/bash\n".toByteArray()),
                tarEntry("usr/share/big", big),
                tarEntry("etc/after", "after".toByteArray()),
            ),
            prefixes = setOf("bin/"),
        )
        // The skipped 3000-byte entry must not corrupt parsing of the last entry.
        assertFalse(tmp.root.resolve("etc/after").exists())
        assertFile("bin/bash", "#!/bin/bash\n")
    }

    @Test
    fun `prefix matches versioned symlink chain together`() {
        extract(
            listOf(
                tarEntry("usr/lib/libreadline.so.8.2", "ELF-readline".toByteArray()),
                tarEntry(
                    "usr/lib/libreadline.so.8",
                    typeflag = '2',
                    linkName = "libreadline.so.8.2",
                ),
            ),
            prefixes = setOf("usr/lib/libreadline"),
        )
        val link = tmp.root.resolve("usr/lib/libreadline.so.8")
        assertTrue("symlink must exist", Files.isSymbolicLink(link.toPath()))
        assertEquals("libreadline.so.8.2", Files.readSymbolicLink(link.toPath()).toString())
        assertFile("usr/lib/libreadline.so.8.2", "ELF-readline")
    }

    @Test
    fun `symlink target restored when both are under the prefix`() {
        extract(
            listOf(
                tarEntry("bin/busybox", "BUSYBOX".toByteArray()),
                tarEntry("bin/sh", typeflag = '2', linkName = "bin/busybox"),
            ),
            prefixes = setOf("bin/"),
        )
        assertTrue(Files.isSymbolicLink(tmp.root.resolve("bin/sh").toPath()))
        assertFile("bin/busybox", "BUSYBOX")
    }

    @Test
    fun `executable bit is preserved from the tar mode`() {
        extract(
            listOf(
                tarEntry("bin/exec", "run".toByteArray(), mode = 0b111_101_101), // 0755
                tarEntry("bin/plain", "data".toByteArray(), mode = 0b110_100_100), // 0644
            ),
            prefixes = setOf("bin/"),
        )
        assertTrue("0755 entry must be executable", tmp.root.resolve("bin/exec").canExecute())
        assertFalse("0644 entry must not be executable", tmp.root.resolve("bin/plain").canExecute())
    }

    @Test
    fun `no prefixes extracts everything like before`() {
        extract(
            listOf(
                tarEntry("bin/bash", "#!/bin/bash\n".toByteArray()),
                tarEntry("etc/hosts", "127.0.0.1 localhost".toByteArray()),
            ),
        )
        assertFile("bin/bash", "#!/bin/bash\n")
        assertFile("etc/hosts", "127.0.0.1 localhost")
    }

    @Test
    fun `dot-slash prefixed entries match onlyPrefixes like the real archive`() {
        // alpine-minirootfs.tar stores every entry with a "./" prefix
        // ("./bin/bash", "./sbin/apk", ...). Regression for the targeted
        // restore silently extracting nothing.
        val entries = listOf(
            tarEntry("./bin/bash", "#!/bin/bash\n".toByteArray()),
            tarEntry("./sbin/apk", "APK-BIN".toByteArray()),
            tarEntry("./usr/lib/libreadline.so.8", "READLINE".toByteArray()),
            tarEntry("./etc/ignored", "skip".toByteArray()),
        )
        extract(entries, setOf("bin/bash", "sbin/apk", "usr/lib/libreadline"))
        assertFile("bin/bash", "#!/bin/bash\n")
        assertFile("sbin/apk", "APK-BIN")
        assertFile("usr/lib/libreadline.so.8", "READLINE")
        assertFalse(tmp.root.resolve("etc/ignored").exists())
    }

    @Test
    fun `dot-slash prefixed symlink chain restores sh pointing at busybox`() {
        val entries = listOf(
            tarEntry("./bin/busybox", "BUSYBOX-BIN".toByteArray()),
            tarEntry("./bin/sh", typeflag = '2', linkName = "/bin/busybox"),
        )
        extract(entries, setOf("bin/sh", "bin/busybox"))
        assertFile("bin/busybox", "BUSYBOX-BIN")
        val link = tmp.root.resolve("bin/sh").toPath()
        assertTrue("missing symlink bin/sh", Files.isSymbolicLink(link))
        assertEquals("/bin/busybox", Files.readSymbolicLink(link).toString())
    }
}
