package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.agent.runtime.AgentRunEvent
import com.rikkaminis.app.agent.runtime.AgentRunPhase
import com.rikkaminis.app.agent.runtime.AgentRunRejectionReason
import com.rikkaminis.app.agent.runtime.AgentRunReducer
import com.rikkaminis.app.agent.runtime.AgentRunState
import com.rikkaminis.app.agent.runtime.AgentRunTransition
import com.rikkaminis.app.agent.runtime.AgentTerminal
import com.rikkaminis.app.agent.runtime.ProviderAttemptOutcome
import com.rikkaminis.app.tools.ToolExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/**
 * [deep-audit-1018 ISSUE-03] `outcomeKnown` 四层传递：
 *
 *     ExecutionCoordinator.CommandResult.outcomeKnown
 *       → ToolResultShell.outcomeKnown            (shell 引擎返回体)
 *       → ToolExecutionResult.outcomeKnown        (VM 包装)
 *       → AgentRunEvent.ToolFinished.resultKnown  (T7 trace)
 *       → AgentRunState.hasOutcomeUnknownTool     (reducer 决策)
 *
 * 断点：`executeWithShellRetry` 已经按 retry verdict 打上 outcomeKnown，但
 * `ExecutionCoordinator.execute` 的收尾 CommandResult 重新构造时把它丢了；
 * `ToolResultShell` / `ToolExecutionResult` 根本没有这个字段；
 * `ChatPromptAndTools` 的 t7Reduce 硬编码 `resultKnown = true` —— 于是
 * "shell 命令可能已执行但结果不可信"这条语义在到达 reducer 之前就蒸发了，
 * `SUCCEED_WITH_UNKNOWN_TOOL` 永远不可能因 shell 路径触发。
 *
 * 断言 = 真值传递（写死字面量）+ 一条端到端（shell 结果 → reducer 拒绝 SUCCEEDED）
 * + 源码形状（防止字段在将来被重新内联/硬编码掉）。
 */
class OutcomeKnownPropagationTest {

    // ─── 第 2 层：ToolResultShell → ToolExecutionResult ────────────────

    @Test
    fun `unknown outcome survives the shell to tool-result mapping`() {
        val shell = ToolResultShell(
            output = "partial output",
            success = false,
            toolTitle = "shell_execute",
            timedOut = true,
            outcomeKnown = false,
        )
        val mapped = shell.toToolExecutionResult()
        assertEquals("partial output", mapped.output)
        assertFalse(mapped.success)
        assertEquals("shell_execute", mapped.toolTitle)
        assertTrue(mapped.timedOut)
        assertFalse("outcomeKnown must survive the mapping", mapped.outcomeKnown)
    }

    @Test
    fun `shell result defaults to a known outcome`() {
        val shell = ToolResultShell(output = "ok", success = true, toolTitle = "shell_execute", timedOut = false)
        assertTrue(shell.outcomeKnown)
        assertTrue(shell.toToolExecutionResult().outcomeKnown)
    }

    @Test
    fun `shell error helper is outcome known`() {
        val shell = ToolResultShell.error("bashism rejected", "shell_execute")
        assertEquals("Error: bashism rejected", shell.output)
        assertTrue("a never-run command has a known outcome", shell.outcomeKnown)
        assertTrue(shell.toToolExecutionResult().outcomeKnown)
    }

    // ─── 第 3 层：ToolExecutionResult 自身 ─────────────────────────────

    @Test
    fun `tool result carries and compares the unknown outcome`() {
        val unknown = ToolExecutionResult(output = "x", success = false, outcomeKnown = false)
        val known = ToolExecutionResult(output = "x", success = false)
        assertFalse(unknown.outcomeKnown)
        assertTrue(known.outcomeKnown)
        // equality must not silently fold the two together
        assertNotEquals(known, unknown)
        assertNotEquals(known.hashCode(), unknown.hashCode())
        assertEquals(unknown, unknown.copy(outcomeKnown = false))
    }

    // ─── 端到端：shell 结果 → reducer 拒绝 SUCCEEDED ────────────────────

    @Test
    fun `unknown shell outcome reaches the reducer and blocks SUCCEEDED`() {
        // The value fed to the reducer comes from the production mapping, not a
        // literal — same expression ChatPromptAndTools.t7Reduce uses.
        val mapped = ToolResultShell(
            output = "",
            success = false,
            toolTitle = "shell_execute",
            timedOut = false,
            outcomeKnown = false,
        ).toToolExecutionResult()

        val state = listOf(
            AgentRunEvent.RunStarted("r-unknown"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("shell_execute"),
            AgentRunEvent.ToolFinished("shell_execute", resultKnown = mapped.outcomeKnown),
            AgentRunEvent.WorkCompleted,
        ).fold(AgentRunState.initial()) { s, e -> accept(s, e) }

        assertTrue(state.hasOutcomeUnknownTool)
        assertEquals(AgentRunPhase.FINALIZING, state.phase)
        val rejected = AgentRunReducer.reduce(state, AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED))
        assertTrue(rejected is AgentRunTransition.Rejected)
        assertEquals(
            AgentRunRejectionReason.SUCCEED_WITH_UNKNOWN_TOOL,
            (rejected as AgentRunTransition.Rejected).rejection.reason,
        )

        // Control arm: the same chain with a known outcome still succeeds, so
        // the rejection above is caused by outcomeKnown and nothing else.
        val known = ToolResultShell("", true, "shell_execute", false).toToolExecutionResult()
        val okState = listOf(
            AgentRunEvent.RunStarted("r-known"),
            AgentRunEvent.ProviderAttemptStarted,
            AgentRunEvent.ProviderAttemptFinished(ProviderAttemptOutcome.SUCCESS),
            AgentRunEvent.ToolStarted("shell_execute"),
            AgentRunEvent.ToolFinished("shell_execute", resultKnown = known.outcomeKnown),
            AgentRunEvent.WorkCompleted,
        ).fold(AgentRunState.initial()) { s, e -> accept(s, e) }
        assertFalse(okState.hasOutcomeUnknownTool)
        assertTrue(
            AgentRunReducer.reduce(okState, AgentRunEvent.RunFinalized(AgentTerminal.SUCCEEDED))
                is AgentRunTransition.Accepted,
        )
    }

    // ─── 源码形状：接线点（第 1、4 层在 VM/coordinator，JVM 起不来） ────

    @Test
    fun `coordinator re-wrap carries the verdict outcomeKnown`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/sandbox/ExecutionCoordinator.kt")
        val executeWrap = src.lines().indexOfFirst { it.contains("truncated = outputTruncated") }
        assertTrue("execute() re-wrap not found", executeWrap >= 0)
        val tail = src.lines().drop(executeWrap).take(4).joinToString("\n")
        assertTrue("re-wrap must pass outcomeKnown through:\n$tail", tail.contains("outcomeKnown = result.outcomeKnown"))
    }

    @Test
    fun `shell engine stamps the shell result from the coordinator`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatShellExecution.kt")
        assertTrue(
            "ToolResultShell construction must carry commander outcomeKnown",
            src.contains("outcomeKnown = result.outcomeKnown"),
        )
        assertTrue("mapping helper must pass the field", src.contains("outcomeKnown = outcomeKnown,"))
    }

    @Test
    fun `t7 tool-finished event uses the result object, never a literal`() {
        val src = readRepoFile("app/src/main/java/com/rikkaminis/app/ui/chat/ChatPromptAndTools.kt")
        assertTrue(
            "ToolFinished must read resultKnown from the result object",
            src.contains("AgentRunEvent.ToolFinished(name, resultKnown = result.outcomeKnown)"),
        )
        assertFalse(
            "no hardcoded resultKnown = true may remain",
            src.contains("AgentRunEvent.ToolFinished(name, resultKnown = true)"),
        )
    }

    private fun accept(state: AgentRunState, event: AgentRunEvent): AgentRunState {
        val t = AgentRunReducer.reduce(state, event)
        assertTrue("event rejected unexpectedly: $event -> $t", t is AgentRunTransition.Accepted)
        return (t as AgentRunTransition.Accepted).state
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
}
