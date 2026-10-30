package com.rikkaminis.app.sandbox.offload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full transition matrix for [ModelExecutionLifecycle], every expected value a
 * hard-coded literal — never derived from the constants under test (a derived
 * expectation turns a constant edit into a silently-passing test).
 *
 * WHY THIS FILE EXISTS [FIX-FW-DEAD-SHUTDOWN-LEVER]: the state machine used to
 * take a third parameter, `shutdownRequested`, that the body never read. The
 * parameter (and the main-process marker file behind it) was deleted, so the
 * fix owes a proof that behaviour did not move. Two halves:
 *   1. THIS table pins the post-change matrix: 5 states × 7 quiescence inputs
 *      = 35 cells.
 *   2. The pre-change machine (from `main` @ 039c9ab2) was compiled separately
 *      and run over 5 × 7 × 2 (`shutdownRequested` false/true) = 70 cells; the
 *      post-change output was diffed cell-for-cell against it and matched
 *      exactly (see fix-reports/FIX-FW-DEAD-SHUTDOWN-LEVER.md §3). The `=true`
 *      column is exactly why the delete is safe: every cell already ignored it.
 *
 * The table is deliberately redundant with the narrative tests in
 * [ModelExecutionLifecycleTest] — those explain WHY a cell reads as it does,
 * this one catches "someone changed one arm of the when() and moved a cell".
 */
class ModelExecutionLifecycleTransitionTableTest {

    private data class Cell(
        val state: ModelExecutionWorkerState,
        val quiescenceName: String,
        val quiescence: ModelExecutionQuiescenceInput,
        val expectedNext: ModelExecutionWorkerState,
        val expectedKillAfter: Boolean,
    )

    private val idle = ModelExecutionQuiescenceInput(0, 0, 0, streamFileFlushed = true)
    private val active1 = ModelExecutionQuiescenceInput(1, 0, 0, streamFileFlushed = true)
    private val active2Queued1 = ModelExecutionQuiescenceInput(2, 1, 0, streamFileFlushed = true)
    private val queued1 = ModelExecutionQuiescenceInput(0, 1, 0, streamFileFlushed = true)
    private val unacked1 = ModelExecutionQuiescenceInput(0, 0, 1, streamFileFlushed = true)
    private val unflushed = ModelExecutionQuiescenceInput(0, 0, 0, streamFileFlushed = false)
    private val unflushedActive1 = ModelExecutionQuiescenceInput(1, 0, 0, streamFileFlushed = false)

    private val quiescences: List<Pair<String, ModelExecutionQuiescenceInput>> = listOf(
        "idle" to idle,
        "active1" to active1,
        "active2+queued1" to active2Queued1,
        "queued1" to queued1,
        "unacked1" to unacked1,
        "unflushed" to unflushed,
        "unflushed+active1" to unflushedActive1,
    )

    private fun cellsFor(
        state: ModelExecutionWorkerState,
        // One entry per quiescence input, in `quiescences` order:
        // next state, then shouldKill(after-transition state, input).
        expectations: List<Pair<ModelExecutionWorkerState, Boolean>>,
    ): List<Cell> = quiescences.mapIndexed { i, (name, q) ->
        Cell(state, name, q, expectations[i].first, expectations[i].second)
    }

    private val table: List<Cell> =
        cellsFor(
            ModelExecutionWorkerState.ACTIVE,
            listOf(
                ModelExecutionWorkerState.STOPPING to true,
                ModelExecutionWorkerState.ACTIVE to false,
                ModelExecutionWorkerState.ACTIVE to false,
                ModelExecutionWorkerState.ACTIVE to false,
                ModelExecutionWorkerState.ACTIVE to false,
                ModelExecutionWorkerState.ACTIVE to false,
                ModelExecutionWorkerState.ACTIVE to false,
            ),
        ) +
            cellsFor(
                ModelExecutionWorkerState.QUIESCE_PENDING,
                listOf(
                    ModelExecutionWorkerState.STOPPING to true,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                ),
            ) +
            cellsFor(
                ModelExecutionWorkerState.DRAINED,
                listOf(
                    ModelExecutionWorkerState.STOPPING to true,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                ),
            ) +
            cellsFor(
                ModelExecutionWorkerState.STOPPING,
                listOf(
                    // Only quiescent STOPPING reaches DEAD; shouldKill is then
                    // asked about DEAD (the service passes the NEW state), so it
                    // is false — the kill already happened inside transition.
                    ModelExecutionWorkerState.DEAD to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                    ModelExecutionWorkerState.ACTIVE to false,
                ),
            ) +
            cellsFor(
                ModelExecutionWorkerState.DEAD,
                listOf(
                    ModelExecutionWorkerState.DEAD to false,
                    ModelExecutionWorkerState.DEAD to false,
                    ModelExecutionWorkerState.DEAD to false,
                    ModelExecutionWorkerState.DEAD to false,
                    ModelExecutionWorkerState.DEAD to false,
                    ModelExecutionWorkerState.DEAD to false,
                    ModelExecutionWorkerState.DEAD to false,
                ),
            )

    @Test
    fun `transition takes no shutdown flag`() {
        // [FIX-FW-DEAD-SHUTDOWN-LEVER] The deleted parameter must not creep
        // back even as a defaulted one — `shutdownRequested: Boolean = false`
        // compiles clean at every call site and is invisible to every
        // behavioural test, which is exactly how the original dead lever
        // survived ("plumbed through for future drain semantics"). The state
        // machine may gain a flag only when a `when` arm READS it; this
        // assertion is the tripwire that forces that question.
        val transition = ModelExecutionLifecycle::class.java.declaredMethods
            .single { it.name == "transition" }
        assertEquals(
            "transition(state, quiescence) — a third parameter must be read by the machine",
            2,
            transition.parameterTypes.size,
        )
    }

    @Test
    fun `transition matrix has the expected shape`() {
        // 5 states × 7 quiescence inputs = 35 cells. A cell count guard keeps a
        // future edit from "passing" by dropping arms of the table.
        assertEquals(35, table.size)
        assertEquals(5, table.map { it.state }.distinct().size)
        assertEquals(7, table.map { it.quiescenceName }.distinct().size)
    }

    @Test
    fun `every cell of the transition matrix matches its literal`() {
        for (cell in table) {
            val next = ModelExecutionLifecycle.transition(cell.state, cell.quiescence)
            assertEquals(
                "transition(${cell.state}, ${cell.quiescenceName})",
                cell.expectedNext,
                next,
            )
            assertEquals(
                "shouldKill(after ${cell.state}→$next, ${cell.quiescenceName})",
                cell.expectedKillAfter,
                ModelExecutionLifecycle.shouldKill(next, cell.quiescence),
            )
        }
    }

    @Test
    fun `shouldKill is true only from STOPPING while quiescent`() {
        // Independent of the table: the kill predicate's PREMISE (the service
        // evaluates it on the state transition() just produced, so any cell
        // where the transition produced STOPPING and the input is quiescent
        // must be the only true ones).
        var trueCells = 0
        for (cell in table) {
            val next = ModelExecutionLifecycle.transition(cell.state, cell.quiescence)
            val kill = ModelExecutionLifecycle.shouldKill(next, cell.quiescence)
            if (kill) {
                trueCells++
                assertEquals(ModelExecutionWorkerState.STOPPING, next)
                assertTrue(ModelExecutionLifecycle.isQuiescent(cell.quiescence))
            } else {
                // No cell may emit STOPPING without then asking to kill: with a
                // quiescent input the service always calls shouldKill on it.
                assertTrue(
                    "STOPPING without a kill decision at ${cell.state}/${cell.quiescenceName}",
                    next != ModelExecutionWorkerState.STOPPING ||
                        !ModelExecutionLifecycle.isQuiescent(cell.quiescence),
                )
            }
        }
        // ACTIVE / QUIESCE_PENDING / DRAINED × idle are the three cells whose
        // transition emits STOPPING, i.e. exactly the three kill decisions.
        assertEquals(3, trueCells)
    }
}
