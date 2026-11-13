package com.rikkaminis.app.ui.chat

/**
 * [audit2 DESIGN-03] Block-list isolation for the PARALLEL tool batch.
 *
 * `loopState.allToolBlocks` is a plain `ArrayList` (AgentLoopState:51) and is
 * read on the main thread by every `updateAssistantMessage` publish. The
 * parallel branch used to hand that very instance to all N `async {}` bodies,
 * which inherit the outer `launch(Dispatchers.IO)` — i.e. N IO threads and the
 * UI shared one unsynchronized mutable list. `executeTool`'s contract takes a
 * `MutableList` and the one writer that exists today (`executeShellCommand`'s
 * `onBlockUpdate`: `toolBlocks[idx] = …`) performs a read-modify-write from
 * whichever thread the coroutine runs on.
 *
 * The tool whitelist (`ToolConcurrencyPolicy.PARALLEL_SAFE_TOOLS`) is the only
 * thing keeping writers out of that window today — file_read / read_image /
 * browser reads do not touch the list. That makes the race latent, not absent:
 * widening the whitelist, or any future parallel-safe tool that publishes a
 * block, turns it into the real thing (lost updates on the ArrayList, and a
 * torn read for whatever iterates it on the main thread).
 *
 * So: fork a private copy per concurrent call, merge once afterwards, on the
 * caller's single thread, in `pending` order. The shared instance is then
 * never reachable from a concurrent coroutine.
 *
 * ponytail: 每并发调用一份整表副本 + 批量结束后单点合并 | 天花板: O(批内调用数 × 块数)
 * 的复制与合并开销；当前批 ≤ 数十块、read-only 工具不写块，合并通常是恒等 | 升级触发:
 * 白名单里出现真正写块的并行工具且批量变大 → 换 per-id 结果槽 + 单点合并（同语义，零复制）。
 */
internal object ParallelToolBatchBlocks {

    /** Immutable batch-start snapshot. Every fork is taken from THIS, not from the live list. */
    fun snapshot(shared: List<AssistantBlock>): List<AssistantBlock> = ArrayList(shared)

    /** Working copy handed to ONE concurrent call. Never share between calls. */
    fun forkForCall(baseline: List<AssistantBlock>): MutableList<AssistantBlock> = ArrayList(baseline)

    /**
     * Single-point merge of every call's edits back into [shared].
     *
     * Called on the caller's thread after the whole batch completed.
     *
     * Only blocks that DIFFER FROM [baseline] are propagated. A copy carries
     * the whole list, so a call that never touched block X still holds block
     * X's stale value — merging "everything that differs from `shared`" would
     * make each call write that stale value back and undo a sibling's edit.
     * Comparing against the batch-start snapshot makes every call carry
     * exactly its own delta, which is also what keeps [shared] untouched for
     * calls that edited nothing.
     *
     * Iterates [perCall] in `pending` order, so if two calls edited the same
     * id the later-pending call wins deterministically — the same
     * last-writer-wins result the sequential path produces. Blocks are matched
     * by id, not by index, so a call that structurally changed its copy cannot
     * shift another call's write onto the wrong block. Unmodified blocks are
     * left alone (identity preserved for equality-keyed UI diffing).
     */
    fun mergeBack(
        shared: MutableList<AssistantBlock>,
        baseline: List<AssistantBlock>,
        perCall: List<List<AssistantBlock>>,
    ) {
        if (perCall.isEmpty()) return
        val indexById = HashMap<String, Int>(baseline.size)
        baseline.forEachIndexed { i, block -> if (!indexById.containsKey(block.id)) indexById[block.id] = i }
        for (worked in perCall) {
            // [r8-C-F03] Baseline AND target are both read by id. Reading the
            // baseline by POSITION (`baseline.getOrNull(i)`) contradicted the
            // KDoc two lines above: one call that inserted or removed a block
            // shifted every later entry, so an untouched block compared unequal
            // to the wrong baseline entry, looked "edited", and wrote its stale
            // copy back over a sibling's real edit.
            for (edited in worked) {
                val target = indexById[edited.id] ?: continue
                val base = baseline[target]
                if (edited == base) continue          // this call did not touch the block
                if (shared[target] != edited) shared[target] = edited
            }
        }
    }
}
