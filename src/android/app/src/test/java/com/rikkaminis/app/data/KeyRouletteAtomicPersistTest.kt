package com.rikkaminis.app.data

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * [W1118-D5 F-05] `KeyRoulette.persistLocked` used `File.writeText`, i.e.
 * truncate-then-write on the live state file. A process death (or a full disk)
 * between the two steps leaves `key_roulette.json` empty or half-written, and
 * the next cold start loses every provider's LRU order. The same file already
 * had an atomic writer for the migration ([rv1014-D2]: temp file + move); the
 * hot path now uses it too.
 *
 * How the two paths are told apart without crashing the JVM on purpose: an
 * in-place `writeText` truncates the EXISTING inode, while the atomic writer
 * installs a complete temp file with `rename(2)` — so the inode under the path
 * changes. Same final bytes, different mechanism, and the mechanism is the
 * whole fix. (A read-only target does not discriminate here: the test suite can
 * run as root, where in-place writes are allowed too.)
 */
class KeyRouletteAtomicPersistTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir") ?: "/tmp", "keyroulette-" + UUID.randomUUID()).apply {
            assertTrue("could not create $this", mkdirs() || isDirectory)
        }

    /** Already hash-shaped, so init()'s plaintext migration leaves it alone. */
    private val foreignSlice = """{"legacy":{"0123456789abcdef0123456789abcdef":1}}"""

    @Test
    fun `a rotation draw replaces the state file instead of truncating it in place`() {
        val dir = tempDir()
        KeyRoulette.init(dir)
        val f = File(dir, "key_roulette.json")
        f.writeText(foreignSlice)
        val before = fileKey(f)
        try {
            KeyRoulette.next("k-alpha, k-beta", "p-atomic")
            val raw = f.readText()
            assertTrue("draw never reached the file: $raw", raw.contains("p-atomic"))
            assertTrue("other providers' state must survive the rewrite: $raw", raw.contains("legacy"))
            assertFalse("a stray temp file was left behind", File(dir, "key_roulette.json.tmp").exists())
            // The discriminator: `writeText` truncates the EXISTING inode, so a
            // crash between truncate and write leaves an empty/short file. The
            // atomic path writes a complete temp file and installs it with
            // rename(2), which swaps the inode. Same bytes, different mechanism
            // — and the mechanism is the whole fix.
            assertNotEquals(
                "the state file was rewritten in place (same inode): a death mid-write loses it",
                before,
                fileKey(f),
            )
        } finally {
            f.setWritable(true, false)
        }
    }

    private fun fileKey(f: File): Any? =
        Files.readAttributes(f.toPath(), BasicFileAttributes::class.java).fileKey()
            ?: throw AssertionError("this filesystem reports no file key for $f")

    @Test
    fun `successive draws keep the file parseable and leave no temp file`() {
        val dir = tempDir()
        KeyRoulette.init(dir)
        KeyRoulette.next("k1, k2", "p-one")
        KeyRoulette.next("k3, k4", "p-two")
        KeyRoulette.next("k1, k2", "p-one")

        val raw = File(dir, "key_roulette.json").readText()
        val root = JSONObject(raw)
        assertTrue("provider one missing: $raw", root.has("p-one"))
        assertTrue("provider two missing: $raw", root.has("p-two"))
        // Both keys of a provider are tracked (hashed, never plaintext).
        assertTrue(root.getJSONObject("p-one").length() == 2)
        assertFalse(File(dir, "key_roulette.json.tmp").exists())
        assertFalse("plaintext key leaked to disk", raw.contains("k1"))
    }
}
