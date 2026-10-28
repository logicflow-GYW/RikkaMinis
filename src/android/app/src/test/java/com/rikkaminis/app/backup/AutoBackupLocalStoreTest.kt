package com.rikkaminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * [FIX-P1B / FINDING-14] JVM coverage for the local auto-backup file lifecycle:
 * the one "is this a finished backup" rule, the atomic (temp + rename) streaming
 * write, and the reclaim of temp files a killed run leaves behind.
 *
 * The harm this pins (world W6, disk full / kill mid-export): the nightly run
 * used to stream the export straight into `rikkaminis-auto-<stamp>.json`, so a
 * partial write left a TRUNCATED document under a fully legal name — the newest
 * entry in the settings list, one of the three rotation slots, and a
 * `Malformed JSON` on restore day.
 *
 * Expectations are hardcoded literals, NOT derived from `AutoBackupManager` /
 * `AutoBackupLocalStore` constants: a mutation of the production constant must
 * not silently rewrite the expectation (evidence-discipline).
 *
 * No Android dependency on purpose — this file must run in a plain JVM harness
 * as well as under Gradle.
 */
class AutoBackupLocalStoreTest {

    /** Literal mirror of AutoBackupManager.LOCAL_FILE_PREFIX (pinned above). */
    private val prefix = "rikkaminis-auto-"

    private fun newDir(): File {
        val f = File.createTempFile("autobackup-local", "dir")
        f.delete()
        f.mkdirs()
        f.deleteOnExit()
        return f
    }

    private fun names(dir: File): List<String> = (dir.list() ?: emptyArray()).sorted()

    // ── 命名规则：写入端与读取端必须认同一个形状 ─────────────────────────

    @Test
    fun `the name the writer builds is the name the lister accepts`() {
        val name = AutoBackupLocalStore.fileName(prefix, "20261028-120000")
        assertEquals("rikkaminis-auto-20261028-120000.json", name)
        assertTrue(AutoBackupLocalStore.isBackupName(name, prefix))
        assertFalse(AutoBackupLocalStore.isTempName(name, prefix))
    }

    @Test
    fun `an in-flight temp file is invisible to the backup rule`() {
        val tmp = "rikkaminis-auto-20261028-120000.json.tmp"
        assertFalse(
            "a half-written document must never be listed as a backup",
            AutoBackupLocalStore.isBackupName(tmp, prefix),
        )
        assertTrue(AutoBackupLocalStore.isTempName(tmp, prefix))
    }

    @Test
    fun `other files in the directory are not auto backups`() {
        assertFalse(AutoBackupLocalStore.isBackupName("rikkaminis-backup-20261028-120000.json", prefix))
        assertFalse(AutoBackupLocalStore.isBackupName("rikkaminis-auto-20261028-120000.json.bak", prefix))
        assertFalse(AutoBackupLocalStore.isBackupName("rikkaminis-auto-notes.txt", prefix))
    }

    // ── 原子写：中途失败不许留下合法名字的截断件 ─────────────────────────

    @Test
    fun `a complete write lands under the final name and leaves no temp behind`() {
        val dir = newDir()
        val target = File(dir, "rikkaminis-auto-20261028-120000.json")
        val body = (1..50).joinToString("\n") { """{"n":$it}""" }

        val returned = AutoBackupLocalStore.writeAtomically(target) { w ->
            w.write(body)
            7
        }

        assertEquals(7, returned)
        assertEquals(body, target.readText())
        assertEquals(listOf(target.name), names(dir))
    }

    @Test
    fun `a body that throws leaves no backup file at all`() {
        val dir = newDir()
        val target = File(dir, "rikkaminis-auto-20261028-120000.json")

        try {
            AutoBackupLocalStore.writeAtomically(target) { w ->
                // exactly the shape an ENOSPC produces: a syntactically open JSON
                // document, then nothing.
                w.write("""{"format":"openminis.config.backup","providers":[""")
                throw IOException("ENOSPC (simulated)")
            }
            fail("the write must fail, not silently land a half document")
        } catch (e: IOException) {
            assertTrue("the caller must see the real cause", e.message!!.contains("ENOSPC"))
        }

        assertFalse(
            "a truncated document must never appear under the final name",
            target.exists(),
        )
        assertEquals(emptyList<String>(), names(dir))
    }

    @Test
    fun `a body that throws leaves the previous good copy untouched`() {
        val dir = newDir()
        val target = File(dir, "rikkaminis-auto-20261028-120000.json")
        val good = """{"format":"openminis.config.backup","providers":[]}"""
        target.writeText(good)

        runCatching {
            AutoBackupLocalStore.writeAtomically(target) { w ->
                w.write("""{"broken":""")
                throw IOException("disk full")
            }
        }

        assertEquals(good, target.readText())
        assertEquals(listOf(target.name), names(dir))
    }

    @Test
    fun `a second write in the same second replaces the first one completely`() {
        val dir = newDir()
        val target = File(dir, "rikkaminis-auto-20261028-120000.json")
        AutoBackupLocalStore.writeAtomically(target) { w -> w.write("aaaaaaaaaaaaaaaaaaaaaaaa") }
        AutoBackupLocalStore.writeAtomically(target) { w -> w.write("bb") }

        assertEquals("bb", target.readText())
        assertEquals(listOf(target.name), names(dir))
    }

    // ── 轮转不受污染：失败的一次写不能占掉一个滚动槽位 ────────────────────

    @Test
    fun `a failed write cannot take one of the kept rotation slots`() {
        val dir = newDir()
        val kept = (1..3).map { i ->
            File(dir, "rikkaminis-auto-2026102$i-120000.json").apply {
                writeText("""{"i":$i}""")
                setLastModified(1_700_000_000_000L + i * 1000L)
            }
        }

        runCatching {
            AutoBackupLocalStore.writeAtomically(File(dir, "rikkaminis-auto-20261028-120000.json")) { w ->
                w.write("""{"half":""")
                throw IOException("killed")
            }
        }

        val visible = (dir.listFiles() ?: emptyArray())
            .filter { AutoBackupLocalStore.isBackupName(it.name, prefix) }
        assertEquals(
            "rotation sees exactly the three good copies — the failure added nothing",
            3,
            visible.size,
        )
        assertEquals(kept.map { it.name }.sorted(), visible.map { it.name }.sorted())
    }

    // ── 被杀留下的临时文件必须能被回收 ───────────────────────────────────

    @Test
    fun `deleteStaleTemps reclaims only this app's temp files`() {
        val dir = newDir()
        val good = File(dir, "rikkaminis-auto-20261028-120000.json").apply { writeText("{}") }
        val stale = File(dir, "rikkaminis-auto-20261027-120000.json.tmp").apply { writeText("{") }
        val foreign = File(dir, "rikkaminis-backup-auto-20261027-120000.json.tmp").apply { writeText("{") }

        assertEquals(1, AutoBackupLocalStore.deleteStaleTemps(dir, prefix))

        assertFalse(stale.exists())
        assertTrue(good.exists())
        assertTrue(foreign.exists())
    }

    @Test
    fun `deleteStaleTemps on a missing directory is not an error`() {
        assertEquals(0, AutoBackupLocalStore.deleteStaleTemps(File(newDir(), "absent"), prefix))
    }
}
