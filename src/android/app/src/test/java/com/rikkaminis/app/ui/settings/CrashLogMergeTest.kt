package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1028 E5 / B06 F-09] Crash-list merge ordering.
 *
 * Pre-fix: `(javaCrashes + nativeCrashes).sortedByDescending { it.name }.take(100)`.
 * The two families do not share a name prefix (`crash-…` vs `native-crash-…`)
 * and do not even share a stamp format (`YYYY-MM-DD_HH-MM-SS` local vs
 * `YYYY-MM-DDTHH-MM-SSZ` UTC), so a name sort compared `n` against `c` before
 * it ever compared a timestamp: every native report outranked every Java one
 * and the 100-row cap then dropped the newest Java crashes.
 *
 * Rows are a local stand-in for `AppLogger.LogFileMeta` (that type lives in an
 * Android-bound file). Expectations are literals.
 */
class CrashLogMergeTest {

    private data class Row(val name: String, val mtime: Long)

    private fun merge(rows: List<Row>, limit: Int): List<Row> =
        newestFirstCapped(rows, limit, nameOf = { it.name }, modifiedOf = { it.mtime })

    /**
     * The pre-fix algorithm, transcribed verbatim, so the fixtures below are
     * proven to actually exhibit the defect instead of merely describing it.
     */
    private fun preFixMerge(rows: List<Row>, limit: Int): List<Row> =
        rows.sortedByDescending { it.name }.take(limit)

    @Test
    fun newerJavaCrashOutranksOlderNativeCrash() {
        val javaNew = Row("crash-2026-10-28_10-00-00.log", 1_000_000_000_000L)
        val nativeOld = Row("native-crash-2026-10-25T08-00-00Z.log", 1_000_000_000_000L - 3 * 86_400_000L)
        val merged = merge(listOf(javaNew, nativeOld), 100)
        assertEquals(listOf(javaNew, nativeOld), merged)
        // Control: the pre-fix ordering put the native report first anyway.
        assertEquals(listOf(nativeOld, javaNew), preFixMerge(listOf(javaNew, nativeOld), 100))
    }

    @Test
    fun cap_keepsGlobalNewest_notWholeNativeFamily() {
        // 100 native reports, all older than three Java ones written since.
        val native = (1..100).map {
            Row("native-crash-2026-10-2${it % 9}T0${it % 9}-00-0${it % 9}Z.log", it.toLong())
        }
        val java = listOf(
            Row("crash-2026-10-28_10-00-01.log", 500_000L),
            Row("crash-2026-10-28_10-00-00.log", 400_000L),
            Row("crash-2026-10-28_09-59-59.log", 300_000L),
        )
        val merged = merge(native + java, 100)

        assertEquals(100, merged.size)
        // The three newest entries are the Java crashes, newest first.
        assertEquals(java, merged.take(3))
        // Control: the pre-fix sort kept 100 native rows and starved all three.
        val pre = preFixMerge(native + java, 100)
        assertTrue("pre-fix fixture must exhibit starvation", pre.none { it in java })
    }

    @Test
    fun equalTimestamps_breakTiesByFileNameDeterministically() {
        val a = Row("crash-2026-10-28_10-00-00.log", 7L)
        val b = Row("native-crash-2026-10-28T10-00-00Z.log", 7L)
        // Same mtime → name descending, so the 'n' family still sorts first…
        assertEquals(listOf(b, a), merge(listOf(a, b), 10))
        // …but only as a tie-break: the same pair with a newer Java mtime flips.
        val newer = a.copy(mtime = 8L)
        assertEquals(listOf(newer, b), merge(listOf(newer, b), 10))
    }

    @Test
    fun limitBoundaries() {
        val rows = (1..5).map { Row("crash-2026-10-28_10-00-0${it}.log", it.toLong()) }
        assertEquals(5, merge(rows, 10).size)
        assertEquals(listOf(rows[4]), merge(rows, 1))
        assertEquals(emptyList<Row>(), merge(rows, 0))
        assertEquals(emptyList<Row>(), merge(rows, -3))
        assertEquals(emptyList<Row>(), merge(emptyList(), 100))
    }
}
