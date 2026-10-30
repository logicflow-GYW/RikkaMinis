package com.rikkaminis.app.ui.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.util.TimeZone

/**
 * [FIX-FW-FILE-DATE] Pins [fileDateBucket] and the one label it drives that
 * cannot be reached by accident: the FUTURE arm.
 *
 * Every expectation below is a LITERAL enum value (never derived from the
 * implementation) — a test that recomputes what the code computes goes green on
 * any mutation that keeps both sides in step.
 */
class FileDateBucketTest {

    // Zone is injected, never mutated globally: setting TimeZone.setDefault in a
    // test leaks into every other test in the same JVM.
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** 2026-05-14T12:00:00Z — midday, so ±23 h stays inside its own calendar day. */
    private val nowMs = 1_778_760_000_000L

    private fun bucket(modifiedMs: Long, now: Long = nowMs): FileDateBucket =
        fileDateBucket(modifiedMs, now, utc)

    // ── the boundary table ────────────────────────────────────────────

    @Test
    fun `future timestamps are their own bucket`() {
        // The defect: both of these used to burn through to WITHIN_WEEK,
        // because (-1ms / 1 day) and (-23h / 1 day) both truncate toward zero.
        assertEquals(FileDateBucket.FUTURE, bucket(nowMs + 1))
        assertEquals(FileDateBucket.FUTURE, bucket(nowMs + HOUR))
        assertEquals(FileDateBucket.FUTURE, bucket(nowMs + 23 * HOUR))
        assertEquals(FileDateBucket.FUTURE, bucket(nowMs + 400 * DAY))
    }

    @Test
    fun `the future arm wins even when the future instant lands on another day`() {
        // 23:30 → 01:30: the old code saw a different DAY_OF_YEAR (no same-day
        // match), no yesterday match, and daysAgo == 0 → weekday name.
        val lateNight = 1_778_801_400_000L // 2026-05-14T23:30:00Z
        assertEquals(FileDateBucket.FUTURE, bucket(lateNight + 2 * HOUR, lateNight))
    }

    @Test
    fun `same day`() {
        assertEquals(FileDateBucket.SAME_DAY, bucket(nowMs - 1))
        assertEquals(FileDateBucket.SAME_DAY, bucket(nowMs - 12 * HOUR))
        // 12:00 − 13 h = 23:00 on the 13th — yesterday.
        assertEquals(FileDateBucket.YESTERDAY, bucket(nowMs - 13 * HOUR))
        assertEquals(FileDateBucket.SAME_DAY, bucket(nowMs))
    }

    @Test
    fun `yesterday`() {
        assertEquals(FileDateBucket.YESTERDAY, bucket(nowMs - 23 * HOUR))
        assertEquals(FileDateBucket.YESTERDAY, bucket(nowMs - 24 * HOUR))
        assertEquals(FileDateBucket.YESTERDAY, bucket(nowMs - 25 * HOUR))
    }

    @Test
    fun `within the last week`() {
        assertEquals(FileDateBucket.WITHIN_WEEK, bucket(nowMs - 2 * DAY))
        assertEquals(FileDateBucket.WITHIN_WEEK, bucket(nowMs - 6 * DAY))
        // 6 d 23 h: still truncates to 6 whole days.
        assertEquals(FileDateBucket.WITHIN_WEEK, bucket(nowMs - (6 * DAY + 23 * HOUR)))
        // …and the truncation boundary on the other side.
        assertEquals(FileDateBucket.OLDER, bucket(nowMs - 7 * DAY))
        assertEquals(FileDateBucket.OLDER, bucket(nowMs - 8 * DAY))
    }

    @Test
    fun `no usable mtime`() {
        assertEquals(FileDateBucket.EMPTY, bucket(0L))
        assertEquals(FileDateBucket.EMPTY, bucket(-1L))
        assertEquals(FileDateBucket.EMPTY, bucket(-DAY))
    }

    @Test
    fun `month and year boundaries still read as yesterday`() {
        // 2026-01-01T00:30Z, mtime 2025-12-31T23:30Z: DAY_OF_YEAR wraps (1 vs 365).
        val newYear = 1_767_227_400_000L
        assertEquals(FileDateBucket.YESTERDAY, bucket(newYear - HOUR, newYear))
    }

    @Test
    fun `no future offset falls into a past bucket`() {
        // The regression shape: truncation toward zero turned a sub-24 h future
        // offset into "0 days ago". Whatever the window bound is, a timestamp
        // after `now` must answer FUTURE — not SAME_DAY just because it lands on
        // the same calendar day, and above all not WITHIN_WEEK.
        for (offset in listOf(1L, 1_000L, HOUR, 23 * HOUR, DAY, 30 * DAY)) {
            assertEquals("now+$offset ms", FileDateBucket.FUTURE, bucket(nowMs + offset))
        }
    }

    // ── the label the user actually sees ──────────────────────────────

    @Test
    fun `a future mtime renders Just now and never a weekday name`() {
        // Timezone-independent: "future" is future in every zone, so this
        // assertion needs no default-zone pinning.
        val now = System.currentTimeMillis()
        for (offset in listOf(1L, 1_000L, HOUR, 23 * HOUR, 9 * DAY)) {
            val label = fileItem(now + offset).formattedDate
            assertEquals("now+$offset ms", "Just now", label)
            for (weekday in WEEKDAYS) {
                assertFalse("now+$offset ms rendered \"$weekday\": $label", label.contains(weekday))
            }
        }
    }

    @Test
    fun `a future mtime is not labelled like today`() {
        // Acceptance criterion: distinguishable from the same-day label. The
        // same-day form is "h:mm a"; "Just now" must not collide with it.
        val now = System.currentTimeMillis()
        val future = fileItem(now + HOUR).formattedDate
        assertFalse("future label looks like a clock time: $future", Regex("\\d").containsMatchIn(future))
        assertEquals("", fileItem(0L).formattedDate)
    }

    // ── drift guard: the getter must stay a formatting shell ───────────

    @Test
    fun `formattedDate delegates its day arithmetic to the bucket function`() {
        val src = readRepoFile(VIEW_MODEL)
        val body = declarationSlice(src, "val formattedDate: String")
        assertTrue(
            "formattedDate no longer consults fileDateBucket:\n$body",
            body.contains("fileDateBucket(modifiedMs"),
        )
        // The old truncating arithmetic must not come back, at either site.
        assertFalse(
            "truncating daysAgo arithmetic is back in formattedDate:\n$body",
            body.contains("DAY_MS") || body.contains("24L * 60"),
        )
        assertFalse(
            "formattedDate grew its own weekday fallthrough:\n$body",
            body.contains("in 0..6"),
        )
    }

    private fun fileItem(modifiedMs: Long): FileItem = FileItem(
        file = File("/var/minis/does-not-exist.txt"),
        name = "does-not-exist.txt",
        isDirectory = false,
        isSymlink = false,
        size = 0L,
        modifiedMs = modifiedMs,
    )

    private fun readRepoFile(relative: String): String {
        // Gradle sets user.dir to the app module dir; a sandbox JVM run may start
        // anywhere under the repo — walk up instead of assuming a depth.
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    /**
     * Source lines of the declaration at [marker] — from that line up to the
     * next unsignaled member boundary (a KDoc / annotation / companion).
     */
    private fun declarationSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val t = lines[i]
            t.startsWith("    /**") || t.startsWith("    companion object")
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private companion object {
        private const val HOUR = 60L * 60L * 1000L
        private const val DAY = 24L * HOUR
        private const val VIEW_MODEL =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/sandbox/FileBrowserViewModel.kt"
        private val WEEKDAYS = listOf(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        )
    }
}
