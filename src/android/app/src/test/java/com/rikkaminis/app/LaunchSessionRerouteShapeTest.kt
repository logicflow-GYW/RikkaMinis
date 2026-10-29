package com.rikkaminis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [r5-d2 D-F-06] The New-Chat launch-mode reroute vs. everything that is not a
 * background→foreground transition.
 *
 * `KEY_LAUNCH_SESSION == 2` ("New Chat") re-honoured itself on every ON_START
 * after the first one, and the route it queued was executed in `onResume` with
 * `popUpTo(graph.startDestinationId) { inclusive = true }`. Two ON_STARTs are
 * not that transition:
 *
 *  - the return leg of an in-app sub-flow — camera / file picker / SAF / a
 *    settings page we launched. The user came back from the camera to a blank
 *    new chat: the conversation was gone and the draft they were typing into
 *    was orphaned.
 *  - a wake-up intent that already navigated somewhere (`minis://session/<id>`
 *    from a completion notification, or a share). The reroute landed on top of
 *    that navigation and replaced it with a draft — the deep link was never
 *    visible.
 *
 * Both are fixed with one-shot suppressors armed where the decision is actually
 * made (the launcher override / `onNewIntent`) and consumed by `onStart`. The
 * lifecycle answer itself cannot be exercised from a JVM test (it needs a real
 * Activity), so this reads the shipped `MainActivity.kt` — the house pattern for
 * lifecycle call sites (ScreenshotCaptureShapeTest / BrowserSessionBindingTest)
 * — and every probe carries an in-test negative control built from the same
 * source, so a probe that has quietly stopped looking fails loudly.
 */
class LaunchSessionRerouteShapeTest {

    private val activityRel =
        "src/android/app/src/main/java/com/rikkaminis/app/MainActivity.kt"

    private val guardNeedle = "selfLaunchedExternalUi || incomingWakeIntent"
    private val rerouteNeedle = "pendingLaunchSessionRoute = newRoute"
    private val resumedNeedle =
        "!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)"
    private val launcherMarker =
        "override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?)"

    // ── 1. the shipped source satisfies every anchor ──────────────────────

    @Test
    fun `shipped MainActivity passes every anchor`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        assertEquals(emptyList<String>(), check(code))
    }

    @Test
    fun `onStart keeps the reroute exactly once, after the guard`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        val onStart = fnBody(code, "fun onStart()")
        assertEquals(1, onStart.windowed(rerouteNeedle.length).count { it == rerouteNeedle })
        assertTrue(
            "the guard must still be reachable before the reroute",
            onStart.indexOf(guardNeedle) in 1 until onStart.indexOf(rerouteNeedle),
        )
    }

    // ── 2. negative controls ─────────────────────────────────────────────

    @Test
    fun `a guard that never fires is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        val mutated = code.replace(guardNeedle, "false")
        assertTrue(
            "expected the suppression guard to be reported missing",
            check(mutated).contains("onStart: no suppression guard"),
        )
    }

    @Test
    fun `a reroute queued before the guard is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        val line = code.lineSequence().first { rerouteNeedle in it }
        val moved = code.replace(line + "\n", "").replace(
            "if ($guardNeedle) {",
            line.trim() + "\n        if ($guardNeedle) {",
        )
        assertTrue(
            "expected the ordering violation to be reported",
            check(moved).contains("onStart: guard must precede the reroute"),
        )
    }

    @Test
    fun `onNewIntent that forgets to drop the queued route is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        val at = code.lastIndexOf("pendingLaunchSessionRoute = null")
        val mutated = code.substring(0, at) + "" + code.substring(at + "pendingLaunchSessionRoute = null".length)
        assertTrue(
            "expected the dropped-route anchor to be reported missing",
            check(mutated).contains("onNewIntent: queued reroute not dropped"),
        )
    }

    @Test
    fun `a wake-up suppressor armed while RESUMED is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        val mutated = code.replace(resumedNeedle, "true")
        assertTrue(
            "expected the resumed-arm violation to be reported",
            check(mutated).contains("onNewIntent: suppressor armed while already resumed"),
        )
    }

    @Test
    fun `a launcher override that arms before the launch is rejected`() {
        // Synthetic: the shipped source is only reordered here, and surgery on
        // two statement orderings is the mutation the probe exists to catch.
        val synthetic = """
            class Fake {
                $launcherMarker {
                    selfLaunchedExternalUi = true
                    super.startActivityForResult(intent, requestCode, options)
                }
            }
        """.trimIndent()
        assertTrue(
            "expected the arm-before-launch violation to be reported",
            check(synthetic).contains("launcher: flag armed before the launch"),
        )
    }

    @Test
    fun `a deleted back-stack wipe is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        val mutated = code.replace("popUpTo(nav.graph.startDestinationId) { inclusive = true }", "")
        assertTrue(
            "expected the missing popUpTo anchor to be reported",
            check(mutated).contains("onResume: back-stack wipe gone"),
        )
    }

    // ── the probe ───────────────────────────────────────────────────────

    /**
     * Every anchor, as a list of problem labels. Called with the shipped source
     * (expect empty) and with each mutation above (expect exactly that label).
     */
    private fun check(code: String): List<String> {
        val problems = mutableListOf<String>()
        val onStart = runCatching { fnBody(code, "fun onStart()") }.getOrNull()
        val onNewIntent = runCatching { fnBody(code, "override fun onNewIntent(intent: Intent)") }.getOrNull()
        val onResume = runCatching { fnBody(code, "fun onResume()") }.getOrNull()
        val launcher = runCatching { fnBody(code, launcherMarker) }.getOrNull()

        // ── onStart: the suppressor must exist, fire, and precede the queue ──
        val guardIdx = onStart?.indexOf(guardNeedle) ?: -1
        if (guardIdx < 0) problems += "onStart: no suppression guard"
        val rerouteIdx = onStart?.indexOf(rerouteNeedle) ?: -1
        if (onStart != null && rerouteIdx < 0) problems += "onStart: the mode-2 reroute is gone"
        if (guardIdx >= 0 && rerouteIdx >= 0) {
            if (guardIdx > rerouteIdx) {
                problems += "onStart: guard must precede the reroute"
            } else {
                val block = onStart!!.substring(guardIdx, rerouteIdx)
                if (!block.contains("selfLaunchedExternalUi = false")) {
                    problems += "onStart: sub-flow flag not consumed"
                }
                if (!block.contains("incomingWakeIntent = false")) {
                    problems += "onStart: wake-up flag not consumed"
                }
                if (!block.contains("pendingLaunchSessionRoute = null")) {
                    problems += "onStart: stale queued route survives the suppression"
                }
            }
        }
        if (onStart != null && guardIdx >= 0 && rerouteIdx >= 0) {
            val modeGate = onStart.indexOf("if (mode != 2) return")
            if (modeGate <= guardIdx || modeGate >= rerouteIdx) {
                problems += "onStart: mode gate must sit between the guard and the reroute"
            }
        }

        // ── onNewIntent: drop the queue, arm only while not resumed ──────────
        if (onNewIntent == null) {
            problems += "onNewIntent: missing"
        } else {
            val armed = onNewIntent.indexOf("incomingWakeIntent = true")
            if (armed < 0) problems += "onNewIntent: wake-up suppressor not armed"
            if (!onNewIntent.contains("pendingLaunchSessionRoute = null")) {
                problems += "onNewIntent: queued reroute not dropped"
            }
            val resumedIdx = onNewIntent.indexOf(resumedNeedle)
            if (armed >= 0 && (resumedIdx < 0 || resumedIdx > armed)) {
                problems += "onNewIntent: suppressor armed while already resumed"
            }
            if (!onNewIntent.contains("handleDeepLink(intent.data)")) {
                problems += "onNewIntent: deep-link handling dropped"
            }
        }

        // ── the launcher override: arm AFTER the launch succeeded ───────────
        if (launcher == null) {
            problems += "launcher: no startActivityForResult override"
        } else {
            val arm = launcher.indexOf("selfLaunchedExternalUi = true")
            val sup = launcher.indexOf("super.startActivityForResult(")
            if (arm < 0) {
                problems += "launcher: flag never armed"
            } else if (sup < 0 || arm < sup) {
                problems += "launcher: flag armed before the launch"
            }
        }

        // ── onResume: the reroute it executes must still wipe the stack ─────
        if (onResume == null || !onResume.contains("popUpTo(nav.graph.startDestinationId) { inclusive = true }")) {
            problems += "onResume: back-stack wipe gone"
        }
        return problems
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun readRepoFile(relative: String): String {
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

    private fun fnBody(code: String, marker: String): String {
        val start = code.indexOf(marker)
        if (start < 0) throw AssertionError("marker not found: $marker")
        val open = code.indexOf('{', start)
        if (open < 0) throw AssertionError("no body for: $marker")
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced body for: $marker")
    }

    /** Blanks comments and string/char literals, preserving offsets. */
    private fun stripCommentsAndLiterals(src: String): String {
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
                src.startsWith("\"\"\"", i) -> {
                    out.append("   ")
                    i += 3
                    while (i < n && !src.startsWith("\"\"\"", i)) {
                        out.append(if (src[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i + 2 < n) {
                        out.append("   ")
                        i += 3
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
