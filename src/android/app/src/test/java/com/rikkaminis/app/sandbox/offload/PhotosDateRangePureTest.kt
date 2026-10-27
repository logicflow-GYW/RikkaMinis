package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * [deep-audit-1016c FIX-B C] Pure date-alignment helpers for
 * `android-photos` date ranges — compiled from the VERBATIM top-level source
 * of PhotosOffloadHandler.kt via probe/jvm/extract_pure.py, so the code under
 * test IS the shipped code, not a hand copy that can drift.
 *
 * Every expected value below is a written literal computed from an
 * independent implementation (python zoneinfo ground truth, see
 * probe/pre-p3-expected.txt), never derived from the functions under test.
 *
 * The two bugs this pins:
 *  1. date-only `--end` parsed to 00:00:00.000 ⇒ zero-width ranges;
 *  2. `--days N` computed as `now - N * 24h` ⇒ hour of drift across a DST
 *     transition can truncate onto the wrong local day.
 *
 * [da1032-C] adds the window RESOLUTION layer (`resolvePhotoRange`): an
 * unreadable `--start` / `--end` or an inverted window must refuse the command
 * instead of resolving to "no filter", plus the positional contract of
 * `handleAlbum`'s projection (the album surface published no dimensions).
 */
class PhotosDateRangePureTest {

    private val ny = TimeZone.getTimeZone("America/New_York")
    private val utc = TimeZone.getTimeZone("UTC")

    // ── isDateOnly ────────────────────────────────────────────────────────

    @Test
    fun `date-only shape is recognized`() {
        assertTrue(isDateOnly("2026-10-16"))
        assertTrue(isDateOnly("  2026-10-16  ")) // surrounding whitespace tolerated
    }

    @Test
    fun `anything with a time part or garbage is not date-only`() {
        assertFalse(isDateOnly("2026-10-16T23:59:59"))
        assertFalse(isDateOnly("2026-10-16T10:00"))
        assertFalse(isDateOnly("2026-10-16 10:00:00"))
        assertFalse(isDateOnly("2026-10-1"))
        assertFalse(isDateOnly("x2026-10-16"))
        assertFalse(isDateOnly(""))
    }

    // ── alignDateOnlyEndMs ────────────────────────────────────────────────

    @Test
    fun `normal day in New York aligns to 23_59_59_999`() {
        // ground truth (python zoneinfo): 2026-10-16T00:00:00-04:00 == 1792123200000
        val midnight = 1792123200000L
        // ground truth: 2026-10-16T23:59:59.999-04:00 == 1792209599999
        assertEquals(1792209599999L, alignDateOnlyEndMs(midnight, ny))
    }

    @Test
    fun `utc day aligns across the same instant ladder`() {
        // ground truth: 2026-10-16T00:00:00Z == 1792108800000; end == 1792195199999
        assertEquals(1792195199999L, alignDateOnlyEndMs(1792108800000L, utc))
    }

    @Test
    fun `spring-forward day keeps its full 25h wall clock`() {
        // 2026-03-08 is the 2026 spring-forward day in America/New_York:
        // midnight is EST (-05:00), 23:59:59.999 is EDT (-04:00).
        // ground truth: midnight == 1772946000000; end == 1773028799999
        assertEquals(1773028799999L, alignDateOnlyEndMs(1772946000000L, ny))
        // The naive `+24h` alignment would have produced 1773032399999 —
        // 00:59:59.999 of the NEXT local day (the day is only 23h long) —
        // the literal above is exactly 3600000 lower.
        assertEquals(3600000L, 1773032399999L - alignDateOnlyEndMs(1772946000000L, ny))
    }

    @Test
    fun `fall-back day keeps its full 23h wall clock`() {
        // 2026-11-01 is the 2026 fall-back day in America/New_York.
        // ground truth: midnight (EDT -04:00) == 1793505600000; end (EST -05:00) == 1793595599999
        assertEquals(1793595599999L, alignDateOnlyEndMs(1793505600000L, ny))
    }

    @Test
    fun `midday instant lands on its own day's end`() {
        // 2026-10-16T12:00:00-04:00 == 1792166400000 → same day's 23:59:59.999
        assertEquals(1792209599999L, alignDateOnlyEndMs(1792166400000L, ny))
    }

    // ── daysAgoStartOfDayMs ───────────────────────────────────────────────

    @Test
    fun `one day back crosses the DST boundary by wall clock not arithmetic`() {
        // now = 2026-03-09T00:15:00-04:00 == 1773029700000 (the day AFTER
        // spring forward). The PREVIOUS LOCAL DAY's midnight is
        // 2026-03-08T00:00:00-05:00 == 1772946000000.
        assertEquals(1772946000000L, daysAgoStartOfDayMs(1773029700000L, 1, ny))
        // The buggy form (now - 24h = 2026-03-07T23:15 EST, truncated to
        // 2026-03-07T00:00 EST == 1772859600000) would have skipped a day.
    }

    @Test
    fun `seven days back in stable fall time`() {
        // now = 2026-10-16T12:00:00-04:00 == 1792166400000;
        // 7 local days earlier, local midnight: 2026-10-09T00:00:00-04:00 == 1791518400000
        assertEquals(1791518400000L, daysAgoStartOfDayMs(1792166400000L, 7, ny))
    }

    @Test
    fun `zero days back is today's local midnight`() {
        // 2026-10-16T12:00:00-04:00 → 2026-10-16T00:00:00-04:00 == 1792123200000
        assertEquals(1792123200000L, daysAgoStartOfDayMs(1792166400000L, 0, ny))
    }

    @Test
    fun `utc never drifts`() {
        // 2026-10-16T12:00:00Z == 1792152000000 minus 3 days → 2026-10-13T00:00:00Z == 1791849600000
        assertEquals(1791849600000L, daysAgoStartOfDayMs(1792152000000L, 3, utc))
    }

    // ── resolvePhotoRange (da1032-C ISSUE-04) ─────────────────────────────

    /**
     * Fake parser for the window arms below: only the two shapes they use
     * resolve; anything else is the "supplied but unreadable" case. (The real
     * `parseDate` list is exercised by the arms above.)
     */
    private val fakeParse: (String) -> Long? = { raw ->
        when (raw) {
            "2026-10-16" -> 1792108800000L   // ground truth: 2026-10-16T00:00:00Z
            "2026-10-18" -> 1792281600000L   // ground truth: 2026-10-18T00:00:00Z
            "2026-10-18T12:00:00" -> 1792324800000L // ground truth: 2026-10-18T12:00:00Z
            else -> null
        }
    }

    @Test
    fun `no date flags means no filter, and the parser is not consulted`() {
        var calls = 0
        val r = resolvePhotoRange(1792166400000L, utc, null, null, null) { calls++; fakeParse(it) }
        assertEquals(PhotoRange.Window(null, null), r)
        assertEquals("absent flags must not reach the parser", 0, calls)
    }

    @Test
    fun `a lone --start filters from that instant on`() {
        // A bare-date start is the day's start (only the END bound gets the
        // end-of-day alignment), unchanged from the pre-fix contract.
        assertEquals(
            PhotoRange.Window(1792108800000L, null),
            resolvePhotoRange(1792166400000L, utc, null, "2026-10-16", null, fakeParse),
        )
    }

    @Test
    fun `a lone bare-date --end covers that whole day`() {
        assertEquals(
            PhotoRange.Window(null, 1792195199999L), // 2026-10-16T23:59:59.999Z
            resolvePhotoRange(1792166400000L, utc, null, null, "2026-10-16", fakeParse),
        )
    }

    @Test
    fun `--days outranks the explicit bounds`() {
        assertEquals(
            PhotoRange.Window(1791518400000L, 1792166400000L),
            resolvePhotoRange(1792166400000L, ny, 7, "2026-10-16", "2026-10-16", fakeParse),
        )
        // days <= 0 keeps the historical 7-day reading.
        assertEquals(
            PhotoRange.Window(1791518400000L, 1792166400000L),
            resolvePhotoRange(1792166400000L, ny, 0, null, null, fakeParse),
        )
    }

    @Test
    fun `an unreadable bound is refused by name, never read as no filter`() {
        // The defect: these used to answer Window(null, …) -> "no filter" ->
        // the command returned the ENTIRE library for a typo'd date.
        assertEquals(
            PhotoRange.Invalid("--start", "2026-13-40"),
            resolvePhotoRange(1792166400000L, utc, null, "2026-13-40", null, fakeParse),
        )
        assertEquals(
            PhotoRange.Invalid("--end", "2026-13-40"),
            resolvePhotoRange(1792166400000L, utc, null, null, "2026-13-40", fakeParse),
        )
        assertEquals(
            PhotoRange.Invalid("--end", "2026-10-16"),
            resolvePhotoRange(1792166400000L, utc, null, "2026-10-18", "2026-10-16", fakeParse),
        )
    }

    @Test
    fun `an inverted window is refused while same-day and equal bounds stay legal`() {
        // start 2026-10-18 > end 2026-10-16 -> a range that cannot hold anything.
        assertEquals(
            PhotoRange.Invalid("--end", "2026-10-16"),
            resolvePhotoRange(1792166400000L, utc, null, "2026-10-18", "2026-10-16", fakeParse),
        )
        // Same day named twice: start = 00:00, end = 23:59:59.999 -> legal.
        assertEquals(
            PhotoRange.Window(1792108800000L, 1792195199999L),
            resolvePhotoRange(1792166400000L, utc, null, "2026-10-16", "2026-10-16", fakeParse),
        )
        // Equal instants are a (narrow) legal query, not an inversion.
        assertEquals(
            PhotoRange.Window(1792324800000L, 1792324800000L),
            resolvePhotoRange(1792166400000L, utc, null, "2026-10-18T12:00:00", "2026-10-18T12:00:00", fakeParse),
        )
    }

    @Test
    fun `a flag with no value stays the absent case`() {
        assertEquals(
            PhotoRange.Window(null, null),
            resolvePhotoRange(1792166400000L, utc, null, "", "  ", fakeParse),
        )
    }

    // ── resolvePhotoRange `--days` refusal ([dv14-A3] ISSUE-05) ────────────

    @Test
    fun `a supplied but unreadable --days is refused instead of answering the library`() {
        // The defect: getInt("days") answers null for "7d" exactly as it does for
        // an absent flag, so the window resolved to (null, null) = "no filter"
        // and `android-photos list --days 7d` returned the ENTIRE library, exit 0.
        assertEquals(
            PhotoRange.Invalid("--days", "7d"),
            resolvePhotoRange(
                nowMs = 1792166400000L, zone = utc, daysRaw = null, startRaw = null, endRaw = null,
                parse = fakeParse, daysText = "7d",
            ),
        )
        assertEquals(
            PhotoRange.Invalid("--days", "last-week"),
            resolvePhotoRange(
                nowMs = 1792166400000L, zone = utc, daysRaw = null, startRaw = null, endRaw = null,
                parse = fakeParse, daysText = "last-week",
            ),
        )
        assertEquals(
            PhotoRange.Invalid("--days", "7.5"),
            resolvePhotoRange(
                nowMs = 1792166400000L, zone = utc, daysRaw = null, startRaw = null, endRaw = null,
                parse = fakeParse, daysText = "7.5",
            ),
        )
    }

    @Test
    fun `an absent or readable --days keeps its window`() {
        // Absent (flag given no value / never passed): no filter, unchanged.
        assertEquals(
            PhotoRange.Window(null, null),
            resolvePhotoRange(
                nowMs = 1792166400000L, zone = utc, daysRaw = null, startRaw = null, endRaw = null,
                parse = fakeParse, daysText = null,
            ),
        )
        assertEquals(
            PhotoRange.Window(null, null),
            resolvePhotoRange(
                nowMs = 1792166400000L, zone = utc, daysRaw = null, startRaw = null, endRaw = null,
                parse = fakeParse, daysText = "   ",
            ),
        )
        // Readable integer string: the same 7-day window as daysRaw = 7.
        assertEquals(
            PhotoRange.Window(1791518400000L, 1792166400000L),
            resolvePhotoRange(
                nowMs = 1792166400000L, zone = ny, daysRaw = 7, startRaw = null, endRaw = null,
                parse = fakeParse, daysText = "7",
            ),
        )
        // A non-positive integer keeps the historical degrade-to-7 reading.
        assertEquals(
            PhotoRange.Window(1791518400000L, 1792166400000L),
            resolvePhotoRange(
                nowMs = 1792166400000L, zone = ny, daysRaw = 0, startRaw = null, endRaw = null,
                parse = fakeParse, daysText = "0",
            ),
        )
    }

    @Test
    fun `the handler hands the pure core the raw days string`() {
        val body = memberSlice(readRepoFile(HANDLER_REL), "private fun resolveDateRange(")
        assertTrue("the raw --days string must reach the core", body.contains("daysText = args.get(\"days\")"))
        assertTrue("the integer read stays alongside it", body.contains("daysRaw = args.getInt(\"days\")"))
    }

    // ── the query surface (da1032-C ISSUE-04) ─────────────────────────────

    @Test
    fun `the list surface refuses an invalid window instead of querying`() {
        val body = memberSlice(readRepoFile(HANDLER_REL), "private fun handleList(")
        assertTrue(
            "handleList must branch on the resolved range, not destructure a nullable pair",
            body.contains("resolveDateRange(args)"),
        )
        assertTrue("an invalid window must be refused here", body.contains("PhotoRange.Invalid"))
        assertTrue("the refusal names the offending flag", body.contains("invalid \${r.flag}"))
    }

    @Test
    fun `the handler resolves the window through the shared pure core`() {
        val body = memberSlice(readRepoFile(HANDLER_REL), "private fun resolveDateRange(")
        assertTrue("must delegate to the pure core", body.contains("resolvePhotoRange("))
        // The null / alignment decisions must live in the core, not here.
        assertFalse("the end-of-day alignment decision belongs to the core", body.contains("alignDateOnlyEndMs("))
        assertFalse("the member must not branch on nulls itself", body.contains("?.let"))
    }

    @Test
    fun `album rows carry the width and height columns at the indices the cursor reads`() {
        val body = memberSlice(readRepoFile(HANDLER_REL), "private fun handleAlbum(")
        val projBlock = body.substringAfter("val proj = arrayOf(").substringBefore(")")
        val cols = Regex("""MediaStore\.Images\.Media\.(\w+)""")
            .findAll(projBlock).map { it.groupValues[1] }.toList()
        // The defect: the album projection dropped WIDTH/HEIGHT, so every
        // album row published no dimensions while handleList published both.
        val widthIdx = cols.indexOf("WIDTH")
        val heightIdx = cols.indexOf("HEIGHT")
        assertTrue("album rows need the WIDTH column (have $cols)", widthIdx >= 0)
        assertTrue("album rows need the HEIGHT column (have $cols)", heightIdx >= 0)
        // Column order is an implicit contract with the positional reads.
        assertEquals(
            "every projected column is read exactly once, in order",
            (0 until cols.size).toList(),
            Regex("""c\.get\w+\((\d+)\)""").findAll(body).map { it.groupValues[1].toInt() }.toList(),
        )
        assertTrue(
            "width must be emitted from the WIDTH column (index $widthIdx)",
            body.contains("put(\"width\", c.getInt($widthIdx))"),
        )
        assertTrue(
            "height must be emitted from the HEIGHT column (index $heightIdx)",
            body.contains("put(\"height\", c.getInt($heightIdx))"),
        )
    }

    // ── [dv16-A A-01] the shipped photo parser is strict ───────────────────

    @Test
    fun `the shipped photo parser refuses an out-of-range bound`() {
        val body = memberSlice(readRepoFile(HANDLER_REL), "private fun parseDate(")
        assertTrue(body.contains("SimpleDateFormat"))
        assertTrue(body.contains("\"yyyy-MM-dd\"")) // the bare-date pattern survives
        assertTrue(
            "parseDate must parse strictly (isLenient = false): the lenient default ROLLS " +
                "2026-02-30 over to March 2, so a mistyped --start/--end answers for another day",
            body.contains("isLenient = false"),
        )
    }

    @Test
    fun `a strict bound parse turns a rolled-over date into a refusal`() {
        // Differential: the SAME token through the two modes of the same JDK
        // parser. Lenient parses it (to a day nobody spelled), strict does not —
        // and the resolve layer then refuses instead of filtering the library
        // on the wrong day. The shipped parser's own mode is pinned above.
        //
        // Both lambdas honour the injected-parse contract `(String) -> Long?`:
        // a token it cannot read is null, never a thrown ParseException — which
        // is exactly what the shipped `parseDate` does (each pattern attempt is
        // wrapped, and strict mode reports an out-of-range field by THROWING).
        val lenient: (String) -> Long? = { raw ->
            runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)?.time }.getOrNull()
        }
        val strict: (String) -> Long? = { raw ->
            runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(raw)?.time
            }.getOrNull()
        }
        assertTrue("lenient mode rolls 2026-13-40 forward", lenient("2026-13-40") != null)
        assertNull("strict mode refuses 2026-13-40", strict("2026-13-40"))
        assertEquals(
            PhotoRange.Invalid("--start", "2026-13-40"),
            resolvePhotoRange(1792166400000L, utc, null, "2026-13-40", null, null, strict),
        )
        // …and the other bound is refused by name too.
        assertEquals(
            PhotoRange.Invalid("--end", "2026-13-40"),
            resolvePhotoRange(1792166400000L, utc, null, null, "2026-13-40", null, strict),
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

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

    companion object {
        const val HANDLER_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/PhotosOffloadHandler.kt"

        // Silence "unused" for the fixture tree anchor used by the runner script.
        @JvmStatic fun probeSource(): File = File(".")
    }
}
