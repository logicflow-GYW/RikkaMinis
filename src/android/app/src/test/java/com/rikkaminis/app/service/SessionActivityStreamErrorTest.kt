package com.rikkaminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rikkaminis.app.scheduled.ScheduledTask
import com.rikkaminis.app.scheduled.ScheduledTaskTransitions
import com.rikkaminis.app.scheduled.ScheduledTasksFile
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * [fix/da1015-sched-errorprop] Pins the run-scoped error flag that feeds
 * `SessionActivityTracker`'s completion listener — which is what
 * `ScheduledTaskRunner.onSessionSettled` turns into RESULT_ERROR / RESULT_OK.
 *
 * Two halves, because the defect had two halves:
 *
 * 1. **Behaviour** — [StreamErrorFlags] is plain Kotlin, so mark → clear →
 *    consume is exercised directly. These arms are the run-scoping contract:
 *    a flag is reported exactly once, only for the run that set it.
 * 2. **Wiring** — the production call sites live in Android singletons and
 *    ViewModels the JVM runtime cannot instantiate (Context / viewModelScope /
 *    R.string), so they are asserted on the sources themselves, the same probe
 *    shape as [AppInitSkipGuardWiringTest]. Every wiring assertion has a
 *    negative control that mutates a copy of the real source and proves the
 *    assertion rejects it — a vanished call must not read as a green test.
 *
 * The bug this file exists for: `AgentLoopEngine` surfaces terminal failures
 * through `host.setInlineError` and then returns NORMALLY (it does not throw),
 * so the `catch (e: Exception)` blocks that used to be the flag's only writers
 * never ran. A failed run was reported as a clean completion → the scheduled
 * task's `lastRunDate` got pinned and `failStreak` reset → the fuse could
 * never trip.
 */
class SessionActivityStreamErrorTest {

    // ── 1. behaviour: the run-scoped flag store ──────────────────────────────

    /** 臂 1: a flagged run settles as an error (`isError = true`). */
    @Test
    fun `marked run settles as error exactly once`() {
        val flags = StreamErrorFlags()
        assertEquals("the first mark must set the flag", true, flags.mark("s1"))
        assertEquals(
            "setInactive broadcasts isError=true for a flagged run",
            true,
            flags.consume("s1"),
        )
        assertEquals(
            "the flag is consumed, so a second settle cannot re-report the same failure",
            false,
            flags.consume("s1"),
        )
    }

    /** 臂 2 (关键回归臂): mark → clear → settle must broadcast `isError = false`. */
    @Test
    fun `cleared run settles as success`() {
        val flags = StreamErrorFlags()
        flags.mark("s1")
        assertEquals("the clear must actually retract a set flag", true, flags.clear("s1"))
        assertEquals(
            "a run that recovered must NOT be reported as failed",
            false,
            flags.consume("s1"),
        )
    }

    /** 臂 3: the pre-existing default — never marked → `isError = false`. */
    @Test
    fun `never marked run settles as success`() {
        val flags = StreamErrorFlags()
        assertEquals(false, flags.consume("s1"))
    }

    /** 臂 4: repeated mark / clear is idempotent and never double-counts. */
    @Test
    fun `repeated mark and clear are idempotent`() {
        val flags = StreamErrorFlags()
        assertEquals(true, flags.mark("s1"))
        assertEquals("marking twice is not a second failure", false, flags.mark("s1"))
        assertEquals(false, flags.mark("s1"))
        assertEquals(true, flags.clear("s1"))
        assertEquals("clearing twice removes nothing more", false, flags.clear("s1"))
        assertEquals(false, flags.consume("s1"))
        // And the pair can be replayed for the next run of the same session.
        assertEquals(true, flags.mark("s1"))
        assertEquals(true, flags.consume("s1"))
    }

    /** Flags are per session: one failing session must not fail another. */
    @Test
    fun `flags are per session`() {
        val flags = StreamErrorFlags()
        flags.mark("failed")
        flags.mark("ok")
        flags.clear("ok")
        assertEquals(true, flags.consume("failed"))
        assertEquals(false, flags.consume("ok"))
    }

    /**
     * Run-scoping: a flag left over from a run whose `setInactive` never ran
     * (the claim-token guard skips the teardown) must not be reported as the
     * NEXT run's outcome — the exact false-"failed" that would let a healthy
     * scheduled task burn its fail budget.
     */
    @Test
    fun `stale flag from an abandoned run cannot report the next run`() {
        val flags = StreamErrorFlags()
        // Run 1 fails, but its teardown is skipped → the flag stays behind.
        flags.mark("s1")
        // Run 2 starts (setActive) and succeeds.
        flags.clear("s1")
        assertEquals(
            "the successful run must settle as success",
            false,
            flags.consume("s1"),
        )
    }

    /**
     * Thread-safety: marks arrive from the agent-loop coroutine (IO) while the
     * teardown consumes from whichever thread the `finally` lands on. Each
     * thread owns its own session, so every mark/consume pair is a
     * deterministic invariant (no ordering assumption between threads).
     */
    @Test
    fun `concurrent mark and consume never lose or duplicate a flag`() {
        val flags = StreamErrorFlags()
        val threads = 8
        val rounds = 500
        val start = CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val workers = (0 until threads).map { t ->
            Thread {
                start.await()
                val sid = "s$t"
                repeat(rounds) {
                    if (!flags.mark(sid)) failures += "mark($sid) did not set the flag"
                    if (!flags.consume(sid)) failures += "consume($sid) lost the flag"
                }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(30_000) }
        assertEquals("no thread may observe a lost or duplicated flag: $failures", 0, failures.size)
    }

    // ── 2. wiring: the production call sites ─────────────────────────────────

    @Test
    fun `terminal failure funnel flags the session before the run unwinds`() {
        val body = bodyOf(inlineErrorSource(), "internal fun ChatViewModel.setInlineError(", INLINE_ERROR_FILE)
        assertWiring(setInlineErrorFlags(body), audience = "setInlineError")
    }

    @Test
    fun `recovery retracts the flag, unconditionally and before any early return`() {
        val body = bodyOf(promptToolsSource(), "internal fun ChatViewModel.clearInlineError()", PROMPT_TOOLS_FILE)
        assertWiring(clearInlineErrorRetracts(body), audience = "clearInlineError")
    }

    @Test
    fun `a new run drops the previous run's flag`() {
        val body = bodyOf(trackerSource(), "fun setActive(sessionId: String, onStop: (() -> Unit)? = null)", TRACKER_FILE)
        assertWiring(setActiveDropsStale(body), audience = "setActive")
    }

    @Test
    fun `setInactive consumes the flag as the completion verdict`() {
        val body = bodyOf(trackerSource(), "fun setInactive(sessionId: String)", TRACKER_FILE)
        assertWiring(setInactiveConsumes(body), audience = "setInactive")
    }

    @Test
    fun `both flag mutators exist and write the run-scoped store`() {
        val source = trackerSource()
        assertWiring(markMutatorWritesStore(source), audience = "markStreamError")
        assertWiring(clearMutatorWritesStore(source), audience = "clearStreamError")
    }

    /**
     * Every run boundary flags the tracker — the audit found the drain and
     * resume boundaries missing it. The counts are literals so a NEW boundary
     * that forgets to flag (or a deleted one) fails here rather than silently
     * reporting a clean completion.
     */
    @Test
    fun `every run boundary flags the tracker`() {
        val boundary = "SessionActivityTracker.markStreamError(activeSessionId)"
        val expected = mapOf(
            "ui/chat/ChatViewModel.kt" to 2,        // send + resume
            "ui/chat/ChatRetryOps.kt" to 1,         // retryLast
            "ui/chat/ChatQueueInterruption.kt" to 1, // queued drain
            "ui/chat/ChatTurnPersistence.kt" to 1,  // persistence-path call sites
        )
        for ((rel, count) in expected) {
            val text = appSource(rel)
            assertEquals(
                "$rel must flag the tracker in $count run-boundary catch block(s)",
                count,
                occurrences(text, boundary),
            )
        }
    }

    /**
     * Cancellation is not a failure: every mark must sit inside a
     * `catch (e: Exception)` handler, never in the adjacent
     * `catch (e: CancellationException)` one (a user-stopped run must settle
     * as `isError = false`).
     */
    @Test
    fun `marks live in Exception handlers, never in cancellation handlers`() {
        val files = listOf(
            "ui/chat/ChatViewModel.kt",
            "ui/chat/ChatRetryOps.kt",
            "ui/chat/ChatQueueInterruption.kt",
            "ui/chat/ChatTurnPersistence.kt",
        )
        for (rel in files) {
            val body = normalize(appSource(rel))
            assertWiring(notInCancellationHandler(body, rel), audience = "cancel-is-not-error ($rel)")
        }
    }

    /**
     * The engine must keep funnelling its terminal failures through
     * `setInlineError` — a failure branch that surfaces an error some other
     * way would bypass the tracker again. One funnel, two shapes: immediate
     * sites call `host.setInlineError` inline, and [deep-audit-1015 BUG-01b]
     * deferred sites park the banner in `loopState.pendingInlineError` for
     * the turn tail to drain through `TerminalErrorPlacement` into the SAME
     * funnel — an inline call before the turn's row exists would stamp the
     * banner onto the previous turn's row. `>=` and not `==` on both counts
     * on purpose: adding a funnel site is correct; removing one, breaking
     * the tail drain (or opening a second error channel) is the regression.
     */
    @Test
    fun `the engine keeps a single error funnel and no bypass channel`() {
        assertWiring(engineFunnelIntact(engineSource()), audience = "AgentLoopEngine funnel")
        val body = normalize(engineSource())
        val inline = occurrences(body, "host.setInlineError(")
        val deferred = occurrences(body, "loopState.pendingInlineError = host.string(")
        // [FIX-R4-Q F-01] Floor 5 -> 3: the empty-response hint and the
        // EOF give-up banner are fall-through branches whose row is persisted
        // by the turn tail BELOW them, so an inline write there would stamp
        // the banner onto the PREVIOUS turn's row (the shape
        // TerminalErrorPlacementTest rejects). They park in the deferred slot
        // instead — the second shape this very KDoc endorses. Site total is
        // unchanged (10 before, 10 now: 2 moved direct -> deferred); the
        // surviving direct three are the two "persist an empty row first"
        // branches plus the tail flush.
        assertTrue("expected at least 3 direct funnel sites, found $inline", inline >= 3)
        // [FIX-R4-Q F-01] Floor 5 -> 6: the EOF give-up banner became a
        // deferred site (its row is now persisted by the turn tail below it),
        // and `normalize` collapses that multi-line assignment onto one line,
        // so the live count is 6. The floor has to move with the live count —
        // at 5 the negative control below ("remove one deferred site") stayed
        // green, i.e. the funnel could lose a site unnoticed.
        assertTrue("expected at least 6 deferred banner sites, found $deferred", deferred >= 6)
    }

    /**
     * The consequence chain this fix exists for, end to end on the pure
     * surfaces: flag → the run's verdict → the outcome `ScheduledTaskRunner`
     * records → the policy's streak / daily gate. Before the fix the failing
     * path produced RESULT_OK here, which pinned `lastRunDate` and reset
     * `failStreak` — the fuse could not trip no matter how often a run failed.
     */
    @Test
    fun `the verdict still drives the scheduled-task outcome and its streak`() {
        val runner = bodyOf(
            appSource(RUNNER_REL),
            "fun onSessionSettled(context: Context, sessionId: String, isError: Boolean)",
            RUNNER_REL,
        )
        assertWiring(runnerMapsVerdict(runner), audience = "ScheduledTaskRunner.onSessionSettled")

        val file = ScheduledTasksFile(
            globalEnabled = true,
            tasks = listOf(
                ScheduledTask(
                    id = "t-1",
                    title = "briefing",
                    prompt = "summarize",
                    windowStart = "07:00",
                    windowEnd = "23:00",
                    lastRunDate = null,
                    failStreak = 0,
                ),
            ),
        )

        // A flagged run settles as failed (RESULT_ERROR = "error").
        val flags = StreamErrorFlags()
        flags.mark("s1")
        val outcome = if (flags.consume("s1")) ScheduledTask.RESULT_ERROR else ScheduledTask.RESULT_OK
        assertEquals("error", outcome)
        val failed = ScheduledTaskTransitions.completeRun(file, "t-1", outcome, "2026-10-15")
        assertEquals("a failed run counts one failure", 1, failed.task!!.failStreak)
        assertNull(
            "a failed run must NOT advance the daily gate — that is what keeps the day's retry open",
            failed.task.lastRunDate,
        )
        assertFalse("one failure is below the fuse threshold", failed.fuseTripped)

        // An unflagged run settles as success: gate advanced, streak cleared.
        val clean = StreamErrorFlags()
        val cleanOutcome =
            if (clean.consume("s1")) ScheduledTask.RESULT_ERROR else ScheduledTask.RESULT_OK
        assertEquals("ok", cleanOutcome)
        val ok = ScheduledTaskTransitions.completeRun(file, "t-1", cleanOutcome, "2026-10-15")
        assertEquals(0, ok.task!!.failStreak)
        assertEquals("2026-10-15", ok.task.lastRunDate)
    }

    // ── 3. negative controls (mutate a real-source copy, expect a red) ───────

    @Test
    fun `mutating the flag wiring out turns the wiring assertions red`() {
        // Each entry is the SAME assertion the production-source tests above
        // run, bound to a mutated input — so a mutation that removes the call
        // makes this list throw instead of quietly passing.
        val assertions: List<Pair<String, (String) -> Unit>> = listOf(
            "setInlineError mark" to { src: String ->
                assertWiring(
                    setInlineErrorFlags(bodyOf(src, SET_INLINE_ERROR_SIG, INLINE_ERROR_FILE)),
                    audience = "setInlineError (mutated)",
                )
            },
            "clearInlineError clear" to { src: String ->
                assertWiring(
                    clearInlineErrorRetracts(bodyOf(src, CLEAR_INLINE_ERROR_SIG, PROMPT_TOOLS_FILE)),
                    audience = "clearInlineError (mutated)",
                )
            },
            "setActive stale drop" to { src: String ->
                assertWiring(
                    setActiveDropsStale(bodyOf(src, SET_ACTIVE_SIG, TRACKER_FILE)),
                    audience = "setActive (mutated)",
                )
            },
            "setInactive consume" to { src: String ->
                assertWiring(
                    setInactiveConsumes(bodyOf(src, SET_INACTIVE_SIG, TRACKER_FILE)),
                    audience = "setInactive (mutated)",
                )
            },
            "markStreamError store write" to { src: String ->
                assertWiring(markMutatorWritesStore(src), audience = "markStreamError (mutated)")
            },
            "clearStreamError store write" to { src: String ->
                assertWiring(clearMutatorWritesStore(src), audience = "clearStreamError (mutated)")
            },
            "cancel-is-not-error" to { src: String ->
                assertWiring(
                    notInCancellationHandler(normalize(src), "ui/chat/ChatViewModel.kt"),
                    audience = "cancel-is-not-error (mutated)",
                )
            },
            "engine funnel" to { src: String ->
                assertWiring(engineFunnelIntact(src), audience = "engine funnel (mutated)")
            },
            "engine deferred site" to { src: String ->
                assertWiring(engineFunnelIntact(src), audience = "engine deferred site (mutated)")
            },
            "engine tail drain" to { src: String ->
                assertWiring(engineFunnelIntact(src), audience = "engine tail drain (mutated)")
            },
        )
        val mutated = mapOf(
            "setInlineError mark" to inlineErrorSource().replace(
                "if (sid.isNotEmpty()) SessionActivityTracker.markStreamError(sid)",
                "if (sid.isNotEmpty()) { /* flag removed */ }",
            ),
            "clearInlineError clear" to promptToolsSource().replace(
                "SessionActivityTracker.clearStreamError(sid)",
                "/* clear removed */",
            ),
            "setActive stale drop" to trackerSource().replace(
                "pendingErrorFlag.clear(sessionId)\n",
                "",
            ),
            "setInactive consume" to trackerSource().replace(
                "val wasError = pendingErrorFlag.consume(sessionId)",
                "val wasError = false",
            ),
            "markStreamError store write" to trackerSource().replace(
                "if (pendingErrorFlag.mark(sessionId)) {",
                "if (false) {",
            ),
            "clearStreamError store write" to trackerSource().replace(
                "if (pendingErrorFlag.clear(sessionId)) {",
                "if (false) {",
            ),
            "cancel-is-not-error" to chatViewModelSource().replace(
                "catch (e: Exception) {\n                            AppLogger.error(TAG_STREAM, \"resume runAgentLoop EXCEPTION",
                "catch (e: CancellationException) {\n                            AppLogger.error(TAG_STREAM, \"resume runAgentLoop EXCEPTION",
            ),
            "engine funnel" to engineSource().replace(
                "host.setInlineError(host.string(R.string.error_content_filtered))",
                "host.someOtherErrorChannel(host.string(R.string.error_content_filtered))",
            ),
            "engine deferred site" to engineSource().replace(
                "loopState.pendingInlineError = host.string(R.string.error_stream_interrupted)",
                "host.someOtherErrorChannel(host.string(R.string.error_stream_interrupted))",
            ),
            "engine tail drain" to engineSource().replace(
                "terminalErrorToSurface(pendingInlineError, persistedRowId)",
                "terminalErrorToSurface(null, persistedRowId)",
            ),
        )
        for ((name, assertion) in assertions) {
            val source = mutated.getValue(name)
            if (source == originalSourceFor(name)) {
                // The production source no longer carries the wiring this
                // control edits (it was removed for real — e.g. while mutation
                // testing this very tree), so the edited copy is identical.
                // The meaningful check then is the inverse: the assertion must
                // reject the LIVE source, which is what the removal proves.
                val rejected = try {
                    assertion(source)
                    false
                } catch (e: AssertionError) {
                    true
                }
                assertTrue(
                    "the live source lost '$name' but the wiring assertion still accepts it",
                    rejected,
                )
                continue
            }
            val threw = try {
                assertion(source)
                false
            } catch (e: AssertionError) {
                true
            }
            assertTrue("removing '$name' must turn its wiring assertion red", threw)
        }
    }

    @Test
    fun `the funnel probe rejects a second error channel`() {
        val leaked = engineSource().replace(
            "loopState.terminalErrorSurfaced = true",
            "_error.value = \"bypassed\"\n                    loopState.terminalErrorSurfaced = true",
        )
        val threw = try {
            assertWiring(engineFunnelIntact(leaked), audience = "engine funnel (leaked channel)")
            false
        } catch (e: AssertionError) {
            true
        }
        assertTrue("a second error channel in the engine must fail the funnel probe", threw)
    }

    // ── the wiring assertions themselves (reused by the controls) ────────────

    private fun assertWiring(verdict: String?, audience: String) {
        if (verdict != null) fail("wiring assertion failed for $audience: $verdict")
    }

    private fun setInlineErrorFlags(body: String): String? = when {
        !contains(body, "SessionActivityTracker.markStreamError(sid)") ->
            "setInlineError must flag the session: no markStreamError(sid) call in its body"
        indexOf(body, "SessionActivityTracker.markStreamError(sid)") >
            indexOf(body, "if (lastAssistantIdx >= 0) {") ->
            "the flag must be set for EVERY terminal failure, not only when an assistant bubble exists"
        indexOf(body, "SessionActivityTracker.markStreamError(sid)") >
            indexOf(body, "flushAllStreamingDeltas()") ->
            "the flag must be set before the banner is built (and well before the run unwinds)"
        else -> null
    }

    private fun clearInlineErrorRetracts(body: String): String? = when {
        !contains(body, "SessionActivityTracker.clearStreamError(sid)") ->
            "clearInlineError must retract the flag: no clearStreamError(sid) call in its body"
        indexOf(body, "SessionActivityTracker.clearStreamError(sid)") >
            indexOf(body, "return") ->
            "the retraction must run before the early returns, or a recovery that has no banner cannot undo the flag"
        else -> null
    }

    private fun setActiveDropsStale(body: String): String? = when {
        !contains(body, "pendingErrorFlag.clear(sessionId)") ->
            "setActive must drop the previous run's flag: no pendingErrorFlag.clear(sessionId)"
        else -> null
    }

    private fun setInactiveConsumes(body: String): String? = when {
        !contains(body, "val wasError = pendingErrorFlag.consume(sessionId)") ->
            "setInactive must consume the flag as its isError verdict"
        else -> null
    }

    private fun markMutatorWritesStore(source: String): String? {
        val body = bodyOf(source, "fun markStreamError(sessionId: String)", TRACKER_FILE)
        return when {
            !contains(body, "pendingErrorFlag.mark(sessionId)") ->
                "markStreamError must write the run-scoped store"
            contains(body, "pendingErrorFlag.consume(") ->
                "markStreamError must not consume the flag it just set"
            else -> null
        }
    }

    private fun clearMutatorWritesStore(source: String): String? {
        val body = bodyOf(source, "fun clearStreamError(sessionId: String)", TRACKER_FILE)
        return when {
            !contains(body, "pendingErrorFlag.clear(sessionId)") ->
                "clearStreamError must retract the flag in the run-scoped store"
            contains(body, "completionListener?.invoke") ->
                "clearStreamError must not fire the completion listener — setInactive owns that"
            else -> null
        }
    }

    private fun notInCancellationHandler(body: String, rel: String): String? {
        val needle = "SessionActivityTracker.markStreamError(activeSessionId)"
        var from = 0
        while (true) {
            val idx = body.indexOf(needle, from)
            if (idx < 0) return null
            val previousCatch = body.lastIndexOf("catch (e:", idx)
            if (previousCatch < 0) return "$rel: mark without a preceding catch"
            val handler = body.substring(previousCatch, idx)
            if (!handler.startsWith("catch (e: Exception)")) {
                return "$rel: mark sits in `$handler` — a cancelled run must not be reported as failed"
            }
            from = idx + 1
        }
    }

    private fun runnerMapsVerdict(body: String): String? = when {
        !contains(body, "isError") -> "the runner must branch on the tracker verdict"
        !contains(body, "ScheduledTask.RESULT_ERROR") ->
            "the runner must map a flagged run to RESULT_ERROR"
        !contains(body, "ScheduledTask.RESULT_OK") ->
            "the runner must map a clean run to RESULT_OK"
        else -> null
    }

    private fun engineFunnelIntact(source: String): String? {
        val body = normalize(source)
        // One funnel, two shapes [fix/da1015-funnel-evidence]: immediate
        // sites call host.setInlineError inline; [deep-audit-1015 BUG-01b]
        // deferred sites park the banner in loopState.pendingInlineError and
        // the turn tail drains the slot through TerminalErrorPlacement into
        // the SAME funnel (persist first, then surface — an inline call
        // before the turn's row exists stamps the banner onto the PREVIOUS
        // turn's row). The literal call count therefore split in two, but
        // the invariant did not: every banner must still land in
        // host.setInlineError, and no second error channel may open.
        val inline = occurrences(body, "host.setInlineError(")
        // [FIX-R4-Q F-01] 3, not 5 — see the note in the test above: two
        // fall-through sites moved to the deferred shape (the funnel itself,
        // the drain and the no-second-channel rule below are unchanged).
        if (inline < 3) return "the engine lost direct funnel sites: found $inline, expected at least 3"
        val deferred = occurrences(body, "loopState.pendingInlineError = host.string(")
        // [FIX-R4-Q F-01] 6, not 5 — see the note in the test above (the EOF
        // give-up banner is now a deferred site, so a removed site must go red).
        if (deferred < 6) return "the engine lost deferred banner sites: found $deferred, expected at least 6"
        // The deferred slot must actually drain: the tail hands BOTH the
        // banner and the persist result to TerminalErrorPlacement and puts
        // what comes back onto the same funnel. A slot nobody drains would
        // swallow the banner instead of surfacing it.
        if (!contains(body, "terminalErrorToSurface(pendingInlineError, persistedRowId)")) {
            return "the turn tail no longer drains the deferred banner through TerminalErrorPlacement"
        }
        if (!contains(body, "host.setInlineError(banner)")) {
            return "the deferred banner flush no longer surfaces through the setInlineError funnel"
        }
        if (contains(body, "_error.value =")) {
            return "the engine opened a second error channel (_error.value) that bypasses setInlineError"
        }
        return null
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun originalSourceFor(name: String): String = when (name) {
        "setInlineError mark" -> inlineErrorSource()
        "clearInlineError clear" -> promptToolsSource()
        "setActive stale drop", "setInactive consume",
        "markStreamError store write", "clearStreamError store write" -> trackerSource()
        "cancel-is-not-error" -> chatViewModelSource()
        "engine funnel", "engine deferred site", "engine tail drain" -> engineSource()
        else -> error("unknown control '$name'")
    }

    private fun contains(haystack: String, needle: String) = haystack.contains(needle)

    private fun indexOf(haystack: String, needle: String) = haystack.indexOf(needle)

    /** Drop comments, then collapse whitespace so multi-line code matches one literal. */
    private fun normalize(text: String): String {
        val noComments = text
            .replace(Regex("""//[^\n]*"""), " ")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        return noComments.replace(Regex("""\s+"""), " ").trim()
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

    /** The normalized body of the function declared by [signature]. */
    private fun bodyOf(source: String, signature: String, rel: String): String {
        val body = normalize(source)
        val start = body.indexOf(signature)
        assertTrue("'$signature' not found in $rel", start >= 0)
        val open = body.indexOf('{', start)
        assertTrue("no body after '$signature' in $rel", open > start)
        val close = matchingBrace(body, open)
        assertTrue("unbalanced body for '$signature' in $rel", close > open)
        return body.substring(open, close + 1)
    }

    /** Index of the `}` matching the `{` at [openIndex]; -1 when unbalanced. */
    private fun matchingBrace(text: String, openIndex: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIndex until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** A source under `src/main/java/com/rikkaminis/app/` (package-relative [rel]). */
    private fun appSource(rel: String): String =
        sourceFile("src/main/java/com/rikkaminis/app/$rel").readText()

    private fun trackerSource(): String = sourceFile(TRACKER_FILE).readText()
    private fun inlineErrorSource(): String = sourceFile(INLINE_ERROR_FILE).readText()
    private fun promptToolsSource(): String = sourceFile(PROMPT_TOOLS_FILE).readText()
    private fun chatViewModelSource(): String = sourceFile(CHAT_VM_FILE).readText()
    private fun engineSource(): String = sourceFile(ENGINE_FILE).readText()

    /** Walk up from the CWD (same technique as [AppInitSkipGuardWiringTest]). */
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

    private companion object {
        const val TRACKER_FILE = "src/main/java/com/rikkaminis/app/service/SessionActivityTracker.kt"
        const val INLINE_ERROR_FILE = "src/main/java/com/rikkaminis/app/ui/chat/ChatInlineErrorExt.kt"
        const val PROMPT_TOOLS_FILE = "src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt"
        const val CHAT_VM_FILE = "src/main/java/com/rikkaminis/app/ui/chat/ChatViewModel.kt"
        const val ENGINE_FILE = "src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"

        /** Package-relative (under `com/rikkaminis/app/`) path used by [appSource]. */
        const val RUNNER_REL = "scheduled/ScheduledTaskRunner.kt"

        const val SET_INLINE_ERROR_SIG = "internal fun ChatViewModel.setInlineError("
        const val CLEAR_INLINE_ERROR_SIG = "internal fun ChatViewModel.clearInlineError()"
        const val SET_ACTIVE_SIG = "fun setActive(sessionId: String, onStop: (() -> Unit)? = null)"
        const val SET_INACTIVE_SIG = "fun setInactive(sessionId: String)"
    }
}
