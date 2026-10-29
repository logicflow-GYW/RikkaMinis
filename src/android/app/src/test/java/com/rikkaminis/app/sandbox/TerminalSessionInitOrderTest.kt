package com.rikkaminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [deep-audit-1032 ISSUE-05, P1] Source-shape probe for the terminal boot
 * ordering.
 *
 * The defect is an *ordering* defect, and ordering is invisible to a JVM unit
 * test: [TerminalSession.start] needs a live PRoot PTY (Context, Termux JNI,
 * `PRootKernel.boot`), and the screen's prefill needs a Compose runtime. What
 * made it a real bug is not "two timers differ" — it is which bytes reach the
 * PTY first:
 *
 *  - pre-fix, `_state.value = State.RUNNING` was published BEFORE the
 *    `cd /var/minis && clear\r` preamble, and the preamble itself was written
 *    300 ms *after* that publish;
 *  - the screen prefilled `initCommand` after a fixed 500 ms, i.e. with only
 *    200 ms of margin against `PRootKernel.boot()`.
 *
 * Whenever boot overran the margin the prefill was queued first and the two
 * writes coalesced into ONE input line — `<prefill>cd /var/minis && clear` + CR
 * — which the interactive shell executed as a single command, and `clear` then
 * wiped whatever the user was supposed to see. Sanitising the prefill cannot
 * prevent that: the CR that submits the line belongs to the preamble write.
 *
 * So the invariants asserted here are the ones that make the ordering safe:
 *
 * 1. `start()` publishes RUNNING strictly AFTER the preamble write;
 * 2. a `myGeneration` guard sits between the two (a `stop()` during the 300 ms
 *    delay must not resurrect a killed PTY as RUNNING);
 * 3. the re-entry guard / BOOTING publish / `termuxSession = session`
 *    assignment are still in place (the failure handler relies on the latter to
 *    reap an orphaned PTY);
 * 4. `TerminalScreen` waits for RUNNING instead of a wall-clock delay, and the
 *    prefill is sent after that wait.
 *
 * Every arm also runs the same analyser over a pre-fix fixture, so a probe that
 * has quietly stopped looking fails loudly instead of passing. Comments are
 * blanked (offsets preserved) before analysis: the KDoc in `start()` quotes the
 * very `_state.value = State.RUNNING` line being located.
 */
class TerminalSessionInitOrderTest {

    private val sessionRel =
        "src/android/app/src/main/java/com/rikkaminis/app/sandbox/TerminalSession.kt"
    private val screenRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/terminal/TerminalScreen.kt"

    // ── source access ────────────────────────────────────────────────────────

    private fun sourceFile(rel: String): File {
        val viaAndroid = "src/android/app/$rel"
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val d = dir
            if (File(d, rel).isFile) return File(d, rel)
            if (File(d, viaAndroid).isFile) return File(d, viaAndroid)
            dir = d.parentFile
        }
        error("'$rel' not found from ${File(".").absoluteFile}")
    }

    /** Comments blanked, every offset preserved (string-literal aware). */
    private fun codeOnly(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '"' || c == '\'') {
                val end = skipLiteral(src, i)
                out.append(src, i, end)
                i = end
                continue
            }
            if (src.startsWith("//", i)) {
                var j = i
                while (j < n && src[j] != '\n') {
                    out.append(' ')
                    j++
                }
                i = j
                continue
            }
            if (src.startsWith("/*", i)) {
                val close = src.indexOf("*/", i + 2)
                val j = if (close < 0) n else close + 2
                for (k in i until j) out.append(if (src[k] == '\n') '\n' else ' ')
                i = j
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun skipLiteral(code: String, i: Int): Int {
        val q = code[i]
        if (q == '"' && code.startsWith("\"\"\"", i)) {
            val j = code.indexOf("\"\"\"", i + 3)
            return if (j < 0) code.length else j + 3
        }
        var j = i + 1
        while (j < code.length) {
            if (code[j] == '\\') {
                j += 2
                continue
            }
            if (code[j] == q || code[j] == '\n') return j + 1
            j++
        }
        return code.length
    }

    /** The single `{ … }` block whose declaration matches [decl], else null. */
    private fun blockAt(code: String, decl: Regex): String? {
        val matches = decl.findAll(code).toList()
        if (matches.size != 1) return null
        return blockFrom(code, code.indexOf('{', matches[0].range.first))
    }

    /** The `{ … }` block that opens at [open] (index of `{`), else null. */
    private fun blockFrom(code: String, open: Int): String? {
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < code.length) {
            val c = code[i]
            if (c == '"' || c == '\'') {
                i = skipLiteral(code, i)
                continue
            }
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun lineOf(code: String, idx: Int): Int =
        code.take(idx).count { it == '\n' } + 1

    // ── analyser: TerminalSession.start() ────────────────────────────────────

    private fun sessionViolations(rawSrc: String): List<String> {
        val code = codeOnly(rawSrc)
        val body = blockAt(code, Regex("\\bfun\\s+start\\s*\\("))
            ?: return listOf("TerminalSession.start() declaration missing or ambiguous")
        val out = mutableListOf<String>()

        val preamble = body.indexOf("session.write(initCmd")
        val running = body.indexOf("_state.value = State.RUNNING")
        if (preamble < 0) out += "start(): the cd/clear preamble write (`session.write(initCmd…`) is missing"
        if (running < 0) out += "start(): `_state.value = State.RUNNING` is missing"

        if (preamble >= 0 && running >= 0 && running < preamble) {
            out += "start(): RUNNING is published at line ${lineOf(code, running)} BEFORE the " +
                "preamble write at line ${lineOf(code, preamble)} — anything the screen types " +
                "in between is queued ahead of `cd … && clear`, and its CR submits both"
        }
        if (preamble >= 0 && running >= 0) {
            val guards = Regex("if\\s*\\(\\s*myGeneration\\s*!=\\s*startGeneration\\.get\\(\\)\\s*\\)")
                .findAll(body).map { it.range.first }.toList()
            if (guards.none { it > preamble && it < running }) {
                out += "start(): no `myGeneration != startGeneration.get()` guard between the " +
                    "preamble write (line ${lineOf(code, preamble)}) and the RUNNING publish " +
                    "(line ${lineOf(code, running)}) — a stop() during the delay would " +
                    "resurrect a killed PTY as RUNNING"
            }
        }

        // Regression anchors (shapes the rest of the lifecycle depends on).
        if (!body.contains("_state.value = State.BOOTING")) {
            out += "start(): the initial BOOTING publish is gone"
        }
        if (!Regex("if\\s*\\(\\s*_state\\.value\\s*==\\s*State\\.RUNNING\\s*\\)\\s*return")
                .containsMatchIn(body)
        ) {
            out += "start(): the re-entry guard (`if (_state.value == State.RUNNING) return`) is gone"
        }
        if (!body.contains("termuxSession = session")) {
            out += "start(): `termuxSession = session` is gone (the failure handler reaps it)"
        }
        return out
    }

    // ── analyser: TerminalScreen prefill ─────────────────────────────────────

    /**
     * [R5-F F-C-10] The prefill is located by CONTENT, not by its key list.
     *
     * The effect key had to move from `Unit` to `(terminalSession, initCommand)`:
     * `launchSingleTop` keeps TERMINAL on top and REPLACES its NavBackStackEntry
     * with a copy carrying the new route arguments (navigation 2.8.5
     * `NavController.launchSingleTopInternal` → `NavBackStackEntry(entry, args)`
     * → `visibleEntries` → `NavHost` `collectAsState`), so the content lambda
     * re-runs with the new `initCommand` while `LaunchedEffect(Unit)` never
     * restarts — "terminal already on screen, then
     * `open_terminal?init_command=…`" did nothing at all, silently.
     *
     * Every ordering invariant this arm was written for is unchanged and still
     * graded: the prefill awaits RUNNING instead of a wall-clock delay, the wait
     * precedes the send, and the control-character filter survives. The new
     * criterion is the key itself — it must name the command (or the command is
     * still dropped) and the session object (or a changed `sessionId` keeps a
     * PTY whose proot args and env name the old session).
     */
    private fun screenViolations(rawSrc: String): List<String> {
        val code = codeOnly(rawSrc)
        // Every `LaunchedEffect(<keys>) { … }` whose body prefills the PTY.
        val prefills = Regex("LaunchedEffect\\s*\\(([^)]*)\\)\\s*\\{").findAll(code)
            .mapNotNull { m ->
                val body = blockFrom(code, code.indexOf('{', m.range.last)) ?: return@mapNotNull null
                if (body.contains("sendText(")) m.groupValues[1] to body else null
            }
            .toList()
        if (prefills.isEmpty()) {
            return listOf(
                "TerminalScreen: no LaunchedEffect block sends the prefill (`sendText(`) — " +
                    "the command cannot be delivered at all",
            )
        }
        if (prefills.size > 1) {
            return listOf(
                "TerminalScreen: ${prefills.size} LaunchedEffect blocks call sendText — " +
                    "one prefill path, or the characters land twice",
            )
        }
        val (keys, body) = prefills[0]
        val out = mutableListOf<String>()

        val send = body.indexOf("sendText(")
        if (code.contains("delay(500)")) {
            out += "TerminalScreen: a fixed `delay(500)` is back — the prefill must wait for " +
                "the session's RUNNING state (which is published after the cd preamble), not " +
                "a wall-clock guess"
        }
        val wait = body.indexOf("state.first")
        if (wait < 0) {
            out += "TerminalScreen: the prefill no longer awaits " +
                "`terminalSession.state.first { it == TerminalSession.State.RUNNING }`"
        } else if (send >= 0 && wait > send) {
            out += "TerminalScreen: the RUNNING wait sits AFTER the prefill " +
                "(wait line ${lineOf(code, wait)}, send line ${lineOf(code, send)})"
        }
        if (!body.contains("initCommand.filter {")) {
            out += "TerminalScreen: the control-character filter on initCommand is gone"
        }
        if (!keys.contains("initCommand")) {
            out += "TerminalScreen: the prefill effect is not keyed on `initCommand` — a " +
                "TERMINAL destination already on screen never re-delivers the command"
        }
        if (!keys.contains("terminalSession")) {
            out += "TerminalScreen: the prefill effect is not keyed on `terminalSession` — a " +
                "changed sessionId would keep delivering into the replaced PTY"
        }
        return out
    }

    // ── live tree ────────────────────────────────────────────────────────────

    @Test
    fun `start publishes RUNNING only after the cd preamble`() {
        val violations = sessionViolations(sourceFile(sessionRel).readText())
        assertEquals("session ordering violations: $violations", emptyList<String>(), violations)
    }

    @Test
    fun `screen prefills only after RUNNING is published`() {
        val violations = screenViolations(sourceFile(screenRel).readText())
        assertEquals("screen ordering violations: $violations", emptyList<String>(), violations)
    }

    // ── teeth: the pre-fix ordering must be rejected ─────────────────────────

    /** The pre-fix body, quoted verbatim from the audit (kept on purpose). */
    private val preFixSession = """
        class TerminalSession {
            fun start(sessionId: String? = null) {
                if (_state.value == State.RUNNING) return
                _state.value = State.BOOTING
                val myGeneration = startGeneration.incrementAndGet()
                scope.launch {
                    termuxSession = session
                    _state.value = State.RUNNING
                    liveSessions.add(WeakReference(this@TerminalSession))
                    if (sessionId != null) {
                        kotlinx.coroutines.delay(300)
                        val initCmd = "cd /var/minis && clear\r".toByteArray()
                        session.write(initCmd, 0, initCmd.size)
                    }
                }
            }
        }
    """.trimIndent()

    private val preFixScreen = """
        @Composable
        fun TerminalScreen(initCommand: String?) {
            LaunchedEffect(Unit) {
                if (!initCommand.isNullOrBlank()) {
                    kotlinx.coroutines.delay(500)
                    val safe = initCommand.filter { it != '\u0000' }
                    if (safe.isNotBlank()) {
                        terminalSession.sendText(safe)
                    }
                }
            }
        }
    """.trimIndent()

    @Test
    fun `pre-fix ordering is rejected by the probe`() {
        val violations = sessionViolations(preFixSession)
        assertTrue(
            "pre-fix ordering must be RED, got $violations",
            violations.any { it.contains("BEFORE the preamble write") },
        )
        // The guard arm must fire too: the fixture has no guard at all.
        assertTrue(
            "pre-fix fixture must also report the missing generation guard, got $violations",
            violations.any { it.contains("no `myGeneration") },
        )
    }

    @Test
    fun `pre-fix fixed-delay prefill is rejected by the probe`() {
        val violations = screenViolations(preFixScreen)
        assertTrue(
            "a fixed delay(500) prefill must be RED, got $violations",
            violations.any { it.contains("delay(500)") },
        )
        assertTrue(
            "a prefill without the RUNNING wait must be RED, got $violations",
            violations.any { it.contains("no longer awaits") },
        )
    }

    /**
     * [R5-F F-C-10] Teeth for the NEW criterion. The pre-fix screen is also
     * unkeyed, so remove the fixed delay and add the RUNNING wait: nothing else
     * is wrong with this body — it still drops the command the moment the
     * TERMINAL destination is reused, because the key is still `Unit`.
     */
    @Test
    fun `a prefill keyed on Unit is rejected even when the ordering is right`() {
        val unitKeyed = preFixScreen
            .replace(
                "            kotlinx.coroutines.delay(500)\n",
                "            terminalSession.state.first { it == TerminalSession.State.RUNNING }\n",
            )
        val violations = screenViolations(unitKeyed)
        assertTrue(
            "a correct-but-unkeyed prefill must still be RED, got $violations",
            violations.any { it.contains("not keyed on `initCommand`") },
        )
        assertTrue(
            "the unkeyed fixture must also name the missing session key, got $violations",
            violations.any { it.contains("not keyed on `terminalSession`") },
        )
        // …and the ordering arms must be quiet on it, or the fixture is not
        // isolating what this test claims to isolate.
        assertTrue(
            "the ordering arms must be green on this fixture, got $violations",
            violations.none { it.contains("delay(500)") || it.contains("no longer awaits") },
        )
    }

    @Test
    fun `a RUNNING assignment inside a comment does not satisfy the probe`() {
        // Teeth for the comment stripper: the live KDoc quotes this very line.
        val commented = preFixSession.replace(
            "_state.value = State.RUNNING\n",
            "// _state.value = State.RUNNING\n",
        )
        val violations = sessionViolations(commented)
        assertTrue(
            "a commented-out publish must still be RED (comments must be blanked), got $violations",
            violations.any { it.contains("`_state.value = State.RUNNING` is missing") },
        )
    }
}
