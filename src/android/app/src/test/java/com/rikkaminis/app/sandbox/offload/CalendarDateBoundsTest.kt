package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1016c ISSUE-09B] `android-calendar list` date-window bounds.
 *
 * Defect shape being pinned (fix-f report §B): `resolveDateRange` fed
 * `parseDate(str)` straight into the window, so a bare `--end 2026-10-16`
 * parsed to 00:00:00.000 local and `--start X --end X` was a ZERO-WIDTH window
 * (measured: width=0, see probe/pre-P2-measured.txt) — a day the caller named
 * explicitly answered empty. `--today` added a flat 24h constant, so on a
 * 23/25-hour DST day the window was off by exactly 1h (measured both ways).
 *
 * Every literal below is cross-checked against independent python-zoneinfo
 * ground truth (probe/dst-literals.txt), never derived from the code under test.
 * The harness note on each group names the mutation that must flip it.
 *
 * 2026 US DST: spring forward Mar 8 (23h day), fall back Nov 1 (25h day).
 */
class CalendarDateBoundsTest {

    private val shanghai: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val newYork: TimeZone = TimeZone.getTimeZone("America/New_York")

    // ─── parser replica (pinned by a source assertion below) ────────────────
    //
    // VERBATIM copy of CalendarOffloadHandler.parseDateInZone (same format list,
    // same relative-offset branch, same null-on-garbage contract). Needed because
    // parseDateInZone is a member of an Android-bound class (its Context param
    // has a Kotlin null check, so it cannot be instantiated on a plain JVM).
    // testProductionParserShapeBelow pins the shape this replica mirrors.
    private fun parseLikeProduction(s: String, nowMs: Long, zone: TimeZone): Long? {
        val rel = Regex("""^([+-]?)(\d+)([dhm])$""").matchEntire(s.trim())
        if (rel != null) {
            val sign = if (rel.groupValues[1] == "-") -1L else 1L
            val n = rel.groupValues[2].toLong()
            val unitMs = when (rel.groupValues[3]) {
                "d" -> 24 * 60 * 60 * 1000L
                "h" -> 60 * 60 * 1000L
                "m" -> 60 * 1000L
                else -> return null
            }
            return nowMs + sign * n * unitMs
        }
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd",
        )
        for (f in formats) {
            try {
                val sdf = SimpleDateFormat(f, Locale.US).apply {
                    timeZone = if (f.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else zone
                }
                return sdf.parse(s)?.time
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun range(
        nowMs: Long,
        zone: TimeZone,
        today: Boolean = false,
        days: Int? = null,
        start: String? = null,
        end: String? = null,
        daysText: String? = null,
    ): Pair<Long, Long>? = CalendarOffloadHandler.resolveRangePure(
        nowMs = nowMs,
        zone = zone,
        todayFlag = today,
        daysRaw = days,
        startRaw = start,
        endRaw = end,
        parse = { s, now, z -> parseLikeProduction(s, now, z) },
        daysText = daysText,
    )

    // ─── B-1: bare-date --end is inclusive end-of-day (zero-width repair) ──
    // MUT-B1 (alignBareDateEnd passthrough) must flip this red.

    @Test
    fun `bare date end covers the whole day - start X end X is not a zero width window`() {
        val (s, e) = range(1_792_200_000_000L, shanghai, start = "2026-10-16", end = "2026-10-16")!!
        assertEquals(1_792_080_000_000L, s) // 2026-10-16 00:00 +08
        assertEquals(1_792_166_399_999L, e) // 2026-10-16 23:59:59.999 +08
        assertTrue("window must not be zero-width", e > s)
    }

    @Test
    fun `bare date end covers the whole day on a 25 hour DST day`() {
        val (s, e) = range(1_793_592_000_000L, newYork, start = "2026-11-01", end = "2026-11-01")!!
        assertEquals(1_793_505_600_000L, s) // 2026-11-01 00:00 EDT
        assertEquals(1_793_595_599_999L, e) // 2026-11-01 23:59:59.999 EST (25h after s, +1h vs 24h so)
    }

    @Test
    fun `bare date end covers the whole day on a 23 hour DST day`() {
        val (s, e) = range(1_772_988_800_000L, newYork, start = "2026-03-08", end = "2026-03-08")!!
        assertEquals(1_772_946_000_000L, s) // 2026-03-08 00:00 EST
        assertEquals(1_773_028_799_999L, e) // 2026-03-08 23:59:59.999 EDT (23h after s)
    }

    // ─── B-2: explicit instants keep their exact value ──────────────────────

    @Test
    fun `datetime end keeps its explicit instant - no end-of-day snapping`() {
        val (s, e) = range(
            1_792_200_000_000L, shanghai,
            start = "2026-10-16", end = "2026-10-16T10:30:00",
        )!!
        assertEquals(1_792_080_000_000L, s)
        assertEquals(1_792_117_800_000L, e) // 2026-10-16 10:30 +08, NOT 23:59:59.999
    }

    @Test
    fun `relative shorthand end keeps its instant`() {
        // now is 2026-10-17 09:20 +08; "-0d" is now.
        val now = 1_792_200_000_000L
        val (s, e) = range(now, shanghai, end = "-0d")!!
        assertEquals(now - 24 * 60 * 60 * 1000L, s) // default start: last 24h (unchanged)
        assertEquals(now, e) // NOT end-of-day of the containing day
    }

    // ─── B-3: --today uses calendar-day arithmetic, not a 24h constant ──────

    @Test
    fun `today spans the full 25 hour fall back day`() {
        // Noon on 2026-11-01 in New York.
        val (s, e) = range(1_793_552_400_000L, newYork, today = true)!!
        assertEquals(1_793_505_600_000L, s) // 00:00 EDT
        assertEquals(1_793_595_600_000L, e) // next-day 00:00 EST = 25h later
        assertEquals(25 * 60 * 60 * 1000L, e - s)
    }

    @Test
    fun `today spans the full 23 hour spring forward day`() {
        // Noon on 2026-03-08 in New York.
        val (s, e) = range(1_772_985_600_000L, newYork, today = true)!!
        assertEquals(1_772_946_000_000L, s) // 00:00 EST
        assertEquals(1_773_028_800_000L, e) // next-day 00:00 EDT = 23h later
        assertEquals(23 * 60 * 60 * 1000L, e - s)
    }

    @Test
    fun `today on a plain day is the ordinary 24 hour day`() {
        val (s, e) = range(1_792_200_000_000L, shanghai, today = true)!!
        assertEquals(1_792_166_400_000L, s) // 2026-10-17 00:00 +08
        assertEquals(1_792_252_800_000L, e) // 2026-10-18 00:00 +08
    }

    // ─── B-4: --days N uses add(DAY_OF_YEAR, -N) from local midnight ────────

    @Test
    fun `days starts at the calendar day boundary across the fall back night`() {
        // 2026-11-02 00:30 EST, one day back → 2026-11-01 00:00 EDT.
        val (s, e) = range(1_793_597_400_000L, newYork, days = 1)!!
        assertEquals(1_793_505_600_000L, s) // local midnight of the fall-back day
        assertEquals(1_793_597_400_000L, e)
    }

    @Test
    fun `days degrades to seven for non positive input`() {
        val now = 1_792_166_400_000L // 2026-10-17 00:00 +08
        val (s, e) = range(now, shanghai, days = 0)!!
        assertEquals(1_791_561_600_000L, s) // 2026-10-10 00:00 +08
        assertEquals(now, e)
    }

    // ─── B-8: an unreadable --days is refused ([dv14-A3] ISSUE-05) ──────────
    // MUT-B8 (drop the daysText guard) must flip these red.

    @Test
    fun `unreadable days is refused instead of silently narrowing to 24 hours`() {
        // The defect: getInt("days") answers null for "7d" exactly as it does
        // for an absent flag, so `list --days 7d` answered the last 24 hours —
        // six of the seven days the caller named, dropped in silence.
        val now = 1_792_166_400_000L
        assertNull(range(now, shanghai, daysText = "7d"))
        assertNull(range(now, shanghai, daysText = "last-week"))
        assertNull(range(now, shanghai, days = null, start = "2026-10-16", daysText = "7d"))
    }

    @Test
    fun `an absent or readable days keeps its window`() {
        val now = 1_792_166_400_000L // 2026-10-17 00:00 +08
        // Absent: the flag never carried a value — last 24 hours, unchanged.
        val (s0, e0) = range(now, shanghai, daysText = null)!!
        assertEquals(now - 24 * 60 * 60 * 1000L, s0)
        assertEquals(now, e0)
        // Blank is the same "no value" case, not an unreadable one.
        assertEquals(now - 24 * 60 * 60 * 1000L, range(now, shanghai, daysText = "  ")!!.first)
        // Readable: the same 7-day window the `days = 0` arm pins above.
        val (s7, e7) = range(now, shanghai, days = 7, daysText = "7")!!
        assertEquals(1_791_561_600_000L, s7) // 2026-10-10 00:00 +08
        assertEquals(now, e7)
        // A non-positive integer still degrades to 7 (daysText agrees it is one).
        assertEquals(1_791_561_600_000L, range(now, shanghai, days = 0, daysText = "0")!!.first)
    }

    // ─── B-9: the write paths need READ as well as WRITE ([dv14-A3] I-06) ───

    @Test
    fun `a write predicate needs read as well as write`() {
        // Every calendar write reads the provider first (listWritableCalendars /
        // findEventRow); with WRITE granted alone those reads throw
        // SecurityException into an empty answer, so the write reported
        // calendar_no_account / not_found for a permission problem.
        assertTrue(CalendarOffloadHandler.calendarSatisfied(read = true, write = true, needsWrite = true))
        assertFalse(CalendarOffloadHandler.calendarSatisfied(read = false, write = true, needsWrite = true))
        assertFalse(CalendarOffloadHandler.calendarSatisfied(read = true, write = false, needsWrite = true))
        // The read paths keep the minimum-permission contract.
        assertTrue(CalendarOffloadHandler.calendarSatisfied(read = true, write = false, needsWrite = false))
        assertFalse(CalendarOffloadHandler.calendarSatisfied(read = false, write = true, needsWrite = false))
    }

    @Test
    fun `every writing subcommand decides on both permissions and requests both`() {
        val src = readHandlerSource()
        for (marker in listOf("private fun doCreate(", "private fun doUpdate(", "private fun doDelete(")) {
            val body = memberSlice(src, marker)
            assertTrue(
                "$marker must gate on READ and WRITE",
                body.contains("satisfied = { calendarSatisfied(hasRead(), hasWrite(), needsWrite = true) }"),
            )
            assertTrue("$marker must request READ_CALENDAR too", body.contains("Manifest.permission.READ_CALENDAR"))
        }
    }

    @Test
    fun `the read only subcommands never ask for write`() {
        val src = readHandlerSource()
        for (marker in listOf("private fun doList(", "private fun doFreebusy(", "private fun doListCalendars(")) {
            val body = memberSlice(src, marker)
            assertFalse("$marker must not request WRITE_CALENDAR", body.contains("WRITE_CALENDAR"))
            assertFalse("$marker must not gate on write", body.contains("needsWrite = true"))
        }
    }

    @Test
    fun `the handler hands the pure core the raw days string`() {
        val body = memberSlice(readHandlerSource(), "private fun resolveDateRange(")
        assertTrue("resolveDateRange must forward the raw --days string", body.contains("daysText = args.get(\"days\")"))
        assertTrue("resolveDateRange must keep the integer read", body.contains("daysRaw = args.getInt(\"days\")"))
    }

    // ─── B-5: unchanged contracts (swap, defaults, error semantics) ─────────

    @Test
    fun `start after end is still swapped`() {
        val (s, e) = range(1_792_200_000_000L, shanghai, start = "2026-10-17", end = "2026-10-16")!!
        assertEquals(1_792_166_399_999L, s) // aligned end (16th 23:59:59.999) sorts first
        assertEquals(1_792_166_400_000L, e)
    }

    @Test
    fun `missing sides still default to the last 24 hours either side`() {
        val now = 1_792_200_000_000L
        val (s, e) = range(now, shanghai)!!
        assertEquals(now - 24 * 60 * 60 * 1000L, s)
        assertEquals(now, e)
    }

    @Test
    fun `unparseable bounds still return null`() {
        assertNull(range(1_792_200_000_000L, shanghai, end = "not-a-date"))
        assertNull(range(1_792_200_000_000L, shanghai, start = "not-a-date"))
    }

    // ─── B-6: boundary helpers directly (DST coverage arithmetic) ───────────

    @Test
    fun `endOfLocalDay covers a 25 hour day in full`() {
        val e = CalendarOffloadHandler.endOfLocalDay(1_793_505_600_000L, newYork)
        assertEquals(1_793_595_599_999L, e)
        assertTrue("a 24h-1ms constant would stop 1h short", e > 1_793_505_600_000L + 86_400_000L - 1)
    }

    @Test
    fun `endOfLocalDay covers a 23 hour day in full`() {
        assertEquals(1_773_028_799_999L, CalendarOffloadHandler.endOfLocalDay(1_772_946_000_000L, newYork))
    }

    @Test
    fun `addLocalDays walks calendar days not fixed offsets`() {
        // One calendar day before 2026-11-02 00:00 EST is 2026-11-01 00:00 EDT (25h earlier,
        // not 24h: the fixed-offset answer would be 01:00 EST and land in the wrong hour).
        assertEquals(1_793_505_600_000L, CalendarOffloadHandler.addLocalDays(1_793_595_600_000L, -1, newYork))
        assertFalse(
            "fixed 24h offsets cannot express the DST day length",
            CalendarOffloadHandler.addLocalDays(1_793_595_600_000L, -1, newYork) == 1_793_509_200_000L,
        )
    }

    @Test
    fun `alignBareDateEnd only touches bare dates`() {
        val parsed = 1_793_505_600_000L // 2026-11-01 00:00 EDT
        assertEquals(1_793_595_599_999L, CalendarOffloadHandler.alignBareDateEnd(parsed, "2026-11-01", newYork))
        assertEquals(1_793_595_599_999L, CalendarOffloadHandler.alignBareDateEnd(parsed, " 2026-11-01 ", newYork))
        assertEquals(parsed, CalendarOffloadHandler.alignBareDateEnd(parsed, "2026-11-01T00:00:00", newYork))
        assertEquals(parsed, CalendarOffloadHandler.alignBareDateEnd(parsed, "-7d", newYork))
    }

    // ─── B-7: source-shape pins (production wiring, not just the pure core) ──

    @Test
    fun `resolveDateRange delegates to the pure core and reads no clock itself`() {
        val body = memberSlice(readHandlerSource(), "private fun resolveDateRange(")
        assertTrue("must delegate to resolveRangePure", body.contains("resolveRangePure("))
        assertFalse("must not build Calendars itself", body.contains("Calendar.getInstance()"))
        assertFalse("must not keep a 24h constant", body.contains("24 * 60 * 60 * 1000L"))
    }

    @Test
    fun `freebusy aligns a bare date end like the list window`() {
        val body = memberSlice(readHandlerSource(), "private fun doFreebusy(")
        assertTrue("freebusy --end must go through alignBareDateEnd", body.contains("alignBareDateEnd("))
    }

    @Test
    fun `help text documents the inclusive bare date end`() {
        val src = readHandlerSource()
        assertTrue(src.contains("--end covers the WHOLE day"))
        assertTrue(src.contains("23:59:59.999 local"))
    }

    @Test
    fun `production parser shape still matches the replica`() {
        // The replica above mirrors parseDateInZone; if the production format
        // list loses the bare-date entry, the replica silently diverges.
        val body = memberSlice(readHandlerSource(), "internal fun parseDateInZone(")
        assertTrue(body.contains("\"yyyy-MM-dd\""))
        assertTrue(body.contains("SimpleDateFormat"))
        assertTrue(body.contains("^([+-]?)(\\d+)([dhm])$"))
    }

    // ─── repo-test conventions (same helpers as CalendarFreebusyInstancesTest) ──

    private fun readHandlerSource(): String =
        readRepoFile("src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/CalendarOffloadHandler.kt")

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val line = lines[i]
            val indent = line.takeWhile { it == ' ' }.length
            indent <= 4 && line.isNotBlank() && !line.trimStart().startsWith("//") &&
                !line.trimStart().startsWith("*") &&
                Regex("^(private|internal|public|protected|fun|val|var|@)").containsMatchIn(line.trimStart())
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }
}
