package com.rikkaminis.app.diagnostics

import com.rikkaminis.app.logging.AppLogger

/**
 * Which render / row-build path actually runs?
 *
 * [T-android-liveness-census] Why this exists: the existing rulers
 * ([StreamRenderProfiler]'s 20-tick flush, the `[Perf][ColdParse]` breadcrumb)
 * only speak once their threshold is crossed or their branch is entered. So
 * "no line in the log" is indistinguishable from "this branch never runs" —
 * a ruler that cannot be told apart from a dead ruler, which is exactly how
 * two live-path rulers went dark without anyone noticing.
 *
 * The census records ONE event per branch entry and always reports a branch
 * the FIRST time it is ever seen, then a compact summary every [flushEvery]
 * events. A branch that is still silent after a full log window is then
 * provably unused rather than merely unmeasured.
 */
internal class RenderPathCensus(
    private val emit: (String) -> Unit,
    private val flushEvery: Int = 50,
) {
    enum class Branch {
        /** Frozen fragment, parse cache HIT — renders synchronously, no parse. */
        FROZEN_HIT,

        /** Frozen fragment, cache MISS — off-main parse (the cold-parse ruler). */
        FROZEN_MISS,

        /** Live streaming tail, off-main parse (the StreamRender ruler). */
        LIVE_PARSE,

        /** Live fragment over the degrade threshold — plain-text tail, no parse. */
        LIVE_DEGRADE,

        /** Chat rows: ledger reconcile tick (the live row path). */
        ROW_LEDGER,

        /** Chat rows: cold build INSIDE the collector (flatItems was empty). */
        ROW_COLD_BUILD,

        /** Chat rows: full rebuild + ledger reseed. */
        ROW_RESEED,
    }

    private val branches = Branch.values()
    private val counts = IntArray(branches.size)
    private val seen = BooleanArray(branches.size)
    private var events = 0
    private var maxRows = 0

    @Synchronized
    fun record(branch: Branch, rows: Int = -1) {
        if (rows > maxRows) maxRows = rows
        val i = branch.ordinal
        counts[i]++
        events++
        if (!seen[i]) {
            seen[i] = true
            emit("first=${branch.name.lowercase()} ${summary()}")
        }
        if (events >= flushEvery) {
            emit("window ${summary()}")
            java.util.Arrays.fill(counts, 0)
            events = 0
            maxRows = 0
        }
    }

    @Synchronized
    fun snapshot(): String = summary()

    private fun summary(): String =
        branches.joinToString(" ") { "${it.name.lowercase()}=${counts[it.ordinal]}" } + " maxRows=$maxRows"
}

/** Process-wide census wired to the file log (`[RenderCensus] ...`). */
internal object Liveness {
    private val census = RenderPathCensus({ line -> AppLogger.info("RenderCensus", "[RenderCensus] $line") })

    fun record(branch: RenderPathCensus.Branch, rows: Int = -1) = census.record(branch, rows)

    fun snapshot(): String = census.snapshot()
}
