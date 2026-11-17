package com.rikkaminis.app.ui.chat

import java.io.File
import java.io.FileNotFoundException
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
    fun `the dispatcher is pinned for the dispatch and released in a finally block`() {
        val body = spawn()
        val beginAt = body.indexOf("SubagentDispatchPin.begin(")
        assertTrue("dispatch must pin the parent", beginAt > 0)
        val promptAt = body.indexOf("ChatMutationMethods.prompt(")
        assertTrue("the pin must be taken before the child runs", beginAt < promptAt)

        val finallyAt = body.indexOf("} finally {")
        assertTrue("the release must be in a finally block (a throw must not leak a pin)", finallyAt > beginAt)
        val endAt = body.indexOf("SubagentDispatchPin.end(")
        assertTrue("dispatch must release the pin", endAt > finallyAt)

        assertTrue(
            "restore must only undo OUR pin, not a manual one taken meanwhile",
            body.contains("pinnedAt == ourPin"),
        )
    }

    @Test
    fun `the dispatched session is named after its task`() {
        // Observed on device (2026-11-17): every dispatch landed as
        // "Sub-agent task" in the drawer because session titles come from the
        // first user message, which is the dispatch preamble. The dispatch must
        // overwrite the title from the task text once the session id is known.
        val body = spawn()
        val promptAt = body.indexOf("ChatMutationMethods.prompt(")
        val titleAt = body.indexOf("chatRepository.updateSessionTitle(")
        assertTrue(
            "a dispatched session must be named from the task, not the preamble",
            titleAt > promptAt,
        )
        assertTrue(
            "the name must come from the task text",
            body.contains("firstOrNull { it.isNotEmpty() }"),
        )
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
    }
}