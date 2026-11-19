package com.rikkaminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [R2 F-10] The sticky-restart crash loop in `AgentForegroundService`.
 *
 * A `START_STICKY` service killed by the LMK is re-created by the system in the
 * **background** with `intent == null`. The shipped code fell through to an
 * unguarded `startForeground(...)` and returned `START_STICKY` again, so on
 * Android 12+ the platform threw `ForegroundServiceStartNotAllowedException`,
 * the process died, and the system re-created it — a crash loop on the *daily*
 * path (long agent turns are what the LMK reclaims). Every round also feeds
 * `CrashFrequencyDetector`, so the next launch landed in safe mode.
 *
 * Two halves, because the defect had two:
 *
 * 1. **Behaviour** — [ForegroundStartPolicy] is plain Kotlin, so the decision
 *    matrix is exercised directly. The arms that matter are BOTH sides: a
 *    background restart with nothing to show must not attach, and an app-side
 *    start (or a restart with a live turn in front of the user) must still
 *    attach — deleting the feature is not a fix.
 * 2. **Wiring** — `onStartCommand` / `onTaskRemoved` / `startService` live in an
 *    Android `Service`, which the JVM cannot instantiate, so the call sites are
 *    asserted on the real sources (the house probe shape). Every arm has a
 *    negative control that mutates a copy of the shipped text.
 */
class ForegroundStartPolicyTest {

    private val fgsRel =
        "src/android/app/src/main/java/com/rikkaminis/app/service/AgentForegroundService.kt"

    // ── 1. behaviour: the decision matrix ────────────────────────────────────

    @Test
    fun `a null intent is a system restart and an explicit start is not`() {
        assertEquals(
            ForegroundStartPolicy.Origin.SYSTEM_RESTART,
            ForegroundStartPolicy.origin(intentPresent = false),
        )
        assertEquals(
            ForegroundStartPolicy.Origin.APP_START,
            ForegroundStartPolicy.origin(intentPresent = true),
        )
    }

    @Test
    fun `a background restart with no live work unwinds instead of attaching`() {
        assertEquals(
            ForegroundStartPolicy.Decision.STUB_THEN_STOP,
            ForegroundStartPolicy.decide(
                origin = ForegroundStartPolicy.Origin.SYSTEM_RESTART,
                appForeground = false,
                hasLiveWork = false,
            ),
        )
    }

    @Test
    fun `a background restart with a surviving turn still unwinds - the attach would be refused`() {
        // The process survived an OEM service kill with the turn still running,
        // but no window is visible: `startForeground` is illegal here regardless
        // of whether there is work to show.
        assertEquals(
            ForegroundStartPolicy.Decision.STUB_THEN_STOP,
            ForegroundStartPolicy.decide(
                origin = ForegroundStartPolicy.Origin.SYSTEM_RESTART,
                appForeground = false,
                hasLiveWork = true,
            ),
        )
    }

    @Test
    fun `a foreground restart with nothing left to show unwinds`() {
        // Fresh process: the tracker's in-memory sets are empty by construction,
        // so a status row would describe nothing.
        assertEquals(
            ForegroundStartPolicy.Decision.STUB_THEN_STOP,
            ForegroundStartPolicy.decide(
                origin = ForegroundStartPolicy.Origin.SYSTEM_RESTART,
                appForeground = true,
                hasLiveWork = false,
            ),
        )
    }

    @Test
    fun `a foreground restart with a live turn keeps its row`() {
        // The OEM-service-kill case: the process is alive, the turn is running,
        // the user is looking at the app. Attaching is legal and meaningful.
        assertEquals(
            ForegroundStartPolicy.Decision.ATTACH_STATUS,
            ForegroundStartPolicy.decide(
                origin = ForegroundStartPolicy.Origin.SYSTEM_RESTART,
                appForeground = true,
                hasLiveWork = true,
            ),
        )
    }

    @Test
    fun `an app start always attaches - the user asked for it`() {
        for (appForeground in listOf(true, false)) {
            for (hasLiveWork in listOf(true, false)) {
                assertEquals(
                    "app start must attach (appForeground=$appForeground hasLiveWork=$hasLiveWork)",
                    ForegroundStartPolicy.Decision.ATTACH_STATUS,
                    ForegroundStartPolicy.decide(
                        origin = ForegroundStartPolicy.Origin.APP_START,
                        appForeground = appForeground,
                        hasLiveWork = hasLiveWork,
                    ),
                )
            }
        }
    }

    // ── 2. wiring: the shipped service ───────────────────────────────────────

    @Test
    fun `the shipped service satisfies every wiring anchor`() {
        assertEquals(emptyList<String>(), check(stripCommentsAndLiterals(readRepoFile(fgsRel))))
    }

    // ── negative controls (one per arm) ──────────────────────────────────────

    @Test
    fun `returning START_STICKY again is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(fgsRel))
        val body = fnBody(code, "override fun onStartCommand")
        assertTrue("the normal path must end in START_NOT_STICKY", body.contains("return START_NOT_STICKY"))
        // Re-introduce the fuel: the last return of the method becomes sticky.
        val at = body.lastIndexOf("return START_NOT_STICKY")
        val mutated = body.substring(0, at) + "return START_STICKY" +
            body.substring(at + "return START_NOT_STICKY".length)
        val problems = check(code.replace(body, mutated))
        assertTrue(
            "expected the sticky-return violation, got $problems",
            problems.any { it.startsWith("onStartCommand: sticky restart re-armed") },
        )
    }

    @Test
    fun `a restart that never consults the policy is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(fgsRel))
        val body = fnBody(code, "override fun onStartCommand")
        val mutated = body.replace("ForegroundStartPolicy.decide(", "decideNeverCalled(")
        val problems = check(code.replace(body, mutated))
        assertTrue(
            "expected the missing-decision violation, got $problems",
            problems.any { it.startsWith("onStartCommand: no policy decision") },
        )
    }

    @Test
    fun `an unguarded startForeground in onStartCommand is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(fgsRel))
        val body = fnBody(code, "override fun onStartCommand")
        val mutated = body.replace("attachForegroundOrGiveUp(", "startForegroundUnguarded(")
        val problems = check(code.replace(body, mutated))
        assertTrue(
            "expected the unguarded-attach violation, got $problems",
            problems.any { it.startsWith("onStartCommand: startForeground called outside") },
        )
    }

    @Test
    fun `an unguarded startForeground in onTaskRemoved is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(fgsRel))
        val body = fnBody(code, "override fun onTaskRemoved")
        val mutated = body.replace("attachForegroundOrGiveUp(", "startForegroundUnguarded(")
        val problems = check(code.replace(body, mutated))
        assertTrue(
            "expected the task-removed violation, got $problems",
            problems.any { it.startsWith("onTaskRemoved: startForeground called outside") },
        )
    }

    @Test
    fun `an unguarded startForegroundService in startService is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(fgsRel))
        val body = fnBody(code, "fun startService(context: Context")
        val mutated = body.replace("try {", "").replace("} catch (t: Throwable) {", "} else {")
        val problems = check(code.replace(body, mutated))
        assertTrue(
            "expected the startService violation, got $problems",
            problems.any { it.startsWith("startService: startForegroundService unguarded") },
        )
    }

    @Test
    fun `a catch that stops but does not log is rejected`() {
        val code = stripCommentsAndLiterals(readRepoFile(fgsRel))
        val body = declBody(code, "private fun attachForegroundOrGiveUp(")
        val mutated = body.replace("Log.w(", "noLogAtAll(")
        val problems = check(code.replace(body, mutated))
        assertTrue(
            "expected the missing-log violation, got $problems",
            problems.any { it.startsWith("attachForegroundOrGiveUp: refusal not logged") },
        )
    }

    @Test
    fun `a policy that attaches on a bare restart is rejected`() {
        // The other direction of the same bug: keeping the guard but making it
        // always attach would restore the crash while looking "handled".
        val code = stripCommentsAndLiterals(readRepoFile(fgsRel))
        val policy = objectBody(code, "internal object ForegroundStartPolicy")
        val mutated = policy.replace(
            "else -> Decision.STUB_THEN_STOP",
            "else -> Decision.ATTACH_STATUS",
        )
        assertTrue("the mutation must really change the decision", mutated != policy)
        val problems = check(code.replace(policy, mutated))
        assertTrue(
            "expected the always-attach violation, got $problems",
            problems.any { it.startsWith("policy: restart without a user attaches") },
        )
    }

    // ── the probe ────────────────────────────────────────────────────────────

    private fun check(code: String): List<String> {
        val problems = mutableListOf<String>()
        val onStart = runCatching { fnBody(code, "override fun onStartCommand") }.getOrNull()
        val onTaskRemoved = runCatching { fnBody(code, "override fun onTaskRemoved") }.getOrNull()
        val startService = runCatching { fnBody(code, "fun startService(context: Context") }.getOrNull()
        val attach = runCatching { declBody(code, "private fun attachForegroundOrGiveUp(") }.getOrNull()
        val policy = runCatching { objectBody(code, "internal object ForegroundStartPolicy") }.getOrNull()

        // ── onStartCommand: decide, then attach through the guarded helper ──
        if (onStart == null) {
            problems += "onStartCommand: missing"
        } else {
            if (!onStart.contains("ForegroundStartPolicy.decide(")) {
                problems += "onStartCommand: no policy decision"
            }
            if (!onStart.contains("ForegroundStartPolicy.Decision.STUB_THEN_STOP")) {
                problems += "onStartCommand: the unwind branch is gone"
            }
            if (!onStart.contains("attachForegroundOrGiveUp(")) {
                problems += "onStartCommand: startForeground called outside the guarded helper"
            }
            val tail = onStart.substringAfterLast("foregroundStarted = true")
            if (tail.contains("return START_STICKY")) {
                problems += "onStartCommand: sticky restart re-armed (the loop's fuel)"
            }
            if (!tail.contains("return START_NOT_STICKY")) {
                problems += "onStartCommand: the normal path does not report START_NOT_STICKY"
            }
            // The unwind must come before the real attach, otherwise a restart
            // still reaches startForeground first.
            val unwindAt = onStart.indexOf("Decision.STUB_THEN_STOP")
            val attachAt = onStart.indexOf("attachForegroundOrGiveUp(")
            if (unwindAt >= 0 && attachAt >= 0 && unwindAt > attachAt) {
                problems += "onStartCommand: the unwind branch sits after the attach"
            }
        }

        // ── onTaskRemoved: same guarded attach ──────────────────────────────
        if (onTaskRemoved == null) {
            problems += "onTaskRemoved: missing"
        } else {
            if (!onTaskRemoved.contains("attachForegroundOrGiveUp(")) {
                problems += "onTaskRemoved: startForeground called outside the guarded helper"
            }
        }

        // ── startService: the platform call is absorbed ──────────────────────
        if (startService == null) {
            problems += "startService: missing"
        } else {
            val guarded = startService.contains("try {") && startService.contains("catch (t: Throwable)")
            if (!guarded || !startService.contains("startForegroundService(")) {
                problems += "startService: startForegroundService unguarded"
            }
        }

        // ── the guarded helper itself ───────────────────────────────────────
        if (attach == null) {
            problems += "attachForegroundOrGiveUp: missing"
        } else {
            if (!attach.contains("startForeground(")) {
                problems += "attachForegroundOrGiveUp: no attach at all"
            }
            if (!attach.contains("catch (t: Throwable)")) {
                problems += "attachForegroundOrGiveUp: refusal not absorbed"
            }
            if (!attach.contains("Log.w(")) {
                problems += "attachForegroundOrGiveUp: refusal not logged"
            }
            if (!attach.contains("stopSelf()")) {
                problems += "attachForegroundOrGiveUp: a refused attach leaves the service up"
            }
        }

        // ── the policy's own shape ──────────────────────────────────────────
        if (policy == null) {
            problems += "policy: missing"
        } else {
            if (!policy.contains("Decision.STUB_THEN_STOP")) {
                problems += "policy: restart without a user attaches"
            }
            if (!policy.contains("appForeground && hasLiveWork")) {
                problems += "policy: a restart ignores whether anything is on screen"
            }
        }
        return problems
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    /** The `{ … }` block after [marker], braces balanced. */
    private fun fnBody(code: String, marker: String): String {
        val start = code.indexOf(marker)
        if (start < 0) throw AssertionError("marker not found: $marker")
        val open = code.indexOf('{', start)
        if (open < 0) throw AssertionError("no body for: $marker")
        return balanced(code, open)
    }

    /**
     * Whole declaration after [marker], up to the next sibling member.
     *
     * Needed for expression-bodied members (`= try { … } catch { … }`), where
     * [fnBody] would stop at the `try` block's closing brace and silently hide
     * the catch arm — which is exactly the arm under test here.
     */
    private fun declBody(code: String, marker: String): String {
        val start = code.indexOf(marker)
        if (start < 0) throw AssertionError("marker not found: $marker")
        val tail = code.substring(start + marker.length)
        val stops = listOf("\n    override fun ", "\n    private fun ", "\n    fun ", "\n    internal fun ")
            .map { tail.indexOf(it) }
            .filter { it >= 0 }
        val end = if (stops.isEmpty()) tail.length else stops.min()
        return code.substring(start, start + marker.length + end)
    }

    private fun objectBody(code: String, marker: String): String = fnBody(code, marker)

    private fun balanced(code: String, open: Int): String {
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
        throw AssertionError("unbalanced body from offset $open")
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
