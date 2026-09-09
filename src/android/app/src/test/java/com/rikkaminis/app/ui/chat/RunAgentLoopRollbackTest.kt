package com.rikkaminis.app.ui.chat

import com.rikkaminis.app.data.model.AgentContentPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC3 — 生产 agent-loop 状态逻辑的直接测试。
 *
 * 背景（系统级根因 F-T11-01）：F01-F14 故障场景由独立重写的 `HarnessRunner`/
 * adapter 骨架驱动，从不触达生产 `ChatViewModel.runAgentLoop`，导致核心 agent
 * 状态链的任何 bug（例如 F-T01-01 的 fallback 不回滚假 tool_use 块）都会全测试
 * 绿逃逸。本测试为「生产使用的」（非旁路）纯逻辑建立直接测试面：
 *
 *  - [rollbackTurnBlocksTo]：生产 retry/fallback 两条路径共用的 turn 级块回滚
 *    语义（单一实现，防止两路漂移）。
 *  - [ChatViewModel.buildTurnParts]：生产的 turn 持久化 parts 构建器（已提升为
 *    internal），用以锁定 F-T01-01 的验收不变量——
 *    「完成某 turn 的 persisted parts 中的 tool_use 集合 == 实际执行的工具集合，
 *    无 PENDING/STREAMING 残留」。
 */
class RunAgentLoopRollbackTest {

    // ── 辅助构造 ──

    private fun block(
        kind: String,
        id: String,
        toolName: String = "",
        toolStatus: ToolBlockStatus? = null,
        content: String = "",
    ) = AssistantBlock(
        id = id,
        kind = kind,
        content = content,
        toolStatus = toolStatus,
        toolName = toolName,
        toolArgs = "{}",
    )

    /** 失败 provider 已发出的假 tool_use 块（状态停在 PENDING，永不执行）。 */
    private fun fakeToolUse(id: String) = block(
        kind = "tool_use",
        id = id,
        toolName = "shell_execute",
        toolStatus = ToolBlockStatus.PENDING,
    )

    // ── rollbackTurnBlocksTo 直接单测 ──

    @Test
    fun `rollback keeps pre-turn blocks and drops the failed attempt's tail`() {
        // turnStartBlockIndex = 2 → 索引 0/1 是更早 turn 的块，保留；2+ 是本次失败尝试的块。
        val blocks = mutableListOf(
            block("text", "prev-prior", content = "earlier"),
            block("tool_use", "prev-done", toolName = "file_read", toolStatus = ToolBlockStatus.SUCCESS),
            fakeToolUse("fake_first"),
            fakeToolUse("fake_second"),
            block("text", "streamed", content = "partial"),
        )
        val removed = rollbackTurnBlocksTo(blocks, turnStartBlockIndex = 2)

        assertTrue("partial blocks existed → should have removed something", removed)
        assertEquals(2, blocks.size)
        assertEquals("prev-prior", blocks[0].id)
        assertEquals("prev-done", blocks[1].id)
        assertTrue("no fake PENDING tool_use should survive", blocks.none { it.kind == "tool_use" && it.toolStatus == ToolBlockStatus.PENDING })
    }

    @Test
    fun `rollback with no partial blocks is a no-op and reports false`() {
        val blocks = mutableListOf(
            block("text", "only", content = "prior"),
        )
        val removed = rollbackTurnBlocksTo(blocks, turnStartBlockIndex = 1)
        assertFalse(removed)
        assertEquals(1, blocks.size)
    }

    @Test
    fun `rollback to index zero clears everything`() {
        val blocks = mutableListOf(fakeToolUse("a"), fakeToolUse("b"))
        assertTrue(rollbackTurnBlocksTo(blocks, turnStartBlockIndex = 0))
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `rollback with turnStartBlockIndex beyond size is a no-op`() {
        val blocks = mutableListOf(fakeToolUse("a"))
        assertFalse(rollbackTurnBlocksTo(blocks, turnStartBlockIndex = 5))
        assertEquals(1, blocks.size)
    }

    // ── F-T01-01 验收不变量（生产语义回归）──

    /**
     * 对应 T01 F-T01-01 的回归场景：
     * provider A 完整发一个 tool_use 后 401 → fallback 到 B 正常完成 →
     * 断言 buildTurnParts 不含 A 的假块。此处用生产 buildTurnParts 验证
     * 「先回滚再构建」能达成该不变量。
     */
    @Test
    fun `after fallback rollback, buildTurnParts excludes failed provider's fake tool_use`() {
        // 失败 provider A 已把一个 PENDING 假 tool_use 压入本轮 allToolBlocks，
        // turnStartBlockIndex 指向本轮起点（假设之前已有 2 个更早 turn 的块）。
        val startBlockIndex = 2
        val blocks = mutableListOf(
            block("text", "prior-1", content = "earlier turn"),
            block("text", "prior-2", content = "earlier turn 2"),
            fakeToolUse("fake_A"), // ← provider A 的假块，PENDING
        )

        // fallback 切到 provider B 前执行生产回滚（RC3 修复点）。
        val removed = rollbackTurnBlocksTo(blocks, startBlockIndex)
        assertTrue(removed)

        // provider B 正常完成，往同一 allToolBlocks 追加自己的内容。
        blocks.add(block("text", "b-reply", content = "B's answer"))

        val parts = buildTurnPartsPure(blocks, startBlockIndex, emptyMap())

        // 不变量：B 完成后的 persisted parts 必须只含实际执行/输出的内容，
        // 绝不包含 A 的 PENDING 假 tool_use。
        assertEquals(1, parts.size)
        assertTrue(parts[0] is AgentContentPart.Text)
        assertEquals(0, parts.count { it is AgentContentPart.ToolUse })
    }

    @Test
    fun `without rollback, buildTurnParts would leak the fake PENDING tool_use`() {
        // 反向对照：证明 F-T01-01 的真实后果。若 fallback 不回滚（修复前状态），
        // 假块会进入 B 完成的 persisted parts。
        val startBlockIndex = 2
        val blocks = mutableListOf(
            block("text", "prior-1", content = "earlier turn"),
            block("text", "prior-2", content = "earlier turn 2"),
            fakeToolUse("fake_A"),
        )
        blocks.add(block("text", "b-reply", content = "B's answer"))

        val parts = buildTurnPartsPure(blocks, startBlockIndex, emptyMap())

        assertTrue("fix would have removed the fake block", parts.any { it is AgentContentPart.ToolUse })
        assertNotEquals("B-only parts would have leaked A's fake tool_use", 1, parts.size)
    }
}
