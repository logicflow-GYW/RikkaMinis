package com.rikkaminis.app.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R5-F F-C-10] Shape probes for the terminal's `initCommand` re-delivery.
 *
 * `launchSingleTop` keeps the TERMINAL destination on top and REPLACES its
 * NavBackStackEntry with a copy carrying the new route arguments
 * (NavController.launchSingleTopInternal → `NavBackStackEntry(entry, args)`),
 * so the screen recomposes with the new `initCommand` — but the effects were
 * keyed on `Unit`, and `Unit` never changes. "Terminal already open, then
 * `open_terminal?init_command=…`" therefore did nothing at all, silently.
 *
 * Neither file can be compiled on the JVM (both are Compose), so this reads the
 * shipped sources — the house pattern (LaunchSessionRerouteShapeTest) — and
 * every criterion carries an in-test negative control built from the same
 * source, so a probe that has quietly stopped looking fails loudly.
 *
 * The `remember(sessionId)` arm is graded on its own: keying the session is
 * what stops a *changed session* from reusing a PTY whose proot args and env
 * already name the old one — and [R4-V F-10] (never start a second PTY for the
 * same session) is exactly why the key is the session, not the command.
 */
class TerminalInitCommandShapeTest {

    private val navRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"
    private val screenRel =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/terminal/TerminalScreen.kt"

    private val keyedRemember =
        "val session = remember(sessionId) { TerminalSession(context.applicationContext) }"
    private val startEffect = "LaunchedEffect(terminalSession) {"
    private val prefillEffect = "LaunchedEffect(terminalSession, initCommand) {"
    private val stopEffect = "DisposableEffect(terminalSession) {"
    private val stopBody = "onDispose { terminalSession.stop() }"
    private val hostPathArg = "Routes.filePreview(item.hostPath.absolutePath)"
    private val plainPathArg = "Routes.filePreview(item.file.absolutePath)"

    // ── 1. the shipped sources satisfy every criterion ──────────────────

    @Test
    fun `shipped sources pass every criterion`() {
        assertEquals(emptyList<String>(), check(read(navRel), read(screenRel)))
    }

    // ── 2. negative controls ────────────────────────────────────────────

    @Test
    fun `an unkeyed session is rejected`() {
        val nav = read(navRel).replace(
            keyedRemember,
            "val session = remember { TerminalSession(context.applicationContext) }",
        )
        assertTrue(
            "expected the unkeyed remember to be reported",
            check(nav, read(screenRel)).contains("nav: session not keyed on sessionId"),
        )
    }

    @Test
    fun `a prefill effect keyed on Unit is rejected`() {
        val screen = read(screenRel).replace(prefillEffect, "LaunchedEffect(Unit) {")
        assertTrue(
            "expected the unkeyed prefill to be reported",
            check(read(navRel), screen).contains("screen: prefill effect not keyed on the command"),
        )
    }

    @Test
    fun `a prefill effect that forgets the session is rejected`() {
        val screen = read(screenRel).replace(prefillEffect, "LaunchedEffect(initCommand) {")
        assertTrue(
            "expected the session-less prefill key to be reported",
            check(read(navRel), screen)
                .contains("screen: prefill effect must restart when the session changes"),
        )
    }

    @Test
    fun `a start effect keyed on Unit is rejected`() {
        val screen = read(screenRel).replace(startEffect, "LaunchedEffect(Unit) {")
        assertTrue(
            "expected the unkeyed start effect to be reported",
            check(read(navRel), screen).contains("screen: start effect not keyed on the session"),
        )
    }

    @Test
    fun `a stop effect keyed on Unit is rejected`() {
        val screen = read(screenRel).replace(stopEffect, "DisposableEffect(Unit) {")
        assertTrue(
            "expected the unkeyed stop effect to be reported",
            check(read(navRel), screen).contains("screen: stop effect not keyed on the session"),
        )
    }

    @Test
    fun `a preview site handed the link's own path is rejected`() {
        val nav = read(navRel).replace("Routes.filePreview(item.hostPath.absolutePath)", plainPathArg)
        assertTrue(
            "expected the link-path preview site to be reported",
            check(nav, read(screenRel)).any { it.startsWith("nav: preview site") },
        )
    }

    // ── the probe ──────────────────────────────────────────────────────

    /**
     * Every criterion, as a list of problem labels. Called with the shipped
     * sources (expect empty) and with each mutation above (expect that label).
     */
    private fun check(nav: String, screen: String): List<String> {
        val problems = mutableListOf<String>()

        // ── the session object must be rebuilt when the session id changes ──
        if (!nav.contains(keyedRemember)) problems += "nav: session not keyed on sessionId"
        if (nav.contains("remember { TerminalSession(")) {
            problems += "nav: session not keyed on sessionId"
        }

        // ── every preview site must hand the host-readable path ────────────
        val navCode = strip(nav)
        val hostCount = navCode.windowed(hostPathArg.length).count { it == hostPathArg }
        if (hostCount != 3) problems += "nav: preview site count is $hostCount, expected 3"
        if (navCode.contains(plainPathArg)) {
            problems += "nav: preview site still hands the link's own path"
        }

        // ── the three lifecycle effects must be keyed ──────────────────────
        val screenCode = strip(screen)
        if (!screenCode.contains(startEffect)) {
            problems += "screen: start effect not keyed on the session"
        }
        if (!screenCode.contains(prefillEffect)) {
            problems += "screen: prefill effect not keyed on the command"
        }
        if (screenCode.contains("LaunchedEffect(initCommand) {")) {
            problems += "screen: prefill effect must restart when the session changes"
        }
        if (!screenCode.contains(stopEffect)) {
            problems += "screen: stop effect not keyed on the session"
        }
        // The stop effect must still stop THIS session — keying is worthless
        // if the body was dropped with it.
        val stopIdx = screenCode.indexOf(stopEffect)
        if (stopIdx >= 0) {
            val body = screenCode.substring(stopIdx, minOf(stopIdx + 120, screenCode.length))
            if (!body.contains(stopBody)) problems += "screen: stop effect no longer stops the session"
        }
        // [R4-V F-10] guards against a SECOND PTY for the same session. Keying
        // must never have added one: exactly one start call, keyed.
        val startCalls = screenCode.windowed("terminalSession.start(".length)
            .count { it == "terminalSession.start(" }
        if (startCalls != 1) problems += "screen: $startCalls start() call(s), expected 1"
        return problems
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun read(relative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = java.io.File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException(
            "$relative not found from user.dir=${System.getProperty("user.dir")}",
        )
    }

    /** Blanks comments and string/char literals, preserving offsets. */
    private fun strip(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            val next = if (i + 1 < n) src[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> {
                    while (i < n && src[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && next == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < n && !(src[i] == '*' && i + 1 < n && src[i + 1] == '/')) {
                        out.append(if (src[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i + 1 < n) {
                        out.append("  ")
                        i += 2
                    }
                }
                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(' ')
                    i++
                    while (i < n) {
                        if (src[i] == '\\') {
                            out.append("  ")
                            i += 2
                            continue
                        }
                        if (src[i] == quote) {
                            out.append(' ')
                            i++
                            break
                        }
                        out.append(if (src[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
