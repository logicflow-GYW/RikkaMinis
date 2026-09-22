package com.rikkaminis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogPrunePolicyTest {

    private fun tempFiles(dir: File, specs: List<Triple<String, Long, Long>>): List<File> =
        specs.map { (name, mtime, len) ->
            val f = File(dir, name).apply { writeText("x".repeat(len.toInt().coerceAtLeast(1))) }
            assertTrue(f.setLastModified(mtime))
            f
        }

    private fun select(files: List<File>, nowMs: Long): List<String> =
        LogPrunePolicy.sizePruneCandidates(files, nowMs, listOf("minis-2026-09-23", "debug-2026-09-23"))
            .map { it.name }

    @Test
    fun `today files are never candidates`() {
        val dir = createTempDir("prune-today")
        val now = 1_800_000_000_000L
        val files = tempFiles(
            dir,
            listOf(
                Triple("minis-2026-09-23.log", now - 100_000, 10),
                Triple("minis-2026-09-23.modelservice.log", now - 100_000, 10),
                Triple("debug-2026-09-23.log", now - 100_000, 10),
                // old + non-protected: the only deletable one
                Triple("minis-2026-09-20.log", now - 5L * 24 * 60 * 60 * 1000, 10),
            ),
        )
        assertEquals(listOf("minis-2026-09-20.log"), select(files, now))
    }

    @Test
    fun `evidence files are never size-pruned`() {
        val dir = createTempDir("prune-evidence")
        val now = 1_800_000_000_000L
        val old = now - 5L * 24 * 60 * 60 * 1000
        val files = tempFiles(
            dir,
            listOf(
                Triple("error-snapshot-2026-09-20-101500-1234.log", old, 10),
                Triple("crash-20260920-101500.log", old, 10),
                Triple("native-crash-20260920-101500.log", old, 10),
                Triple("minis-2026-09-20.log", old, 10),
            ),
        )
        assertEquals(listOf("minis-2026-09-20.log"), select(files, now))
    }

    @Test
    fun `recently modified yesterday file survives a cross-midnight prune - D8 shape`() {
        val dir = createTempDir("prune-d8")
        // 01:06 launch on the 23rd; the worker is still streaming into
        // YESTERDAY's modelservice file (touched 3 minutes ago).
        val now = 1_800_060_000_000L
        val threeMinAgo = now - 3L * 60 * 1000
        val files = tempFiles(
            dir,
            listOf(
                Triple("minis-2026-09-22.modelservice.log", threeMinAgo, 10),
                Triple("debug-2026-09-22.log", threeMinAgo, 10),
                // genuinely stale file: deletable
                Triple("minis-2026-09-21.log", now - 2L * 24 * 60 * 60 * 1000, 10),
            ),
        )
        assertEquals(listOf("minis-2026-09-21.log"), select(files, now))
    }

    @Test
    fun `grace window lapses - file becomes deletable again`() {
        val dir = createTempDir("prune-grace")
        val now = 1_800_000_000_000L
        val files = tempFiles(
            dir,
            listOf(
                // touched 61 minutes ago: outside the grace window
                Triple("minis-2026-09-22.modelservice.log", now - 61L * 60 * 1000, 10),
            ),
        )
        assertEquals(listOf("minis-2026-09-22.modelservice.log"), select(files, now))
    }

    @Test
    fun `oldest first ordering preserves history not recency`() {
        val dir = createTempDir("prune-order")
        val now = 1_800_000_000_000L
        val files = tempFiles(
            dir,
            listOf(
                Triple("minis-2026-09-19.log", now - 4L * 24 * 60 * 60 * 1000, 10),
                Triple("minis-2026-09-21.log", now - 2L * 24 * 60 * 60 * 1000, 10),
                Triple("minis-2026-09-20.log", now - 3L * 24 * 60 * 60 * 1000, 10),
            ),
        )
        assertEquals(
            listOf("minis-2026-09-19.log", "minis-2026-09-20.log", "minis-2026-09-21.log"),
            select(files, now),
        )
    }

    @Test
    fun `empty input yields empty output`() {
        val dir = createTempDir("prune-empty")
        assertEquals(emptyList<String>(), select(tempFiles(dir, emptyList()), 1_800_000_000_000L))
    }
}
