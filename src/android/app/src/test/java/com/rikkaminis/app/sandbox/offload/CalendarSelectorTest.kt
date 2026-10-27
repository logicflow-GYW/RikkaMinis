package com.rikkaminis.app.sandbox.offload

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1034 FIX-A4 ISSUE-07] An explicit calendar selector never
 * silently misses.
 *
 * Defect shape being pinned: `--calendar <name>` / `--calendar-id <id>` on a
 * WRITE fell through to the auto-pick heuristic whenever it matched no writable
 * calendar, so `create` wrote into a calendar the caller never named and
 * `update` MOVED an existing event into the auto-picked one.
 *
 * The mapping from (selector, writable calendars) to an outcome is pure and
 * lives in the top-level `resolveExplicitCalendar`, so the expectations below
 * are real calls with hand-written literals — none of them is derived from the
 * code under test.
 *
 * The read path (`list --calendar NAME`) keeps the tolerant semantics on
 * purpose: there a miss is a legitimate empty answer, and `doListRefuses`
 * below stops that from being "fixed" into a refusal by a later change.
 */
class CalendarSelectorTest {

    private val handler =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/CalendarOffloadHandler.kt"

    private fun cals(vararg pairs: Pair<Long, String>): List<CalendarRef> =
        pairs.map { CalendarRef(id = it.first, displayName = it.second) }

    // ── the pure decision ─────────────────────────────────────────────────

    @Test
    fun `no flag hands the auto-pick back to the caller`() {
        assertNull(resolveExplicitCalendar(cals(1L to "Work"), null, null))
        assertNull(resolveExplicitCalendar(emptyList(), null, null))
    }

    @Test
    fun `an explicit calendar-id wins over the name`() {
        assertEquals(
            CalendarSelection.Matched(2L),
            resolveExplicitCalendar(cals(1L to "Work", 2L to "Home"), 2L, "Work"),
        )
    }

    @Test
    fun `a calendar-id that matches nothing refuses`() {
        assertEquals(CalendarSelection.Unmatched, resolveExplicitCalendar(cals(1L to "Work"), 9L, null))
    }

    @Test
    fun `a calendar name matches case-insensitively on a substring`() {
        assertEquals(
            CalendarSelection.Matched(3L),
            resolveExplicitCalendar(cals(1L to "Work", 3L to "Family Calendar"), null, "family"),
        )
    }

    @Test
    fun `a calendar name that matches nothing refuses`() {
        assertEquals(CalendarSelection.Unmatched, resolveExplicitCalendar(cals(1L to "Work"), null, "Holiday"))
    }

    @Test
    fun `an explicit selector against no writable calendar refuses`() {
        assertEquals(CalendarSelection.Unmatched, resolveExplicitCalendar(emptyList(), null, "Work"))
        assertEquals(CalendarSelection.Unmatched, resolveExplicitCalendar(emptyList(), 1L, null))
    }

    // ── the wiring: who consumes the decision ─────────────────────────────

    @Test
    fun `the write paths route through the refusing resolvers`() {
        val src = readRepoFile(handler)
        val create = memberSlice(src, "private fun doCreate(")
        assertTrue(
            "doCreate must resolve its target through requireCalendarTarget",
            create.contains("requireCalendarTarget("),
        )
        val update = memberSlice(src, "private fun doUpdate(")
        assertTrue(
            "doUpdate must keep the no-move-when-absent contract through optionalCalendarTarget",
            update.contains("optionalCalendarTarget("),
        )
        assertTrue("the miss must become a refusal", src.contains("CalendarTarget.Refused("))
        assertTrue("the refusal must be structured", src.contains("\"calendar_not_found\""))
    }

    @Test
    fun `the silent auto-pick fallback is gone`() {
        val src = readRepoFile(handler)
        assertFalse(
            "pickWritableCalendar() without calendars is the old fallback",
            src.contains("pickWritableCalendar()"),
        )
        assertFalse(
            "the 'falling back to auto-pick' log line is the old tolerance",
            src.contains("falling back to auto-pick"),
        )
    }

    @Test
    fun `doList keeps the filter semantics`() {
        val body = stripComments(memberSlice(readRepoFile(handler), "private fun doList("))
        assertTrue("list must still read the filter flag", body.contains("args.get(\"calendar\")"))
        assertFalse(
            "an unmatched --calendar on the READ path is a legitimate empty answer",
            body.contains("calendar_not_found") || body.contains("CalendarTarget.Refused(") ||
                body.contains("CalendarSelection.Unmatched"),
        )
    }

    @Test
    fun `an explicit selector never reaches the auto-pick`() {
        val body = memberSlice(readRepoFile(handler), "private fun requireCalendarTarget(")
        assertFalse(
            "the unmatched branch must refuse, not fall through",
            body.contains("is CalendarSelection.Unmatched -> Unit"),
        )
        assertTrue(
            "the unmatched branch must build a refusal in the same branch",
            Regex("""CalendarSelection\.Unmatched\s*->\s*\n\s*return CalendarTarget\.Refused\(""")
                .containsMatchIn(body),
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Comments out, so an assertion never judges the explanation.
     *
     * Hand-written linear scan on purpose: the regex form (a line-comment
     * alternative plus a lazy block-comment match) recurses once per character
     * of a block comment, which blew the JVM stack (StackOverflowError in
     * Pattern$LazyLoop) — measured on CI.
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') i++
            } else if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i = if (i + 2 > src.length) src.length else i + 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun repoRoot(): File {
        System.getProperty("probe.repo")?.let { return File(it) }
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            if (File(dir, handler).exists()) return dir
            dir = dir.parentFile
        }
        throw FileNotFoundException("repo root not found from user.dir=${System.getProperty("user.dir")}")
    }

    private fun readRepoFile(relative: String): String {
        val f = File(repoRoot(), relative)
        if (!f.exists()) throw FileNotFoundException(f.path)
        return f.readText()
    }

    /** Source lines of the class member at [marker], up to the next member. */
    private fun memberSlice(src: String, marker: String): String {
        val lines = src.lines()
        val start = lines.indexOfFirst { it.contains(marker) }
        assertTrue("marker not found: $marker", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { idx ->
            val t = lines[idx]
            val indent = t.length - t.trimStart(' ').length
            indent in 1..4 && t.trim().isNotEmpty() && !t.trim().startsWith("//") &&
                !t.trim().startsWith("*") &&
                Regex("""^(private|internal|public|protected|fun|val|var|@)""").containsMatchIn(t.trim())
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }
}
