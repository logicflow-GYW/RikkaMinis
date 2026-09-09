package com.rikkaminis.app.ui.chat

/**
 * [T-streamlining-thinking-fix] Monotonic terminal-state guard for tool blocks
 * published through the streaming side-channel ([StreamingDelta]).
 *
 * During a live turn, tool blocks transition along a one-way path
 *  alive (STREAMING / PENDING / RUNNING) → terminal (SUCCESS / FAILED /
 *  TIMEOUT / CANCELLED).
 *
 * The UI consumes these blocks via a conflated / sampled propagation path
 * (ChatScreen's `combine(...).conflate().sample(80L)` → StableChatRowLedger),
 * and there was a class of "tool card stuck as RUNNING even though it finished"
 * bugs where the final terminal flip was lost and the UI froze on the alive
 * state. This guard makes the terminal flip idempotent at the source: once a
 * block has been published in a terminal state it may never regress to an
 * alive state in a later snapshot.
 *
 * Pure function — no Android/AppLogger dependency, fully JVM-testable.
 * The caller is responsible for emitting any diagnostic warning.
 */
object ToolBlockMonotonicGuard {

    private val ALIVE = setOf(
        ToolBlockStatus.STREAMING,
        ToolBlockStatus.PENDING,
        ToolBlockStatus.RUNNING,
    )

    /** @return true when [s] is a one-way-ended (terminal) status. */
    fun isTerminal(s: ToolBlockStatus?): Boolean =
        s != null && !ALIVE.contains(s)

    /**
     * Represents a regression that was clamped by [guard]: the block with
     * [blockId] had moved from [prevStatus] (terminal) back to [nextStatus]
     * (alive), and [guard] preserved the terminal value.
     */
    data class Regression(
        val blockId: String,
        val prevStatus: ToolBlockStatus,
        val nextStatus: ToolBlockStatus,
    )

    data class Result(
        val blocks: List<AssistantBlock>,
        val regressions: List<Regression>,
    )

    /**
     * Clamp any terminal→alive regression: for each block id that is terminal
     * in [prev] but alive in [next], keep [prev]'s terminal block instead of
     * the regressed one from [next]. All other blocks pass through [next]
     * unchanged (order preserved). If [prev] is null/empty, or a given id is
     * absent from prev, it is passed through untouched.
     */
    fun guard(prev: List<AssistantBlock>?, next: List<AssistantBlock>): Result {
        if (prev.isNullOrEmpty()) {
            return Result(next, emptyList())
        }
        val prevById = prev.associateBy { it.id }
        val regressions = mutableListOf<Regression>()
        val guarded = next.map { candidate ->
            val prevBlock = prevById[candidate.id] ?: return@map candidate
            val prevStatus = prevBlock.toolStatus
            val nextStatus = candidate.toolStatus
            if (prevStatus != null && isTerminal(prevStatus) && !isTerminal(nextStatus)) {
                // prevStatus is terminal (non-null). nextStatus is alive OR
                // null — both are a regression away from terminal; keep prev's
                // terminal block and (if nextStatus is non-null) log it.
                if (nextStatus != null) {
                    regressions += Regression(candidate.id, prevStatus, nextStatus)
                }
                prevBlock
            } else {
                candidate
            }
        }
        return Result(guarded, regressions)
    }
}
