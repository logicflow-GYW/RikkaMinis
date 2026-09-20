package com.rikkaminis.app.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [F-169] Contract tests for the error-snapshot scene: the file an ERROR
 * writes must CONTAIN the ERROR line that triggered it.
 *
 * Production measurement (2026-09-20, 41 snapshots): 17 shipped without their
 * trigger line — `error()` enqueues the line and the drain thread records it
 * into the ring later, so the synchronous dump raced the drain.
 *
 * These tests drive the REAL [AppLogger] with the write queue absent (no drain
 * thread, no race): `log()` then only formats the line and returns it, which is
 * exactly the situation the dump has to cope with. Before the fix the dump read
 * the ring alone and the trigger line was missing; after it, the trigger is
 * composed in.
 *
 * Pure JVM: android.util.Log / android.content.Context are faked here, no
 * Robolectric. Runnable both under Gradle (testReleaseUnitTest) and in the
 * sandbox harness (kotlinc + JUnitCore + stubs).
 */
class ErrorSnapshotOrderingTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("f169-snapshots").toFile()
        setPrivate("logDir", dir)
        setPrivate("enabled", true)
        setPrivate("ring", LogRingBuffer())
        // writeQueue deliberately left null: no drain thread can record the
        // trigger line behind our back, so the assertion is deterministic
        // rather than a race against the drain.
        setPrivate("writeQueue", null)
    }

    @After
    fun tearDown() {
        setPrivate("ring", null)
        setPrivate("writeQueue", null)
        setPrivate("logDir", null)
        setPrivate("enabled", false)
        dir.deleteRecursively()
    }

    @Test
    fun `error snapshot contains its own trigger line`() {
        AppLogger.error("TestCat", "boom-1")

        val snap = onlySnapshot()
        val content = snap.readText()
        assertTrue(
            "snapshot must contain the triggering ERROR line, was:\n$content",
            content.contains("[ERROR] [TestCat] boom-1"),
        )
    }

    @Test
    fun `trigger line is the newest entry of the snapshot`() {
        // A delivered line precedes the error; the trigger must come after it.
        AppLogger.info("TestCat", "before")
        getRing().append("[00:00:00.000] [INFO] [TestCat] before")
        AppLogger.error("TestCat", "boom-2")

        val lines = onlySnapshot().readLines().filter { it.isNotBlank() }
        assertTrue(lines.last().contains("[ERROR] [TestCat] boom-2"))
        assertTrue(lines.any { it.contains("before") })
    }

    @Test
    fun `trigger line is not duplicated when the drain already delivered it`() {
        AppLogger.info("TestCat", "ctx")
        // Simulate the drain having won the race: the ring already holds the
        // exact formatted trigger line the caller is about to hand over.
        val trigger = "[00:00:00.000] [ERROR] [TestCat] boom-3"
        getRing().append(trigger)
        dumpWith(trigger)

        val lines = onlySnapshot().readLines()
        assertEquals(1, lines.count { it == trigger })
    }

    @Test
    fun `ring is left untouched by the dump`() {
        // The drain thread stays the ring's single writer: composing the
        // snapshot must not append into it, or the drain would re-deliver the
        // same line and every later snapshot would show a duplicate.
        AppLogger.error("TestCat", "boom-4")
        assertTrue(onlySnapshot().readText().contains("boom-4"))
        assertFalse(
            "dump must not record into the ring",
            getRing().content().any { it.contains("boom-4") },
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun onlySnapshot(): File {
        val files = dir.listFiles { f -> f.name.startsWith("error-snapshot-") } ?: emptyArray()
        assertEquals("exactly one snapshot expected, got ${files.map { it.name }}", 1, files.size)
        return files[0]
    }

    private fun getRing(): LogRingBuffer =
        field("ring").get(AppLogger) as LogRingBuffer

    /** Calls the private dump with an explicit trigger line (as `error()` does). */
    private fun dumpWith(triggerLine: String) {
        val m = AppLogger::class.java.getDeclaredMethod("dumpErrorSnapshot", String::class.java)
        m.isAccessible = true
        m.invoke(AppLogger, triggerLine)
    }

    private fun field(name: String) =
        AppLogger::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun setPrivate(name: String, value: Any?) {
        field(name).set(AppLogger, value)
    }
}
