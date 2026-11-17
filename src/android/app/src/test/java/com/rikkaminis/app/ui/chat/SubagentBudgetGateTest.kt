package com.rikkaminis.app.ui.chat

import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring assertions for the sub-agent dispatch path.
 *
 * Dispatch is a real session now (rework 2026-11-17), so the behaviour lives
 * behind `ChatMutationMethods.prompt` — Android-coupled and not JVM-callable.
 * What CAN silently rot is the wiring around it, and each assertion below is a
 * defect that has already happened once somewhere in this tree:
 *
 *  - a budget gate that exists but sits AFTER the call it was meant to guard;
 *  - dispatch that returns immediately instead of waiting for an answer;
 *  - a cancellation that is swallowed into "sub-agent failed";
 *  - a pin taken for the dispatch window that is never released.
 */
class SubagentBudgetGateTest {

    private val promptAndTools by lazy { readRepoFile(PROMPT_AND_TOOLS) }

    private fun spawn(): String =
        functionRegion(promptAndTools, "internal suspend fun ChatViewModel.executeSpawnAgentTool(")

    @Test
    fun `deadline and provider-attempt gates run before the session is prompted`() {
        val body = spawn()
        val promptAt = body.indexOf("ChatMutationMethods.prompt(")
        assertTrue("the dispatch call site is gone", promptAt > 0)

        val deadlineAt = body.indexOf("activeRunBudget?.isExpired() == true")
        assertTrue("the dispatch must refuse past the run deadline", deadlineAt in 0 until promptAt)

        val consumeAt = body.indexOf("DIMENSION_PROVIDER_ATTEMPTS")
        assertTrue(
            "one provider attempt must be charged to THIS run before the child runs",
            consumeAt in 0 until promptAt,
        )
    }

    @Test
    fun `dispatch asks for the final answer instead of fire-and-forget`() {
        val body = spawn()
        assertTrue("dispatch must block for the answer", body.contains(".put(\"wait\", true)"))
        assertTrue(
            "the wait must be bounded",
            body.contains("SubagentSkill.WAIT_TIMEOUT_SECONDS"),
        )
    }

    @Test
    fun `a user stop is rethrown, not reported as a sub-agent failure`() {
        val body = spawn()
        val catchAt = body.indexOf("catch (ce: CancellationException)")
        assertTrue("CancellationException must be handled before the generic catch", catchAt > 0)
        val rethrowAt = body.indexOf("throw ce", catchAt)
        assertTrue("the cancellation must be rethrown", rethrowAt > catchAt)
        val genericAt = body.indexOf("catch (t: Throwable)")
        assertTrue("the generic catch must come after the cancellation arm", genericAt > catchAt)
    }

    @Test
    fun `the dispatch window is opened and closed through the locked window ops`() {
        // [INCR-1117B A4] What used to be three separate `indexOf` assertions
        // (begin before prompt, end after finally, NonCancellable around it) is
        // now ONE shape assertion: the call site may only ever delegate to
        // SubagentDispatchPin.beginWindow / finishWindow. The behaviour those
        // blocks must have — a restore that is not cut short by a cancellation,
        // and a restore that cannot interleave with a new window — is covered
        // by real coroutine tests in SubagentDispatchPinTest. Those tests
        // survive refactors; a token order does not (moving the Room calls out
        // of the NonCancellable block used to stay green).
        val body = spawn()
        val beginAt = body.indexOf("SubagentDispatchPin.begin(")
        assertTrue("dispatch must open the window", beginAt > 0)
        val promptAt = body.indexOf("ChatMutationMethods.prompt(")
        assertTrue("the window must be open before the child runs", beginAt < promptAt)

        val finallyAt = body.indexOf("} finally {")
        assertTrue("the close must live in a finally (a throw must not leak a pin)", finallyAt > beginAt)
        val endAt = body.indexOf("SubagentDispatchPin.end(")
        assertTrue("dispatch must close the window", endAt > finallyAt)

        assertFalse(
            "the raw count/pin bookkeeping is private on purpose: touching it from " +
                "the call site skips the per-session lock and the NonCancellable " +
                "block that make the window atomic",
            body.contains("SubagentDispatchPin.noteWrittenPin(") ||
                body.contains("SubagentDispatchPin.takeWrittenPin("),
        )
        assertTrue(
            "a failed restore must be reported, not swallowed silently",
            body.contains("if (!restored)"),
        )
    }

    @Test
    fun `the child is named at creation, before the title generator can run`() {
        // On-device probes (1.0.0+3191 then +3192): renaming AFTER the turn
        // loses — the child's auto-title generator finishes later and overwrites
        // it, and the first attempt managed to rename the PARENT instead
        // (ChatViewModel has its own `sessionId`). The title generator only
        // fires while the row reads "New Chat", so the fix is to name the row
        // when it is created: the title must travel in the dispatch params.
        val body = spawn()
        val promptAt = body.indexOf("ChatMutationMethods.prompt(")
        assertTrue("the dispatch call site is gone", promptAt > 0)
        assertTrue(
            "the child title must be part of the dispatch params",
            body.contains("put(\"title\""),
        )
        assertFalse(
            "a post-hoc rename races the child's title generator — it must not come back",
            body.contains("chatRepository.updateSessionTitle("),
        )
        assertTrue(
            "the name must come from the task text",
            body.contains("firstOrNull { it.isNotEmpty() }"),
        )
    }

    @Test
    fun `a cancelled caller stops the turn it started`() {
        // [INCR-1117 A-F04] The turn streams on the session's own
        // viewModelScope, so releasing the wait is not enough: stop on the
        // dispatching turn must also stop the child, which keeps running tools
        // and spending tokens otherwise.
        // [INCR-1117B A4] Scoped to prompt(): the same tokens appear in retry()
        // and elsewhere in this class, so matching them file-wide could be
        // satisfied by a function that never entered the picture.
        val body = balancedRegion(readRepoFile(HEADLESS_RUNNER), "suspend fun prompt(")
        val waitAt = body.indexOf("val finished = try {")
        assertTrue("the wait must be written so its cancellation is handled", waitAt > 0)
        val nonCancelAt = body.indexOf("withContext(NonCancellable)", waitAt)
        assertTrue("cancelling the child must not itself be cancelled", nonCancelAt > waitAt)
        val cancelAt = body.indexOf("vm.cancelStream()", nonCancelAt)
        assertTrue("a cancelled caller must stop the turn it started", cancelAt > nonCancelAt)
        val rethrowAt = body.indexOf("throw ce", cancelAt)
        assertTrue("the stop must still reach the caller as a stop", rethrowAt > cancelAt)
    }

    @Test
    fun `the dispatch switch is checked at execution, not only in the schema`() {
        // [INCR-1117 A-F06] The switch gated the schema and the routing
        // fragment only, so a replayed or hand-crafted call still opened a
        // session. The memory tools are double-gated the same way.
        val body = spawn()
        val promptAt = body.indexOf("ChatMutationMethods.prompt(")
        assertTrue("the dispatch call site is gone", promptAt > 0)
        val gateAt = body.indexOf("SubagentPrefs.isEnabled(context)")
        assertTrue("dispatch must refuse while the user has it off", gateAt > 0)
        assertTrue("the refusal must come before the child session is opened", gateAt < promptAt)
    }

    @Test
    fun `the wait is capped by the run budget, not only by the fixed constant`() {
        // [INCR-1117 A-F07] The run deadline is only consulted at turn
        // boundaries, so a flat 900 s tool wait can outlive its own run.
        val body = spawn()
        val promptAt = body.indexOf("ChatMutationMethods.prompt(")
        assertTrue("the dispatch call site is gone", promptAt > 0)
        val budgetAt = body.indexOf("remaining()?.millisRemaining")
        assertTrue("the wait must shrink with what the deadline has left", budgetAt in 0 until promptAt)
        assertTrue(
            "the arithmetic must live in the JVM-tested pure function",
            body.contains("SubagentSkill.effectiveWaitSeconds("),
        )
        assertTrue(
            "the timeout envelope must name the wait actually used",
            body.contains("timeoutSeconds = waitSeconds"),
        )
    }

    @Test
    fun `a json null reply is resolved before it becomes the answer text`() {
        // [INCR-1117 A-F01] Android's `optString(name, fallback)` returns the
        // literal "null" for `JSONObject.NULL` — reading the field that way is
        // the defect itself, so the reader must go through the isNull helper.
        val body = spawn()
        assertTrue("the reply must be read through SubagentSkill.replyText", body.contains("SubagentSkill.replyText("))
        assertFalse(
            "reading responseText with optString is the defect",
            body.contains("optString(\"responseText\""),
        )
    }

    /** Source of [signature]'s own body: brace matched from its opening `{`, so
     *  a member function is cut at its own closing brace instead of bleeding
     *  into the next one (the file-order `indexOf` scans it replaces could be
     *  satisfied by any other member of the class). */
    private fun balancedRegion(src: String, signature: String): String {
        val at = src.indexOf(signature)
        assertTrue("signature not found: $signature", at >= 0)
        val open = src.indexOf('{', at + signature.length)
        assertTrue("no body after $signature", open > at)
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        val region = src.substring(at, i + 1)
                        assertTrue("the region must close before EOF", region.length < src.length)
                        return region
                    }
                }
            }
            i += 1
        }
        throw AssertionError("unbalanced braces after $signature")
    }

    /** Source of the function starting at [signature], cut at the next top-level
     *  declaration so ordering assertions cannot see the neighbouring function. */
    private fun functionRegion(src: String, signature: String): String {
        val at = src.indexOf(signature)
        assertTrue("signature not found: $signature", at >= 0)
        val rest = src.substring(at + signature.length)
        val end = Regex("\n(?:@|internal |private |public |fun )").find(rest)
        return if (end == null) rest else rest.substring(0, end.range.first)
    }

    private fun readRepoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: error("user.dir unset"))
        while (dir != null) {
            val f = File(dir, relative)
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        throw FileNotFoundException("$relative not found from user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PROMPT_AND_TOOLS =
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt"
        const val HEADLESS_RUNNER =
            "src/android/app/src/main/java/com/rikkaminis/app/debug/HeadlessChatRunner.kt"
    }
}