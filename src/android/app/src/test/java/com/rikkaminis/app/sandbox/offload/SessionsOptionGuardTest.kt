package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [meta1028-B05 F-12] `minis-sessions-cli` must REFUSE an option it does not
 * implement instead of ignoring it.
 *
 * The defect being closed, verbatim: `list --kewords python` (one typo'd
 * letter) parses as a value under a key nobody reads, no command consults it,
 * and `list` answers EVERY session at exit 0 — which reads as "the filter ran
 * and matched everything". The value-option-without-a-value spelling
 * (`list --start --end 2026-01-01`) is the same class: [OffloadArgs] files a
 * bare `--start` as a boolean flag, the bound then reads as null = "no filter",
 * and an inverted window of dates comes back as the whole history.
 *
 * Running the handler needs a Context + ChatRepository, so the decision is
 * tested here as a pure function and the WIRING is asserted against the real
 * source: [handle] must consult [sessionsOptionProblem] before dispatch and
 * answer INVALID_ARGS + exit 2 without running the subcommand.
 *
 * Expectations are literals; no arm derives its expectation from the tables
 * under test (a new option must turn these red).
 */
class SessionsOptionGuardTest {

    companion object {
        private const val HANDLER_REL =
            "src/android/app/src/main/java/com/rikkaminis/app/sandbox/offload/SessionsOffloadHandler.kt"
    }

    private fun args(vararg argv: String, sub: String = "list"): OffloadArgs =
        OffloadArgs(listOf(sub) + argv.toList(), booleanFlags = setOf("full", "async", "wait", "help", "h"))

    private fun problem(sub: String, vararg argv: String): String? =
        sessionsOptionProblem(sub, args(argv = argv, sub = sub))

    // ── clean invocations: nothing to report ────────────────────────────────

    @Test
    fun `the documented invocations are clean`() {
        assertNull(problem("list"))
        assertNull(problem("list", "--limit", "10"))
        assertNull(problem("list", "--keywords", "python flask"))
        assertNull(problem("list", "--start", "2026-01-01", "--end", "2026-03-31"))
        assertNull(problem("list", "--ids", "abc,def"))
        assertNull(problem("search", "--keywords", "API error", "--limit", "20"))
        assertNull(problem("messages", "--id", "abc"))
        assertNull(problem("messages", "--id", "abc", "--full"))
        assertNull(problem("messages", "--id", "abc", "--offset", "20", "--limit", "10"))
        assertNull(problem("send", "--prompt", "hi"))
        assertNull(problem("send", "--prompt", "hi", "--async"))
        assertNull(problem("send", "--prompt", "hi", "--wait", "--wait-timeout", "60"))
    }

    @Test
    fun `the shared output flags are never reported as unknown`() {
        // --compact / -q / --quiet / --help / -h belong to every CLI.
        assertNull(problem("list", "--compact"))
        assertNull(problem("list", "-q"))
        assertNull(problem("list", "--quiet"))
        assertNull(problem("list", "--help"))
        assertNull(problem("list", "-h"))
        assertNull(problem("messages", "--id", "abc", "--compact"))
    }

    @Test
    fun `an unknown subcommand is left to its own error path`() {
        // "wat" is not a subcommand: reporting its flags here would answer a
        // different question (the handler has a dedicated message naming the
        // real commands).
        assertNull(problem("wat", "--keywords", "x"))
        assertNull(problem("wat"))
    }

    // ── 1: typo'd option ────────────────────────────────────────────────────

    @Test
    fun `a typo'd keyword option is refused`() {
        val msg = problem("list", "--kewords", "python")
        assertTrue("must be refused, got null", msg != null)
        assertTrue("must name the typo, got: $msg", msg!!.contains("--kewords"))
        assertTrue("must list what list accepts, got: $msg", msg.contains("--keywords"))
    }

    @Test
    fun `a typo is refused on every subcommand`() {
        assertTrue(problem("search", "--keywords", "x", "--lmit", "5")!!.contains("--lmit"))
        assertTrue(problem("messages", "--id", "abc", "--offsett", "5")!!.contains("--offsett"))
        assertTrue(problem("send", "--prompt", "hi", "--asynch")!!.contains("--asynch"))
    }

    @Test
    fun `an option from another subcommand is refused`() {
        // `--full` is messages-only; on `list` it used to be ignored (and would
        // have silently done nothing even if it were wired).
        val msg = problem("list", "--full")
        assertTrue("must be refused, got null", msg != null)
        assertTrue("must name the option, got: $msg", msg!!.contains("--full"))
        // …and --keywords is list-only, not messages.
        assertTrue(problem("messages", "--id", "abc", "--keywords", "x")!!.contains("--keywords"))
    }

    @Test
    fun `a short flag nobody reads is refused`() {
        assertTrue(problem("list", "-z")!!.contains("--z"))
    }

    // ── 2: value option spelled bare ────────────────────────────────────────

    @Test
    fun `a bare start is refused as needing a value`() {
        // `--start --end 2026-01-01` : "--end" is not a value, so the parser
        // files --start as a FLAG and the bound reads as "no filter".
        val msg = problem("list", "--start", "--end", "2026-01-01")
        assertTrue("must be refused, got null", msg != null)
        assertTrue("must name --start, got: $msg", msg!!.contains("--start"))
        assertTrue("must say a value is missing, got: $msg", msg.contains("needs a value"))
    }

    @Test
    fun `a trailing bare ids is refused`() {
        assertTrue(problem("list", "--ids")!!.contains("--ids"))
    }

    @Test
    fun `a bare limit reaches the refusal rather than the default`() {
        val msg = problem("list", "--limit")
        assertTrue("must be refused, got null", msg != null)
        assertTrue("must name --limit, got: $msg", msg!!.contains("--limit"))
    }

    @Test
    fun `a bare offset is refused on messages`() {
        assertTrue(problem("messages", "--id", "abc", "--offset")!!.contains("--offset"))
    }

    @Test
    fun `a bare wait-timeout is refused on send`() {
        assertTrue(problem("send", "--prompt", "hi", "--wait-timeout")!!.contains("--wait-timeout"))
    }

    // ── the tables themselves ───────────────────────────────────────────────

    @Test
    fun `send accepts id as well as session, because cmdSend falls back to it`() {
        // The fallback `args.get("session") ?: args.get("id")` is real; refusing
        // --id here would break the spelling the fallback exists for.
        assertNull(problem("send", "--prompt", "hi", "--id", "abc"))
        assertTrue("the fallback must still be wired", handlerSource().contains("args.get(\"session\") ?: args.get(\"id\")"))
    }

    @Test
    fun `every documented option is in the table`() {
        val help = handlerSource().substringAfter("OPTIONS:").substringBefore("OUTPUT (list)")
        // Only the option LINES (`  --name …`), not the prose: the OPTIONS
        // section now quotes a typo'd spelling as the example it refuses, and
        // scanning free text would pick that up as a documented option.
        val documented = Regex("""(?m)^\s+--([a-z][a-z-]*)\b""")
            .findAll(help).map { it.groupValues[1] }.toSet()
        val known = listOf("list", "search", "messages", "send")
            .fold(emptySet<String>()) { acc, s -> acc + SessionsOptions.known(s) }
        val missing = documented.filter { it !in known && it !in setOf("compact", "quiet", "help") }
        assertEquals(
            "an option the help text advertises must be implemented by some subcommand: $missing",
            emptySet<String>(),
            missing.toSet(),
        )
        assertTrue("keywords must be known", "keywords" in known)
        assertTrue("wait-timeout must be known", "wait-timeout" in known)
    }

    // ── wiring: handle() must consult the guard before dispatch ─────────────

    private fun handlerSource(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, HANDLER_REL)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$HANDLER_REL not found from user.dir=${System.getProperty("user.dir")}")
    }

    /** `handle`'s body, sliced by declaration (brace-matched). */
    private fun handleBody(): String {
        val src = handlerSource()
        val start = src.indexOf("override fun handle(")
        assertTrue("handle must exist", start >= 0)
        var depth = 0
        var i = src.indexOf('{', start)
        val from = i
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(from, i + 1)
                }
            }
            i++
        }
        error("unbalanced handle")
    }

    @Test
    fun `handle consults the guard before dispatching`() {
        val body = handleBody()
        val sub = body.indexOf("val sub = args.positional.firstOrNull()")
        val guard = body.indexOf("sessionsOptionProblem(")
        val dispatch = body.indexOf("return try {")
        assertTrue("the subcommand must be resolved", sub >= 0)
        assertTrue("handle must call sessionsOptionProblem", guard >= 0)
        assertTrue("dispatch must still be there", dispatch >= 0)
        assertTrue("the guard runs AFTER sub is known", sub < guard)
        assertTrue("the guard runs BEFORE dispatch, or the command already ran", guard < dispatch)
    }

    @Test
    fun `the refusal is INVALID_ARGS with exit 2 and the help text`() {
        val body = handleBody()
        val guard = body.indexOf("sessionsOptionProblem(")
        val dispatch = body.indexOf("return try {")
        val refusal = body.substring(guard, dispatch)
        assertTrue("must be INVALID_ARGS", refusal.contains("INVALID_ARGS"))
        assertTrue("must use the usage exit code", refusal.contains("EXIT_INVALID_ARGS"))
        assertTrue("must return, not fall through", refusal.contains("return NativeOffloadResult("))
        assertTrue("must print the help so the caller can self-correct", refusal.contains("HELP_TEXT"))
    }

    @Test
    fun `the refusal does not run the subcommand`() {
        val body = handleBody()
        val guard = body.indexOf("sessionsOptionProblem(")
        val dispatch = body.indexOf("return try {")
        val refusal = body.substring(guard, dispatch)
        for (cmd in listOf("cmdList(", "cmdSearch(", "cmdMessages(", "cmdSend(")) {
            assertFalse("the refusal must not invoke $cmd", refusal.contains(cmd))
        }
    }
}
