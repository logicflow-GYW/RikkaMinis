package com.rikkaminis.app.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [R4-V F-10] A terminal destination must never be stacked on top of itself.
 *
 * `MinisOpenUrlBroker.terminalVisible` is set in `TerminalScreen`:
 * `DisposableEffect { set(true) … onDispose { set(false) } }`. Push a SECOND
 * `Routes.TERMINAL` entry and both screens are alive, so leaving the old one
 * fires `onDispose` and publishes `false` while the new terminal is on screen —
 * the visible terminal's tap-to-open-URL path silently turns itself off. The
 * duplicate also starts its own PTY from the same route arguments (a second
 * shell, and `initCommand` delivered twice).
 *
 * And it can happen without a user double tap: the cold-start deep-link
 * dispatcher runs after NavController has already restored the back stack, so a
 * process-death restore plus a terminal deep link (`minis://terminal`) yields two
 * entries from one user intent.
 *
 * This test pins the rule for EVERY terminal navigation in `AppNavigation.kt`,
 * so a new call site cannot quietly skip it.
 *
 * NOT covered here, and deliberately so: the "rotate / leave the screen and come
 * back" half of F-10 lives in `ui/terminal/TerminalScreen.kt` (it owns
 * `stop()` in its `onDispose`) and in `AndroidManifest` (`MainActivity` has no
 * `configChanges`, so a rotation re-creates the activity and disposes the
 * session). Both files are outside this branch's file set — see the report's
 * residual list for the minimal follow-up patch.
 */
class TerminalRouteSingleTopTest {

    private companion object {
        const val NAV = "src/android/app/src/main/java/com/rikkaminis/app/ui/navigation/AppNavigation.kt"
        /**
         * Distance from `Routes.terminal(` to its nav-options block. Generous
         * enough for a multi-line argument list plus an explanatory comment;
         * a call site that really lacks single-top sits far outside it.
         */
        const val WINDOW = 800
    }

    @Test
    fun `every terminal destination is launched single-top`() {
        val src = readRepoFile(NAV)
        val sites = Regex(Regex.escape("Routes.terminal(")).findAll(src).toList()
        assertTrue("no Routes.terminal( call sites found — probe is blind", sites.isNotEmpty())

        val offenders = sites.filter { match ->
            val window = src.substring(match.range.first, minOf(src.length, match.range.first + WINDOW))
            !window.contains("launchSingleTop = true")
        }.map { src.substring(it.range.first, minOf(src.length, it.range.first + 90)).replace('\n', ' ') }

        assertTrue(
            "these terminal navigations can stack a second TERMINAL entry " +
                "(terminalVisible flips false, a second PTY starts):\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the chat entry points resolve the session id before navigating`() {
        val src = readRepoFile(NAV)
        // [da1023-F] kept intact: the route arg stays on the draft key after
        // first-send promotion, so a bare pass-through opens the terminal under
        // the orphan __new__ session. Single-top must not have displaced this.
        assertTrue(
            "onOpenTerminal must still resolve the draft alias",
            src.contains("Routes.terminal(sessionId = ChatViewModelStore.resolveSessionId(sessionId))"),
        )
        val withCommand = src.substringAfter("onOpenTerminalWithCommand", "")
            .substringBefore("onMoveToSession")
        assertTrue("onOpenTerminalWithCommand callback not found — probe is blind", withCommand.isNotBlank())
        assertTrue(
            "onOpenTerminalWithCommand must still resolve the draft alias",
            withCommand.contains("resolveSessionId(sessionId)"),
        )
    }

    @Test
    fun `terminal navigation still goes through the resumed-state guard`() {
        val src = readRepoFile(NAV)
        val sites = Regex(Regex.escape("Routes.terminal(")).findAll(src).toList()
        val unguarded = sites.filter { match ->
            val start = maxOf(0, match.range.first - 120)
            !src.substring(start, match.range.first).contains("safeNavigate(")
        }
        assertTrue(
            "terminal navigations must stay behind safeNavigate()'s RESUMED guard:\n" +
                unguarded.joinToString("\n") { src.substring(maxOf(0, it.range.first - 90), it.range.first).replace('\n', ' ') },
            unguarded.isEmpty(),
        )
    }

    // ── helper ───────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw java.io.FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }
}
