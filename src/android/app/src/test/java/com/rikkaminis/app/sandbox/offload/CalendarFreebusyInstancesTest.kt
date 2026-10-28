package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1017-D] Regression tests for the two calendar defects fixed on this branch.
 *
 * Layer split (why this file does not drive the handler end to end):
 *  - `android.content.ContentValues` / `ContentResolver` are stubs in the JVM
 *    unit-test runtime (returnDefaultValues) — a behavioural run would prove
 *    nothing, and `Instances.CONTENT_URI` is `Uri.parse`-built, i.e. null there.
 *  - So CI pins: (a) the pure decision core, (b) the *source* shape of the
 *    query surface (which table the freebusy path reads, which columns, which
 *    output fields), and (c) the window-selection template + argument order.
 *  - Behaviour against real provider rows is proven in the sandbox probe
 *    (`shared/work/deep-audit-1017/fix-d/probe/jvm/`, 12 tests) plus a SQLite
 *    differential that executes the captured predicate against the measured
 *    row dump.
 *
 * Expectations below are literals, never derived from the code under test.
 */
class CalendarFreebusyInstancesTest {

    // ── part 1: freebusy must read Instances, not Events ─────────────────────

    @Test
    fun `freebusy queries instances and drops the null-able events predicate`() {
        val body = memberSlice(readHandlerSource(), "private fun doFreebusy(")

        assertTrue(
            "the freebusy window must ride on the instances URI (sweep1014-B25a precedent)",
            body.contains("CalendarContract.Instances.CONTENT_URI"),
        )
        assertTrue(
            "the instants window must go through the shared selection helper",
            body.contains("instancesWindowSelection("),
        )
        assertTrue(
            "the projection must come from the shared helper (cursor reads are positional)",
            body.contains("freebusyInstanceProjection("),
        )
        // The defect: Events.DTEND is NULL for every RRULE event (the provider
        // stores DURATION instead), so `DTEND > ?` is never true and every
        // recurring event was reported as free.
        assertFalse(
            "freebusy must not filter on Events.DTEND again — NULL for recurring events",
            body.contains("CalendarContract.Events.DTEND"),
        )
        assertFalse(body.contains("CalendarContract.Events.DTSTART"))
        assertFalse(body.contains("CalendarContract.Events.CONTENT_URI"))
    }

    @Test
    fun `freebusy projection maps the positional cursor reads`() {
        val proj = memberSlice(readHandlerSource(), "internal fun freebusyInstanceProjection(")
        val title = proj.indexOf("CalendarContract.Instances.TITLE")
        val begin = proj.indexOf("CalendarContract.Instances.BEGIN")
        val end = proj.indexOf("CalendarContract.Instances.END")
        val allDay = proj.indexOf("CalendarContract.Instances.ALL_DAY")

        assertTrue("TITLE must be projected", title >= 0)
        assertTrue("BEGIN must be projected", begin >= 0)
        assertTrue("END must be projected", end >= 0)
        assertTrue("ALL_DAY must be projected", allDay >= 0)
        // doFreebusy reads 0=TITLE, 1=BEGIN, 2=END, 3=ALL_DAY — order is load-bearing.
        assertTrue("TITLE before BEGIN", title < begin)
        assertTrue("BEGIN before END", begin < end)
        assertTrue("END before ALL_DAY", end < allDay)
    }

    @Test
    fun `freebusy window selection keeps the operator shape and argument order`() {
        val (selection, selArgs) = CalendarOffloadHandler.instancesWindowSelection(1_000L, 2_000L)

        // Template shape (names come from the constants; the *literal* column
        // names are pinned by freebusyProjectionColumnMapping above + the SQLite
        // differential, and the parameter order below is the load-bearing part).
        val m = Regex("^(.+) < \\? AND (.+) > \\?$").find(selection)
        assertTrue("selection must be '<col> < ? AND <col> > ?' but was '$selection'", m != null)
        assertTrue("left operand must not be empty", m!!.groupValues[1].isNotBlank())
        assertTrue("right operand must not be empty", m.groupValues[2].isNotBlank())
        assertEquals("exactly two bind placeholders", 2, selection.count { it == '?' })

        // Instances windows are half-open [begin, end): BEGIN < winEnd, END > winStart.
        assertEquals(2, selArgs.size)
        assertEquals("2000", selArgs[0])
        assertEquals("1000", selArgs[1])
    }

    @Test
    fun `freebusy still skips all-day instances and keeps its output fields`() {
        val body = memberSlice(readHandlerSource(), "private fun doFreebusy(")
        assertTrue("all-day skip must survive the table switch", body.contains("it.getInt(3) == 1"))
        assertTrue(body.contains("if (isAllDay) continue"))
        // Caller contract: field names are unchanged by this fix.
        assertTrue(body.contains("put(\"busy\""))
        assertTrue(body.contains("put(\"free\""))
        assertTrue(body.contains("put(\"title\""))
        assertTrue(body.contains("put(\"start\""))
        assertTrue(body.contains("put(\"end\""))
    }

    // ── part 2: update must honour all-day semantics ─────────────────────────

    @Test
    fun `all-day semantics come from the flag or the stored row`() {
        assertTrue(CalendarOffloadHandler.updateUsesAllDaySemantics(explicitAllDay = true, storedAllDay = true))
        assertTrue(CalendarOffloadHandler.updateUsesAllDaySemantics(explicitAllDay = true, storedAllDay = false))
        assertTrue(CalendarOffloadHandler.updateUsesAllDaySemantics(explicitAllDay = false, storedAllDay = true))
        assertFalse(CalendarOffloadHandler.updateUsesAllDaySemantics(explicitAllDay = false, storedAllDay = false))
        // Unreadable row: never guess, fall back to the flag-only reading.
        assertFalse(CalendarOffloadHandler.updateUsesAllDaySemantics(explicitAllDay = false, storedAllDay = null))
    }

    @Test
    fun `all-day DTEND repair keeps the stored day span`() {
        // single-day all-day row: provider stores dtstart == dtend (span 0)
        assertEquals(
            1_795_651_200_000L,
            CalendarOffloadHandler.allDayEndRepairMs(1_795_564_800_000L, 1_795_564_800_000L, 1_795_651_200_000L),
        )
        // 3-day all-day row keeps its 3-day length
        assertEquals(
            1_796_083_200_000L,
            CalendarOffloadHandler.allDayEndRepairMs(1_795_132_800_000L, 1_795_392_000_000L, 1_795_824_000_000L),
        )
        // unreadable span degrades to the canonical single-day shape
        assertEquals(
            1_795_651_200_000L,
            CalendarOffloadHandler.allDayEndRepairMs(null, null, 1_795_651_200_000L),
        )
        // inverted stored row must not carry a negative span
        assertEquals(
            1_795_651_200_000L,
            CalendarOffloadHandler.allDayEndRepairMs(1_795_651_200_000L, 1_795_564_800_000L, 1_795_651_200_000L),
        )
        // no DTSTART in the update: nothing to repair
        assertEquals(null, CalendarOffloadHandler.allDayEndRepairMs(1_795_564_800_000L, 1_795_564_800_000L, null))
    }

    @Test
    fun `update reads the stored row and routes dates through the all-day parser`() {
        val body = memberSlice(readHandlerSource(), "private fun doUpdate(")
        assertTrue("must read the stored row first", body.contains("findEventRow("))
        assertTrue(body.contains("updateUsesAllDaySemantics("))
        assertTrue("all-day dates are UTC-midnight parsed (doCreate precedent)", body.contains("parseDateUtc("))
        assertTrue("moved all-day events must repair DTEND", body.contains("allDayEndRepairMs("))
        assertTrue("the flag must actually be consumed", body.contains("hasFlag(\"all-day\")"))
        assertTrue(body.contains("CalendarContract.Events.DTEND"))
    }

    // ── part 3: timed ranges (da1031-C) ──────────────────────────────────────

    @Test
    fun `timed DTEND repair keeps the stored duration`() {
        // stored 1_795_651_200_000 → 1_795_656_600_000 (90 min span) moved to
        // 1_795_655_400_000: the repaired end keeps the 90 min duration.
        assertEquals(
            1_795_660_800_000L,
            CalendarOffloadHandler.timedEndRepairMs(1_795_651_200_000L, 1_795_656_600_000L, 1_795_655_400_000L),
        )
        // degenerate stored span (end == start, e.g. a legacy inverted row)
        // degrades to doCreate's 1h default — never to a 0-duration row.
        assertEquals(
            1_795_659_000_000L,
            CalendarOffloadHandler.timedEndRepairMs(1_795_651_200_000L, 1_795_651_200_000L, 1_795_655_400_000L),
        )
        // inverted stored span (end < start) must not carry a negative span
        assertEquals(
            1_795_659_000_000L,
            CalendarOffloadHandler.timedEndRepairMs(1_795_651_200_000L, 1_795_564_800_000L, 1_795_655_400_000L),
        )
        // A row whose DTSTART/DTEND could not be read stays untouched (null):
        // better an unverified old end than an invented one.
        assertEquals(null, CalendarOffloadHandler.timedEndRepairMs(null, null, 1_795_655_400_000L))
        assertEquals(
            null,
            CalendarOffloadHandler.timedEndRepairMs(1_795_651_200_000L, null, 1_795_655_400_000L),
        )
    }

    @Test
    fun `the timed repair always lands its end after the new start`() {
        for (storedEnd in listOf(1_795_656_600_000L, 1_795_651_200_000L, 1_795_564_800_000L)) {
            val repaired = CalendarOffloadHandler.timedEndRepairMs(1_795_651_200_000L, storedEnd, 1_795_655_400_000L)
            assertTrue("repaired end must follow the moved start", repaired != null && repaired > 1_795_655_400_000L)
        }
    }

    @Test
    fun `inverted timed ranges are refused while all-day keeps its canonical zero-width shape`() {
        assertTrue(CalendarOffloadHandler.invertedTimedRange(false, 1_795_651_200_000L, 1_795_651_200_000L))
        assertTrue(CalendarOffloadHandler.invertedTimedRange(false, 1_795_651_200_000L, 1_795_564_800_000L))
        assertFalse(CalendarOffloadHandler.invertedTimedRange(false, 1_795_651_200_000L, 1_795_656_600_000L))
        // All-day rows: the provider stores dtstart == dtend (canonical
        // single-day shape), so end == start is CORRECT there and must stay
        // accepted — the repair always lands end at or after start.
        assertFalse(CalendarOffloadHandler.invertedTimedRange(true, 1_795_651_200_000L, 1_795_651_200_000L))
        // [meta1028-D2 F-04] …but a NEGATIVE all-day span is not canonical: it
        // is exactly the inverted row da1017-D2 measured disappearing from
        // list/freebusy, and `create --all-day --start D20 --end D15` used to
        // answer created:true while writing it.
        assertTrue(CalendarOffloadHandler.invertedTimedRange(true, 1_795_651_200_000L, 1_795_564_800_000L))
        // Missing bound: nothing to judge (the repair/1h default decides).
        assertFalse(CalendarOffloadHandler.invertedTimedRange(false, null, 1_795_656_600_000L))
        assertFalse(CalendarOffloadHandler.invertedTimedRange(false, 1_795_651_200_000L, null))
        assertFalse(CalendarOffloadHandler.invertedTimedRange(true, null, 1_795_564_800_000L))
    }

    @Test
    fun `an all-day create without --end keeps the 1h default and stays accepted`() {
        // The guard cannot simply drop its all-day branch in the other
        // direction either: doCreate's documented default (createEndMs →
        // start + 1h) must keep passing for all-day callers.
        val start = 1_795_651_200_000L
        val defaultEnd = CalendarOffloadHandler.createEndMs(null, start) { null }
        assertEquals(start + 3_600_000L, defaultEnd)
        assertFalse(CalendarOffloadHandler.invertedTimedRange(true, start, defaultEnd))
    }

    @Test
    fun `create and update refuse an inverted timed range before writing`() {
        val handler = readHandlerSource()
        val create = memberSlice(handler, "private fun doCreate(")
        val update = memberSlice(handler, "private fun doUpdate(")
        for ((label, body) in listOf("doCreate" to create, "doUpdate" to update)) {
            assertTrue(
                "$label must route through the shared inverted predicate",
                body.contains("invertedTimedRange("),
            )
        }
        assertTrue(create.contains("hasFlag(\"all-day\")"))
        assertTrue(update.contains("hasFlag(\"all-day\")"))
    }

    @Test
    fun `update moves the timed repair next to the all-day repair`() {
        val body = memberSlice(readHandlerSource(), "private fun doUpdate(")
        assertTrue(body.contains("allDayEndRepairMs("))
        assertTrue(body.contains("timedEndRepairMs("))
        // The defect: an orphan reminder + updated=true with exit 0 on a
        // rows=0 fall-through. The not_found answer must come BEFORE the
        // reminder write.
        val beforeReminder = body.substringBefore("replaceReminder(")
        assertTrue(
            "the rows=0 fall-through must be refused before any reminder write",
            beforeReminder.contains("not_found"),
        )
    }

    @Test
    fun `help documents --all-day for update`() {
        val src = readHandlerSource()
        // Both the class KDoc usage block and the HELP text must carry the flag
        // on the *update* line (the `--notes N`-style create line is separate).
        assertEquals(
            "update usage must list --all-day in KDoc and HELP",
            2,
            Regex("\\[--notes \\.\\.\\.\\] \\[--location \\.\\.\\.\\] \\[--all-day\\]").findAll(src).count(),
        )
        assertTrue(
            "HELP must explain the all-day date semantics",
            src.contains("their dates are parsed in UTC instead"),
        )
    }

    // ── part 4: doCreate's --end must not swallow a bad value (da1032-C) ─────

    @Test
    fun `create keeps the 1h default exactly when --end is absent`() {
        val start = 1_795_651_200_000L
        // absent flag -> the documented default, and the parser is not even
        // consulted (nothing to parse).
        var parserCalls = 0
        assertEquals(
            "absent --end keeps doCreate's 1h default",
            1_795_654_800_000L,
            CalendarOffloadHandler.createEndMs(null, start) { parserCalls++; 42L },
        )
        assertEquals("an absent flag must not reach the parser", 0, parserCalls)
        // The literal above is start + 3_600_000, spelled out so a change to
        // the default is not silently absorbed by the expectation.
        assertEquals(3_600_000L, 1_795_654_800_000L - start)
    }

    @Test
    fun `create uses the caller's instant when --end parses`() {
        assertEquals(
            1_795_656_600_000L,
            CalendarOffloadHandler.createEndMs("2026-11-22T10:30:00Z", 1_795_651_200_000L) { 1_795_656_600_000L },
        )
    }

    @Test
    fun `create signals a refusal when --end cannot be parsed`() {
        val start = 1_795_651_200_000L
        // The defect: `args.get("end")?.let { parse(it) } ?: (start + 1h)`
        // joined "no value supplied" with "supplied but unreadable" and wrote a
        // one-hour event, answering success. Null is the refusal signal.
        assertEquals(null, CalendarOffloadHandler.createEndMs("2026-13-40", start) { null })
        // ...and an unreadable value must never fall back to the default.
        assertTrue(
            CalendarOffloadHandler.createEndMs("nope", start) { null } != start + 3_600_000L,
        )
        // A flag with no usable value is the ABSENT case, not a refusal — the
        // parser's own model (OffloadArgs treats a bare `--end` as a boolean
        // flag and hands back null). Behaviour for these is unchanged.
        assertEquals(
            1_795_654_800_000L,
            CalendarOffloadHandler.createEndMs("", start) { 42L },
        )
        assertEquals(
            1_795_654_800_000L,
            CalendarOffloadHandler.createEndMs("   ", start) { 42L },
        )
    }

    @Test
    fun `create refuses an unreadable --end in the source, not just in the helper`() {
        val body = memberSlice(readHandlerSource(), "private fun doCreate(")
        assertTrue("doCreate must route --end through the shared helper", body.contains("createEndMs("))
        assertTrue(
            "the refusal must name the flag the caller got wrong",
            body.contains("android-calendar create: invalid --end"),
        )
        val helper = memberSlice(readHandlerSource(), "internal fun createEndMs(")
        assertTrue(
            "a flag with no value is the ABSENT case, before any parse",
            helper.contains("if (rawEnd.isNullOrBlank())"),
        )
        assertTrue(
            "the 1h default lives inside the absent branch",
            helper.contains("60L * 60 * 1000"),
        )
    }

    // ── [dv16-A A-01] the shipped calendar parser is strict ────────────────

    @Test
    fun `the shipped calendar parser refuses an out-of-range date instead of rolling it over`() {
        val body = memberSlice(readHandlerSource(), "internal fun parseDateInZone(")
        // Java's SimpleDateFormat is LENIENT by default: an out-of-range field
        // is ROLLED FORWARD (`2026-02-30` → March 2, `2026-13-01` → next
        // January), so a mistyped --start/--end answered for a DIFFERENT day
        // instead of being refused. This is the same decision the sessions
        // funnel (parseSessionsDate) already carried — the only parser that had
        // it before this change.
        assertTrue(
            "parseDateInZone must parse strictly (isLenient = false)",
            body.contains("isLenient = false"),
        )
        // The flag was added WITHOUT touching what the tests above mirror: the
        // relative-offset branch and the format list are the same ones.
        assertTrue(body.contains("^([+-]?)(\\d+)([dhm])$"))
        assertTrue(body.contains("SimpleDateFormat"))
        assertTrue(body.contains("\"yyyy-MM-dd\""))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

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

    /** Lines of the member declared at [marker], up to the next sibling member. */
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
