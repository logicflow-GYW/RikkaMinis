package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.util.TimeZone

/**
 * [da1032-C] ISSUE-08 — the two failure shapes of `minis-sessions-cli`.
 *
 * Layer split (why this file is not an end-to-end handler run):
 *  - the handler needs a ChatRepository + Application, i.e. Room, which the
 *    JVM unit-test runtime does not carry. What IS reachable is the decision
 *    core (`sessionsDateBound` / `parseSessionsDate`, lifted to top level for
 *    exactly this reason) and the *source* shape of the surface that consumes
 *    it — the same split SessionsCliArgsTest uses for the argv layer.
 *  - the date arms below run the REAL parser, so "2026-13-40 is not a date"
 *    is a measured fact, not a restatement of the code.
 *
 * Expectations are literals, never derived from the code under test.
 */
class SessionsOffloadHandlerValidationTest {

    // ── part 1: the flag reader (real parser) ───────────────────────────────

    private fun <T> inUtc(body: () -> T): T {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            return body()
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `a real date parses to its local midnight`() {
        // ground truth: 2026-10-19T00:00:00Z
        inUtc { assertEquals(1792368000000L, parseSessionsDate("2026-10-19")) }
    }

    @Test
    fun `junk is not a date`() {
        assertTrue(parseSessionsDate("2026-13-40") == null)
        assertTrue(parseSessionsDate("not-a-date") == null)
        // Nothing at all is not a date either — but it is also not an ERROR,
        // which is what the bound reader below distinguishes.
        assertTrue(parseSessionsDate("") == null)
        assertTrue(parseSessionsDate(null) == null)
    }

    @Test
    fun `a date with trailing text reads as its leading date`() {
        // MEASURED, not blessed: SimpleDateFormat.parse(String) stops at the
        // first unparsable character, so `--start 2026-10-19T10:00` resolves to
        // 2026-10-19 00:00 local and the clock part is dropped. Fail-closed
        // would need a ParsePosition index check; that is a deliberate
        // behaviour change (callers may pass a timestamp today) and is
        // registered, not taken, by da1032-C.
        inUtc {
            assertEquals(1792368000000L, parseSessionsDate("2026-10-19T10:00"))
            assertEquals(1792368000000L, parseSessionsDate("2026-10-19 10:00"))
        }
    }

    // ── part 2: absent vs unreadable (the decision core) ────────────────────

    @Test
    fun `an absent flag is not a filter, and never reaches the parser`() {
        var calls = 0
        assertEquals(null, sessionsDateBound("--start", null) { calls++; null })
        assertEquals("an absent flag must not be parsed", 0, calls)
        // A flag with no usable value is the absent case too (OffloadArgs hands
        // back null / "" for a bare `--start` followed by another flag).
        assertEquals(null, sessionsDateBound("--start", "") { calls++; null })
        assertEquals(null, sessionsDateBound("--start", "   ") { calls++; null })
        assertEquals(0, calls)
    }

    @Test
    fun `a real date becomes the bound`() {
        inUtc {
            assertEquals(1792368000000L, sessionsDateBound("--start", "2026-10-19") { parseSessionsDate(it) })
        }
    }

    @Test
    fun `an unreadable flag is refused by name`() {
        // The defect: this returned null, the caller read null as "no filter",
        // and the command answered the ENTIRE history for a typo'd date.
        val e = assertThrows(InvalidDateArg::class.java) {
            sessionsDateBound("--start", "2026-13-40") { parseSessionsDate(it) }
        }
        assertTrue("the refusal must name the flag", e.message!!.contains("--start"))
        assertTrue("the refusal must quote what was rejected", e.message!!.contains("2026-13-40"))
        val f = assertThrows(InvalidDateArg::class.java) {
            sessionsDateBound("--end", "later") { parseSessionsDate(it) }
        }
        assertTrue(f.message!!.contains("--end"))
    }

    // ── part 3: the surface that consumes it ───────────────────────────────

    @Test
    fun `the three reading subcommands route their dates through the funnel`() {
        val src = readHandlerSource()
        for (marker in listOf("private fun cmdList(", "private fun cmdSearch(", "private fun cmdMessages(")) {
            val body = memberSlice(src, marker)
            assertTrue("$marker must resolve both bounds through the funnel", body.contains("resolveDateBounds(args)"))
            assertFalse(
                "$marker must not read a nullable parse result directly (that is the bug)",
                body.contains("parseSessionsDate(args.get(") || body.contains("parseDate(args.get("),
            )
        }
        val funnel = memberSlice(src, "private fun resolveDateBounds(")
        assertTrue("both bounds go through the refusing reader", funnel.contains("sessionsDateBound(\"--start\""))
        assertTrue(funnel.contains("sessionsDateBound(\"--end\""))
    }

    @Test
    fun `the refusal is answered as INVALID_ARGS, ahead of the catch-all`() {
        val handle = memberSlice(readHandlerSource(), "override fun handle(")
        val specific = handle.indexOf("catch (e: InvalidDateArg)")
        val generic = handle.indexOf("catch (e: Throwable)")
        assertTrue("the typed refusal must be caught", specific >= 0)
        assertTrue("…and before the catch-all, or it would surface as INTERNAL", generic >= 0)
        assertTrue("InvalidDateArg must be caught FIRST", specific < generic)
        val refusal = handle.substring(specific, generic)
        assertTrue("the refusal uses the file's existing envelope", refusal.contains("INVALID_ARGS"))
        assertTrue("…with the command's exit code", refusal.contains("EXIT_INVALID_ARGS"))
    }

    // ── part 4: the unknown-command message (the second half of ISSUE-08) ──

    @Test
    fun `the unknown-command message is built from the dispatch table`() {
        val src = readHandlerSource()
        // The defect, verbatim: `send` was dispatchable while the message
        // still advertised the older, shorter set.
        assertFalse(
            "the stale hand-written list must be gone",
            src.contains("Valid: list, search, messages."),
        )
        assertTrue(
            "the message must derive the list from SUBCOMMANDS",
            src.contains("Valid: \${SUBCOMMANDS.joinToString(\", \")}"),
        )
    }

    @Test
    fun `every advertised subcommand is dispatchable and nothing else is`() {
        val src = readHandlerSource()
        val advertised = Regex("""SUBCOMMANDS = listOf\(([^)]*)\)""").find(src)
        assertTrue("SUBCOMMANDS declaration not found", advertised != null)
        val listed = Regex("\"([a-z-]+)\"").findAll(advertised!!.groupValues[1]).map { it.groupValues[1] }.toSet()
        assertTrue("the table must not be empty", listed.isNotEmpty())

        val handle = memberSlice(src, "override fun handle(")
        val dispatched = Regex("^\\s*\"([a-z-]+)\" ->", RegexOption.MULTILINE)
            .findAll(handle).map { it.groupValues[1] }.toSet()

        assertEquals("advertised and dispatchable must be the same set", listed, dispatched)
        // The concrete regression: `send` is reachable, so it must be listed.
        assertTrue("send is dispatchable and must be advertised", "send" in listed)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun readHandlerSource(): String = readRepoFile(HANDLER_REL)

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
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/SessionsOffloadHandler.kt"
    }
}
