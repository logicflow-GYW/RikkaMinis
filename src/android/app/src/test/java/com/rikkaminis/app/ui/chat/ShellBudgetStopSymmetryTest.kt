package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deep-audit-1016 5a] The shell-command budget gate must terminate the run the
 * same way the tool-call gate does.
 *
 * Before this fix the two gates in `ChatPromptAndTools.executeTool` were
 * asymmetric: `dimension.tool_calls` exhaustion set the terminal
 * `t7BudgetStopReason` + told the reducer to leave the running state, while
 * `dimension.shell_commands` exhaustion only returned an error string. A run
 * that died on the shell budget therefore had no terminal reason: it was
 * recorded as a normal completion (`SUCCEEDED` / `COMPLETED`), the user saw
 * "answered" instead of "budget exhausted", and `AgentRunReducer` never left
 * `EXECUTING_TOOLS`.
 *
 * The reason string is consumed by the existing generic templates only
 * (`AgentLoopState.errorLabel` → `budget_exhausted($reason)`, and
 * `ChatTurnPersistence.finalizeBudgetStop`'s `else` → "an execution budget was
 * exhausted ($reason)"), so no per-reason string resource is needed — asserted
 * here so a future reword cannot silently stop reaching the banner.
 *
 * The probe is a pure function of the source text (see the negative controls),
 * so it also runs against the pre-fix tree in the branch's differential closure.
 */
class ShellBudgetStopSymmetryTest {

    private val toolsPath =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt"
    private val enginePath =
        "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopEngine.kt"

    @Test
    fun `the shell budget gate terminates the run like the tool-call gate`() {
        val src = readRepoFile(toolsPath)
        val gates = collectBudgetGates(src)

        // Self-check: both gates must be found, otherwise "every gate is
        // terminal" would hold vacuously on a mutated/pre-fix file.
        assertEquals(
            "expected the tool_calls + shell_commands gates",
            listOf("shell_commands", "tool_calls"),
            gates.map { it.dimension }.sorted(),
        )
        gates.forEach {
            assertTrue(
                "${it.dimension} gate at line ${it.line} is not terminal: " +
                    "the run would be recorded as a normal completion",
                it.setsStopReason,
            )
            assertTrue(
                "${it.dimension} gate does not move the reducer off the running state",
                it.reducesInterrupted,
            )
            assertEquals(
                "${it.dimension} gate must keep returning an explicit error to the LLM " +
                    "(not a silent failure)",
                true,
                it.returnsExplicitError,
            )
        }
    }

    @Test
    fun `the new reason stays out of AgentLoopEngine - its four budget sites are pinned`() {
        // AgentLoopBudgetStopExitTest pins EXACTLY four `t7BudgetStopReason = "…"`
        // assignments in the engine (deadline + provider-attempt nested pair +
        // the two gates). The shell budget reason therefore lives in
        // ChatPromptAndTools — a copy in the engine would break that pin and
        // would also mean a second gate for the same dimension.
        val engine = readRepoFile(enginePath)
        assertEquals(
            "the shell budget reason must not be assigned in AgentLoopEngine",
            0,
            Regex("t7BudgetStopReason = \"shell_command_limit\"").findAll(engine).count(),
        )
        assertEquals(
            "AgentLoopBudgetStopExitTest pins four engine assignments",
            4,
            Regex("t7BudgetStopReason\\s*=\\s*\"").findAll(engine).count(),
        )
        assertEquals(
            "the shell budget reason must be assigned exactly once",
            1,
            Regex("t7BudgetStopReason = \"shell_command_limit\"").findAll(readRepoFile(toolsPath)).count(),
        )
    }

    @Test
    fun `the shell budget reason reaches the banner through the generic template`() {
        // No strings.xml entry exists (or is needed): both the trace label and
        // the user banner are templated on the raw reason. Asserted so a future
        // per-reason `when` cannot drop the reason on the floor unnoticed.
        val persistence = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/ChatTurnPersistence.kt",
        )
        assertTrue(
            "finalizeBudgetStop must keep a generic fallback for reasons without " +
                "their own copy",
            persistence.contains("an execution budget was exhausted (\$reason)")
                || persistence.contains("an execution budget was exhausted ("),
        )
        val state = readRepoFile(
            "src/android/app/src/main/java/com/rikkaminis/app/ui/chat/AgentLoopState.kt",
        )
        assertTrue(
            "the trace label must keep templating the raw reason",
            state.contains("budget_exhausted(\$budgetStopReason)"),
        )
    }

    // ── negative controls ────────────────────────────────────────────────

    @Test
    fun `negative control - a gate that only returns an error is rejected`() {
        val src = readRepoFile(toolsPath)
        val mutated = src.replace(
            "        traceObserver.t7BudgetStopReason = \"shell_command_limit\"\n" +
                "        traceObserver.t7Reduce(AgentRunEvent.ProcessInterrupted(" +
                "\"budget_exhausted(shell_commands)\"))\n",
            "",
        )
        assertTrue("mutation must change the source", mutated != src)
        val gates = collectBudgetGates(mutated)
        assertEquals(2, gates.size)
        assertTrue(
            "the pre-fix shape (error without a terminal reason) must be flagged",
            gates.any { !it.setsStopReason },
        )
    }

    @Test
    fun `negative control - a terminal reason without the reducer signal is rejected`() {
        val src = readRepoFile(toolsPath)
        val mutated = src.replace(
            "        traceObserver.t7Reduce(AgentRunEvent.ProcessInterrupted(" +
                "\"budget_exhausted(shell_commands)\"))\n",
            "",
        )
        assertTrue("mutation must change the source", mutated != src)
        val gates = collectBudgetGates(mutated)
        assertTrue(
            "a stop reason without the reducer transition must be flagged",
            gates.any { !it.reducesInterrupted },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

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
}

/** One budget gate in `ChatPromptAndTools.executeTool`, and how it terminates. */
internal data class BudgetGate(
    val dimension: String,
    val line: Int,
    val setsStopReason: Boolean,
    val reducesInterrupted: Boolean,
    val returnsExplicitError: Boolean,
)

/**
 * Finds every RUN-LEVEL `t7ConsumeAndTrace(DIMENSION_…)` budget gate in
 * [toolsSrc] and reports the terminating signals present in its `if (!…) { … }`
 * body.
 *
 * [FIX-R4-Q F-14] Dispatch-level refusals are excluded: the sub-agent dispatch
 * gates its provider requests on the host run's budget and throws
 * `SubagentBudgetExhaustedException` when refused, so the dispatch fails as a
 * tool result while the RUN keeps going (setting `t7BudgetStopReason` there
 * would end the parent run because a sub-agent ran out of attempts). That gate
 * is pinned by `SubagentBudgetGateTest`; this collector is about the gates that
 * terminate the run, and only those may — indeed must — set a stop reason.
 */
internal fun collectBudgetGates(toolsSrc: String): List<BudgetGate> {
    val lines = toolsSrc.split("\n")
    val out = mutableListOf<BudgetGate>()
    lines.forEachIndexed { i, line ->
        val dim = Regex("DIMENSION_([A-Z_]+)").find(line) ?: return@forEachIndexed
        if (!line.contains("t7ConsumeAndTrace")) return@forEachIndexed
        val dimension = dim.groupValues[1].lowercase()
        // The gate body is the following lines until the block closes.
        var depth = 0
        val body = StringBuilder()
        var j = i
        while (j < lines.size) {
            val l = lines[j]
            body.append(l).append('\n')
            depth += l.count { it == '{' } - l.count { it == '}' }
            if (j > i && depth <= 0) break
            j++
        }
        val text = body.toString()
        // Not a run-level gate when its refusal is a dispatch failure — see the
        // KDoc above ([FIX-R4-Q F-14]).
        if (text.contains("SubagentBudgetExhaustedException")) return@forEachIndexed
        out += BudgetGate(
            dimension = dimension,
            line = i + 1,
            setsStopReason = text.contains("t7BudgetStopReason = "),
            reducesInterrupted = text.contains("t7Reduce(AgentRunEvent.ProcessInterrupted("),
            returnsExplicitError = text.contains("return ToolExecutionResult(\"Error: Agent budget exhausted"),
        )
    }
    return out
}
