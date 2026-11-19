package com.rikkaminis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [R2 F-11] The `BadTokenException` crash on background-tool prompts.
 *
 * `MainActivity.onCreate` collected two prompt flows
 * (`OffloadPermissionManager.pendingSettingsGate` at `:392` and
 * `AccessibilityRecoveryManager.pendingPrompt` at `:439`) inside a bare
 * `lifecycleScope.launch` and called `AlertDialog.show()` unconditionally.
 * `lifecycleScope` is only cancelled at ON_DESTROY, so an event landing while
 * the Activity is finishing / destroyed — or in the window between the window
 * token dying and the scope being cancelled — reaches `show()` on a dead token
 * and the platform throws `WindowManager.BadTokenException` on the main thread:
 * process death, plus a stalled agent turn waiting on a prompt nobody answered.
 *
 * Both halves are covered:
 *
 * 1. **Behaviour** — [DialogShowGate] is plain Kotlin, so the truth table is
 *    exercised directly. Both sides matter: a dead window must not show, and a
 *    live one must still show (deleting the feature is not a fix).
 * 2. **Wiring** — both call sites are pinned on the real `MainActivity.kt`.
 *    **Two** sites, because fixing one of the two leaves the crash reachable.
 */
class DialogShowGateTest {

    private val activityRel =
        "src/android/app/src/main/java/com/rikkaminis/app/MainActivity.kt"

    // ── 1. behaviour ────────────────────────────────────────────────────────

    @Test
    fun `a resumed activity shows the dialog`() {
        assertTrue(
            DialogShowGate.shouldShow(
                isFinishing = false,
                isDestroyed = false,
                startedOrBeyond = true,
            ),
        )
    }

    @Test
    fun `a finishing activity never shows a dialog`() {
        assertFalse(
            DialogShowGate.shouldShow(
                isFinishing = true,
                isDestroyed = false,
                startedOrBeyond = true,
            ),
        )
    }

    @Test
    fun `a destroyed activity never shows a dialog`() {
        assertFalse(
            DialogShowGate.shouldShow(
                isFinishing = false,
                isDestroyed = true,
                startedOrBeyond = true,
            ),
        )
    }

    @Test
    fun `a stopped activity never shows a dialog`() {
        // Home / rotation window: no window token is attached, so show() would
        // be handed a dead one.
        assertFalse(
            DialogShowGate.shouldShow(
                isFinishing = false,
                isDestroyed = false,
                startedOrBeyond = false,
            ),
        )
    }

    @Test
    fun `every single failure is enough to refuse`() {
        // Exhaustive: exactly one row (all clear) may return true.
        var allowed = 0
        for (finishing in listOf(false, true)) {
            for (destroyed in listOf(false, true)) {
                for (started in listOf(false, true)) {
                    if (DialogShowGate.shouldShow(finishing, destroyed, started)) allowed++
                }
            }
        }
        assertEquals("exactly one row of the truth table may show a dialog", 1, allowed)
    }

    // ── 2. wiring ───────────────────────────────────────────────────────────

    @Test
    fun `the shipped activity satisfies every wiring anchor`() {
        assertEquals(emptyList<String>(), check(stripCommentsAndLiterals(readRepoFile(activityRel))))
    }

    // ── negative controls ───────────────────────────────────────────────────

    @Test
    fun `a collector without repeatOnLifecycle is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        val mutated = code.replace(
            "repeatOnLifecycle(Lifecycle.State.STARTED)",
            "runOnceNoLifecycleGate",
        )
        val problems = check(mutated)
        assertTrue(
            "both collectors must be re-anchored, got $problems",
            problems.any { it.startsWith("collector: not scoped to repeatOnLifecycle") },
        )
    }

    @Test
    fun `a flow whose dialog skips the gate is rejected`() {
        // Synthetic fixture, not the shipped file: a control must keep working
        // when the *other* site is mutated, otherwise "which arm went red"
        // stops being attributable. Same stance as
        // LaunchSessionRerouteShapeTest's synthetic launcher arm.
        val problems = check(gateFixture(guardedFirst = false, guardedSecond = true))
        assertTrue(
            "expected the first flow to be reported unguarded, got $problems",
            problems.any {
                it.startsWith(
                    "site[OffloadPermissionManager.pendingSettingsGate]: dialog built without DialogShowGate",
                )
            },
        )
    }

    @Test
    fun `dropping the gate from the second site is rejected`() {
        // The exact "only fixed one of the two" regression.
        val problems = check(gateFixture(guardedFirst = true, guardedSecond = false))
        assertTrue(
            "expected the second flow to be reported unguarded, got $problems",
            problems.any {
                it.startsWith(
                    "site[AccessibilityRecoveryManager.pendingPrompt]: dialog built without DialogShowGate",
                )
            },
        )
    }

    @Test
    fun `deferring must not answer the prompt`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        val body = fnBody(code, "repeatOnLifecycle(Lifecycle.State.STARTED) {")
        // The defer branch is the `if (!DialogShowGate.shouldShow(...)) { … }`
        // block; injecting a CANCEL there is what "silently drop the prompt"
        // would look like.
        val mutated = body.replaceFirst(
            "return@collect",
            "OffloadPermissionManager.respondToSettingsGate(" +
                "OffloadPermissionManager.SettingsGateDecision.CANCEL)\n                            return@collect",
        )
        val problems = check(code.replace(body, mutated))
        assertTrue(
            "expected the silent-drop violation, got $problems",
            problems.any { it.startsWith("defer: prompt answered instead of kept pending") },
        )
    }

    @Test
    fun `an unguarded show is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        val mutated = code.replace("dialog.show()", "dialog.showNowUnguarded()")
        val problems = check(mutated)
        assertTrue(
            "both show sites must be inside a try, got $problems",
            problems.any { it.startsWith("show: unguarded") },
        )
    }

    @Test
    fun `onDestroy that forgets the dialog is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(activityRel))
        val mutated = code.replace("activePermissionDialog?.let { d ->", "unrelatedField?.let { d ->")
        val problems = check(mutated)
        assertTrue(
            "expected the leaked-dialog violation, got $problems",
            problems.any { it.startsWith("onDestroy: permission dialog not dismissed") },
        )
    }

    // ── the probe ───────────────────────────────────────────────────────────

    private fun check(code: String): List<String> {
        val problems = mutableListOf<String>()

        // ── both collectors must be lifecycle-scoped ─────────────────────────
        val collectorGate = "repeatOnLifecycle(Lifecycle.State.STARTED)"
        val collectorCount = occurrences(code, collectorGate)
        if (collectorCount != 2) {
            problems += "collector: not scoped to repeatOnLifecycle (found $collectorCount of 2)"
        }

        // ── each flow's dialog must sit behind a *complete* gate ────────────
        // Checking each flow independently (not "there are two calls
        // somewhere") is what makes the half-fix — one site guarded, the other
        // not — fail on the *unguarded* site instead of passing on the count.
        val gateCall = "DialogShowGate.shouldShow("
        val buildNeedle = "AlertDialog.Builder(this@MainActivity)"
        val flows = listOf(
            "OffloadPermissionManager.pendingSettingsGate",
            "AccessibilityRecoveryManager.pendingPrompt",
        )
        for (flow in flows) {
            val flowAt = code.indexOf(flow)
            if (flowAt < 0) {
                problems += "site[$flow]: flow not collected"
                continue
            }
            val buildAt = code.indexOf(buildNeedle, flowAt)
            val gateAt = code.indexOf(gateCall, flowAt)
            if (buildAt < 0) {
                problems += "site[$flow]: dialog build missing"
                continue
            }
            if (gateAt < 0 || gateAt > buildAt) {
                problems += "site[$flow]: dialog built without DialogShowGate"
                continue
            }
            val argsEnd = matchingParen(code, gateAt + gateCall.length - 1)
            if (argsEnd < 0) {
                problems += "site[$flow]: unbalanced DialogShowGate call"
                continue
            }
            val args = code.substring(gateAt, argsEnd + 1)
            for (input in listOf("isFinishing", "isDestroyed", "lifecycle.currentState")) {
                if (!args.contains(input)) {
                    problems += "site[$flow]: gate input $input replaced by a constant"
                }
            }
            if (!code.startsWith("if (!", gateAt - "if (!".length)) {
                problems += "site[$flow]: gate result not used as a guard"
            }
            val branchOpen = code.indexOf('{', argsEnd)
            val branchEnd = if (branchOpen >= 0) matchingBrace(code, branchOpen) else -1
            if (branchEnd < 0 || !code.substring(branchOpen, branchEnd).contains("return@collect")) {
                problems += "site[$flow]: the refused branch does not defer"
            }
        }
        if (occurrences(code, buildNeedle) != 2) {
            problems += "site: expected two dialog builds, found ${occurrences(code, buildNeedle)}"
        }

        // ── a deferred prompt must stay pending ──────────────────────────────
        for (needle in listOf("OffloadPermissionManager.pendingSettingsGate", "AccessibilityRecoveryManager.pendingPrompt")) {
            val at = code.indexOf(needle)
            if (at < 0) {
                problems += "collector: $needle not collected"
                continue
            }
            val deferAt = code.indexOf("return@collect", at)
            if (deferAt < 0) {
                problems += "defer: no deferred branch after $needle"
                continue
            }
            val window = code.substring(at, deferAt)
            if (window.contains("respondToSettingsGate(") || window.contains("AccessibilityRecoveryManager.respond(")) {
                problems += "defer: prompt answered instead of kept pending"
            }
        }

        // ── every show() is inside a try/catch ──────────────────────────────
        var from = 0
        var showCount = 0
        while (true) {
            val at = code.indexOf("dialog.show()", from)
            if (at < 0) break
            showCount++
            from = at + 1
            val before = code.substring(maxOf(0, at - 200), at)
            val after = code.substring(at, minOf(code.length, at + 400))
            if (!before.contains("try {") || !after.contains("catch (t: Throwable)")) {
                problems += "show: unguarded"
            }
        }
        if (showCount != 2) {
            problems += "show: unguarded ($showCount of 2 show sites present)"
        }

        // ── the held dialog is released on destroy ──────────────────────────
        val onDestroy = runCatching { fnBody(code, "override fun onDestroy()") }.getOrNull()
        if (onDestroy == null) {
            problems += "onDestroy: missing"
        } else {
            val releaseAt = onDestroy.indexOf("activePermissionDialog?.let")
            if (releaseAt < 0) {
                problems += "onDestroy: permission dialog not dismissed"
            } else {
                val open = onDestroy.indexOf('{', releaseAt)
                val close = if (open >= 0) matchingBrace(onDestroy, open) else -1
                if (close < 0 || !onDestroy.substring(open, close).contains("dismiss()")) {
                    problems += "onDestroy: permission dialog not dismissed"
                }
            }
            if (!onDestroy.contains("activePermissionDialog = null")) {
                problems += "onDestroy: the held reference is not cleared"
            }
        }

        // ── the gate itself keeps all three conditions ──────────────────────
        val gateBody = runCatching { fnBody(code, "internal object DialogShowGate") }.getOrNull()
        if (gateBody == null) {
            problems += "gate: DialogShowGate missing"
        } else {
            for (cond in listOf("!isFinishing", "!isDestroyed", "startedOrBeyond")) {
                if (!gateBody.contains(cond)) {
                    problems += "gate: missing condition $cond"
                }
            }
        }
        return problems
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val hit = text.indexOf(needle, from)
            if (hit < 0) return count
            count++
            from = hit + needle.length
        }
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
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

    /**
     * A minimal source with the shipped shape: two prompt flows, each collected
     * under `repeatOnLifecycle(STARTED)` behind a [DialogShowGate] guard, each
     * showing its dialog inside a try, plus the onDestroy release. Either guard
     * can be dropped, which is the mutation the probes must catch.
     */
    private fun gateFixture(guardedFirst: Boolean, guardedSecond: Boolean): String {
        fun site(flow: String, guarded: Boolean, build: String): String {
            val guard = if (guarded) {
                """
                        if (!DialogShowGate.shouldShow(
                                isFinishing = isFinishing,
                                isDestroyed = isDestroyed,
                                startedOrBeyond = lifecycle.currentState
                                    .isAtLeast(Lifecycle.State.STARTED),
                            )
                        ) {
                            return@collect
                        }
                """.trimIndent()
            } else {
                ""
            }
            return """
                    lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            $flow
                                .filterNotNull()
                                .collect { item ->
                $guard
                                    val dialog = $build
                                        .setTitle(item.title)
                                        .create()
                                    activePermissionDialog = dialog
                                    try {
                                        dialog.show()
                                    } catch (t: Throwable) {
                                        runCatching { dialog.dismiss() }
                                    }
                                }
                        }
                    }
            """.trimIndent()
        }

        return listOf(
            site(
                "OffloadPermissionManager.pendingSettingsGate",
                guardedFirst,
                "AlertDialog.Builder(this@MainActivity)",
            ),
            site(
                "AccessibilityRecoveryManager.pendingPrompt",
                guardedSecond,
                "AlertDialog.Builder(this@MainActivity)",
            ),
            """
                override fun onDestroy() {
                    activePermissionDialog?.let { d ->
                        runCatching { if (d.isShowing) d.dismiss() }
                    }
                    activePermissionDialog = null
                    super.onDestroy()
                }
            """.trimIndent(),
            "internal object DialogShowGate {",
            "    fun shouldShow(",
            "        isFinishing: Boolean,",
            "        isDestroyed: Boolean,",
            "        startedOrBeyond: Boolean,",
            "    ): Boolean = !isFinishing && !isDestroyed && startedOrBeyond",
            "}",
        ).joinToString("\n")
    }

    /** Index of the `)` matching the `(` at [open]. */
    private fun matchingParen(code: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** Index of the `}` matching the `{` at [open]. */
    private fun matchingBrace(code: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** Blanks comments and string literals, preserving offsets. */
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
                c == '"' -> {
                    out.append(' ')
                    i++
                    while (i < n && src[i] != '"') {
                        if (src[i] == '\\') {
                            out.append(' ')
                            i++
                            if (i < n) {
                                out.append(' ')
                                i++
                            }
                            continue
                        }
                        out.append(if (src[i] == '\n') '\n' else ' ')
                        i++
                    }
                    if (i < n) {
                        out.append(' ')
                        i++
                    }
                }
                c == '\'' -> {
                    out.append(' ')
                    i++
                    while (i < n && src[i] != '\'') {
                        if (src[i] == '\\') {
                            out.append(' ')
                            i++
                        }
                        out.append(' ')
                        i++
                    }
                    if (i < n) {
                        out.append(' ')
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
