package com.rikkaminis.app.provider

/**
 * [feat/chat-tuning-panel-b] The (maxEdge, quality) descent ladder walked by
 * [ImageBudget.compressUnderBudget] when the first re-encode fails to fit the
 * byte budget. Factored into a pure object so the "defaults reproduce the
 * pre-panel ladder exactly" invariant is unit-testable on the JVM (no Bitmap).
 *
 * Defaults (2000, 80) produce the exact pre-panel ladder:
 * 2000/80 → 1600/75 → 1280/70 → 1024/65 → 896/55 → 768/50 → 640/45.
 */
object ImageBudgetLadder {
    // Integer factors (num/den) instead of floats so 0.64×2000 is exactly
    // 1280, not 1279 after truncation.
    private val EDGE_NUM = listOf(1, 4, 16, 64, 112, 48, 8)
    private val EDGE_DEN = listOf(1, 5, 25, 125, 250, 125, 25)
    private val QUALITY_DROPS = listOf(0, -5, -10, -15, -25, -30, -35)

    /** Walk order: [maxEdge]/[quality] first (loosest last). Never returns
     *  a quality below 30 — below that JPEG artifacts hurt more than the
     *  bytes saved. */
    fun ladderFor(maxEdge: Int, quality: Int): List<Pair<Int, Int>> {
        val edge0 = maxEdge.coerceIn(64, 20_000)
        val q0 = quality.coerceIn(1, 100)
        return EDGE_NUM.indices.map { i ->
            val edge = (edge0.toLong() * EDGE_NUM[i] / EDGE_DEN[i]).toInt().coerceAtLeast(1)
            val q = (q0 + QUALITY_DROPS[i]).coerceIn(30, 100)
            edge to q
        }
    }
}
