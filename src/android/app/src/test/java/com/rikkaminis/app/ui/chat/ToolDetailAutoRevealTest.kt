package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [shouldAutoRevealMore] — the pure predicate behind the tool
 * sheet's lazy-reveal gate (ChatToolDetailUI.kt, LazyRevealToolText).
 *
 * [F-11 meta-audit-1028] The Compose side (per-block ScrollState, keyed
 * remembers, derivedStateOf) cannot run on the JVM and is pinned by source-shape
 * assertions instead; the arithmetic that decides whether a batch may be
 * revealed without the user asking IS pure, and that is where the reported
 * defect lived: the old inline form checked only `value >= max - 600`, which the
 * very first frame satisfies whenever the content overflows the viewport by
 * 1..600 px — the gate opened itself and every pending batch was revealed
 * without a single scroll.
 *
 * Expectations are hard-coded literals (never derived from the production
 * constant), so re-tuning the 600 px window cannot silently rewrite the test.
 */
class ToolDetailAutoRevealTest {

    // ── the reported defect: first frame, content overflows 1..600 px ───────

    @Test fun `first frame with a one px overflow must not auto reveal`() {
        assertFalse(shouldAutoRevealMore(0, 1))
    }

    @Test fun `first frame with a small overflow must not auto reveal`() {
        assertFalse(shouldAutoRevealMore(0, 100))
    }

    @Test fun `first frame exactly at the window edge must not auto reveal`() {
        assertFalse(shouldAutoRevealMore(0, 600))
    }

    // ── long output, no scroll: gate closed either way ──────────────────────

    @Test fun `long output without a scroll stays closed`() {
        assertFalse(shouldAutoRevealMore(0, 5000))
    }

    // ── real scroll near the bottom: reveal ─────────────────────────────────

    @Test fun `scrolled near the bottom reveals`() {
        assertTrue(shouldAutoRevealMore(4500, 5000))
    }

    @Test fun `scrolled to the bottom reveals`() {
        assertTrue(shouldAutoRevealMore(5000, 5000))
    }

    @Test fun `exactly at the threshold reveals`() {
        assertTrue(shouldAutoRevealMore(400, 1000))
    }

    @Test fun `one px past the threshold holds`() {
        assertFalse(shouldAutoRevealMore(399, 1000))
    }

    @Test fun `scrolled but still mid document holds`() {
        assertFalse(shouldAutoRevealMore(1000, 5000))
    }

    // ── content fits / unmeasured: the manual buttons are the way through ───

    @Test fun `content that fits the viewport never auto reveals`() {
        assertFalse(shouldAutoRevealMore(0, 0))
    }

    @Test fun `unmeasured sentinel never auto reveals`() {
        assertFalse(shouldAutoRevealMore(0, Int.MAX_VALUE))
    }

    @Test fun `tiny scrollable range once scrolled reveals`() {
        assertTrue(shouldAutoRevealMore(1, 10))
    }
}
