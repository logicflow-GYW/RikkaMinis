package com.rikkaminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-restricted-settings] / [T-android-downgrade-compat] wiring probes
 * for the two "MinisApp.onCreate skipped init" states.
 *
 * Both fixes are one-line guards on code paths the JVM unit test runtime cannot
 * reach: `ensureGrantOrPrompt` blocks on a real Settings.Secure read plus a
 * coroutine prompt, and `AgentForegroundService.onStartCommand` is a Service
 * lifecycle method. There is no Robolectric here, so the guards are asserted on
 * the compiled sources themselves — the same probe shape as
 * `DatabaseVersionGuardTest.locateDatabaseFile` — with the expected text written
 * as literals that no production constant is allowed to supply.
 *
 * The negative control mutates a copy of the real source (flips the gate
 * verdict) and runs the very same assertions on it. Without that control a
 * vanished guard would show up as a green test that no longer checks anything.
 */
class AppInitSkipGuardWiringTest {

    // ── the guarded text, written out in full ───────────────────────────────

    private val a11yFile = "src/main/java/com/rikkaminis/app/accessibility/AccessibilityRecoveryManager.kt"
    private val fgsFile = "src/main/java/com/rikkaminis/app/service/AgentForegroundService.kt"

    private val neverGrantedGate = "if (!hasEverBeenGranted(context)) return false"
    private val revokedCheck = "if (!isGrantRevoked(context)) return true"
    private val grantLatchWrite = "markGranted(context)"
    private val pendingPromptWrite = "_pendingPrompt.value ="
    private val revokedFlagWrite = "_revoked.value ="
    private val stubNotification = ".setContentTitle(\"RikkaMinis\")"
    private val stubUnwind = "return START_NOT_STICKY"
    /** FGS-DEADLINE-1116: the one exit both "we have nothing to do" paths use. */
    private val sharedStubExit = "satisfyForegroundDeadlineThenStop()"

    private val safeModeGuard = "if (isSafeMode() || isInitSkipped() || isDbGuidanceMode())"
    private val guidanceConstant = "SHOW_NEWER_DB_GUIDANCE"
    private val repositoryAccess = "backgroundSettingsRepository"

    // ── the two gate checks, as functions so they can be reused as controls ──

    /** The gate must exist exactly once, and it must run before the revoked check. */
    private fun assertGatePresent(body: String) {
        val norm = normalize(body)
        assertEquals(
            "the ever-granted gate must exist exactly once in ensureGrantOrPrompt:\n$body",
            1,
            occurrences(norm, neverGrantedGate),
        )
        val gateIdx = norm.indexOf(neverGrantedGate)
        val revokedIdx = norm.indexOf(revokedCheck)
        assertTrue(
            "the revoked check must still be present after the gate:\n$body",
            revokedIdx >= 0,
        )
        assertTrue(
            "the gate must short-circuit before the revoked check (gate@$gateIdx, revoked@$revokedIdx)",
            gateIdx < revokedIdx,
        )
    }

    /** The gate must be a bare false return that mutates no prompt state. */
    private fun assertGateMutatesNothing(body: String) {
        val statement = gateStatement(body)
        assertNotNull(
            "the ever-granted gate is missing from ensureGrantOrPrompt:\n$body",
            statement,
        )
        assertEquals(
            "the gate must be a bare `return false` statement — no dialog, no latch and no " +
                "Settings flag write may be reachable from the never-granted path, where the only " +
                "observable behaviour is the caller's normal error",
            "if (!hasEverBeenGranted(context)) return false",
            statement!!.trim().removeSuffix(";"),
        )
        val gateAt = body.indexOf(neverGrantedGate)
        val firstWrite = firstStateWritePastGate(body)
        assertTrue(
            "no prompt state may be set before the guard (gate@$gateAt, first write@$firstWrite)",
            gateAt < 0 || firstWrite > gateAt,
        )
        assertFalse(
            "the tool path must never latch the grant itself — only the service connect does",
            body.contains(grantLatchWrite),
        )
    }

    // ── negative control ─────────────────────────────────────────────────────

    /**
     * Proves this file can actually fail. Both controls are run against a copy
     * of the real source with the gate edited out; without that arm a vanished
     * guard would read as a green test that no longer checks anything.
     */
    @Test
    fun `gate checks reject a source that lost the never-granted gate`() {
        // Flipping the verdict is the mutation this gate exists to prevent:
        // never-granted would read as "the grant was revoked" and prompt.
        val mangled = normalize(
            ensureGrantOrPromptBody().replace(neverGrantedGate, "if (!hasEverBeenGranted(context)) return true"),
        )
        assertFalse(
            "the mutation must really have replaced the gate — otherwise this control proves nothing",
            mangled.contains(neverGrantedGate),
        )
        val thrown = mutableListOf<String>()
        try {
            assertGatePresent(mangled)
        } catch (e: AssertionError) {
            thrown += "present"
        }
        try {
            assertGateMutatesNothing(mangled)
        } catch (e: AssertionError) {
            thrown += "bare-return"
        }
        assertEquals(
            "both gate assertions must reject the mutated source",
            "present, bare-return",
            thrown.joinToString(", "),
        )
    }

    /** The whole statement the gate sits in — comments and strings ignored. */
    private fun gateStatement(body: String): String? {
        val idx = body.indexOf(neverGrantedGate)
        if (idx < 0) return null
        var lineComment = false
        var blockComment = false
        var inString = false
        var escaped = false
        for (i in idx until body.length) {
            val c = body[i]
            if (lineComment) { if (c.code == 10) lineComment = false; continue }
            if (blockComment) {
                if (c == '*' && body.getOrNull(i + 1) == '/') blockComment = false
                continue
            }
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when {
                c == '/' && body.getOrNull(i + 1) == '/' -> lineComment = true
                c == '/' && body.getOrNull(i + 1) == '*' -> blockComment = true
                c == '"' -> inString = true
                c == ';' -> return body.substring(idx, i + 1).trim()
                // A statement without a terminator still ends at its newline.
                c.code == 10 -> if (i > idx) return body.substring(idx, i).trim()
            }
        }
        return body.substring(idx).trim()
    }

    /** The first prompt-state write after the gate; -1 when there is none. */
    private fun firstStateWritePastGate(body: String): Int {
        val gateStart = body.indexOf(neverGrantedGate)
        if (gateStart < 0) return -1
        val after = body.substring(gateStart + neverGrantedGate.length)
        return listOf(pendingPromptWrite, revokedFlagWrite)
            .map { after.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: -1
    }

    // ── finding 1: the tool path must not prompt on a never-granted device ────

    @Test
    fun `tool path returns false before prompting when the grant was never latched`() {
        assertGatePresent(ensureGrantOrPromptBody())
    }

    @Test
    fun `the never-granted return is a bare false return that mutates nothing`() {
        assertGateMutatesNothing(ensureGrantOrPromptBody())
    }

    // ── finding 2: the guidance skip must reach the same stub as safe mode ────

    /**
     * The init-skip branch of a lifecycle method: everything between its guard
     * condition and the `}` that closes it.
     */
    private fun initSkipBranch(body: String, guard: String): String {
        val guardIdx = body.indexOf(guard)
        val branchEnd = matchingBrace(body, body.indexOf('{', guardIdx))
        assertTrue("no branch closing brace after the guard", branchEnd > guardIdx)
        return body.substring(guardIdx, branchEnd)
    }

    @Test
    fun `guidance shares the safe-mode bail out in onCreate`() {
        val body = onCreateSource()
        val branch = initSkipBranch(body, safeModeGuard)
        assertEquals(
            "onCreate must have exactly one init-skip guard so both states share it:\n<body>",
            1,
            occurrences(branch, safeModeGuard),
        )
        assertTrue(
            "safe mode must still take that same bail-out\n<body>",
            branch.contains("isSafeMode()"),
        )
        assertTrue(
            "the one-way init-skip latch must stay in the guard — finishClose clears " +
                "_safeMode while this process has still skipped init\n<body>",
            branch.contains("isInitSkipped()"),
        )
        assertFalse(
            "the guidance/safe-mode path must not build the real notification\n<body>",
            branch.contains("buildNotification"),
        )
        // The guidance read must be spelled against the real enum constant.
        val helper = functionBody(fgsSource(), "private fun isDbGuidanceMode()")
        assertTrue(
            "isDbGuidanceMode must read MinisApp.dbVersionDecision against $guidanceConstant",
            helper.contains("dbVersionDecision") && helper.contains(guidanceConstant),
        )
    }

    @Test
    fun `guidance takes the stub notification unwind in onStartCommand`() {
        val body = onStartCommandSource()
        val branch = initSkipBranch(body, safeModeGuard)
        assertEquals(
            "onStartCommand must reuse the existing safe-mode stub branch\n<body>",
            1,
            occurrences(branch, safeModeGuard),
        )
        assertEquals(
            "the branch must unwind through the shared stub-and-stop exit — the stub moved " +
                "there so safe mode and the swipe-from-recents path cannot drift apart " +
                "(FGS-DEADLINE-1116 re-anchored this assertion)",
            1,
            occurrences(branch, sharedStubExit),
        )
        assertFalse(
            "the stub notification must not be built inline a second time — that duplication is " +
                "exactly what let the other caller keep a bare stopSelf()\n<branch>",
            branch.contains(stubNotification),
        )
        assertEquals(
            "the branch must return START_NOT_STICKY",
            1,
            occurrences(branch, stubUnwind),
        )
        assertFalse(
            "guidance must not build the real notification from un-initialised state\n<body>",
            branch.contains("buildNotification"),
        )
        assertTrue(
            "the normal path must keep building the real notification",
            body.indexOf("buildNotification") > body.indexOf(safeModeGuard),
        )
    }

    /**
     * FGS-DEADLINE-1116 moved the stub out of `onStartCommand`; this keeps the
     * original intent ("the degraded modes still attach a notification") pinned
     * at the new home, so moving it again cannot silently drop the deadline.
     */
    @Test
    fun `the shared stub-and-stop exit still builds the stub notification`() {
        val shared = functionBody(fgsSource(), "private fun satisfyForegroundDeadlineThenStop()")
        assertEquals(
            "the shared exit is the only place the stub is built now — if it stops building one, " +
                "both callers lose the 5-second deadline and the process is killed at expiry\n$shared",
            1,
            occurrences(shared, stubNotification),
        )
    }

    @Test
    fun `the guidance branch is evaluated before the first repository read or buildNotification`() {
        val body = onStartCommandSource()
        val guardIdx = body.indexOf(safeModeGuard)
        val branchEnd = matchingBrace(body, body.indexOf('{', guardIdx))
        assertTrue("guard expected in onStartCommand", guardIdx >= 0)
        for (needle in listOf(repositoryAccess, "buildNotification")) {
            val hit = body.indexOf(needle, guardIdx)
            assertTrue(
                "'$needle' must stay out of the guidance branch (guard@$guardIdx, hit@$hit, branch ends@$branchEnd)",
                hit < 0 || hit > branchEnd,
            )
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun collapse(text: String) = text.replace(Regex("""\s+"""), " ").trim()

    /** Drop comments, then collapse whitespace so multi-line guards match one literal. */
    private fun normalize(text: String): String {
        val noComments = text
            .replace(Regex("""//[^\n]*"""), " ")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        return collapse(noComments)
    }

    private fun occurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (from <= text.length - needle.length) {
            val hit = text.indexOf(needle, from)
            if (hit < 0) return count
            count++
            from = hit + 1
        }
        return count
    }

    /** The body of [start)..[closeIndex), where [closeIndex] > [start] > -1. */
    private fun bodyBetween(text: String, start: Int, closeIndex: Int): String {
        assertTrue("no balanced close after $start", closeIndex > start)
        return text.substring(start, closeIndex)
    }

    /** Whole function: braces balanced from the first `{` to its matching `}`. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(
            "could not find '$signature'\n${source.take(400)}",
            start >= 0,
        )
        val openIdx = source.indexOf('{', start)
        assertTrue("no body opening for '$signature'", openIdx >= 0)
        return bodyBetween(source, start, matchingBrace(source, openIdx))
    }

    private fun onStartCommandSource(): String = functionBody(fgsSource(), "override fun onStartCommand")

    private fun onCreateSource(): String = functionBody(fgsSource(), "override fun onCreate()")

    private fun ensureGrantOrPromptBody(): String =
        functionBody(a11ySource(), "suspend fun ensureGrantOrPrompt")

    /** Index of the `}` matching the `{` at [openIndex], or -1. */
    private fun matchingBrace(text: String, openIndex: Int): Int {
        var depth = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var escaped = false
        for (i in openIndex until text.length) {
            val c = text[i]
            if (inLineComment) {
                if (c == '\n') inLineComment = false
                continue
            }
            if (inBlockComment) {
                if (c == '*' && text.getOrNull(i + 1) == '/') inBlockComment = false
                continue
            }
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when {
                c == '/' && text.getOrNull(i + 1) == '/' -> inLineComment = true
                c == '/' && text.getOrNull(i + 1) == '*' -> inBlockComment = true
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    // ── source location (same technique as DatabaseVersionGuardTest) ──────────

    private fun a11ySource(): String = sourceFile(a11yFile).readText()
    private fun fgsSource(): String = sourceFile(fgsFile).readText()

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
}
