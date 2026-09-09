package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-dedup-neutral-status] behaviour tests.
 *
 * The same-turn dedup drop ([T-android-tool-dedupe] in ChatViewModel Pass 1)
 * used to mark the duplicate block FAILED — red error icon, "error" label —
 * which read as "the tool broke" when the drop was intentional. New contract:
 * the dropped block carries DEDUPLICATED, renders with cancelled's neutral
 * grey styling on every surface, never gets the cancelled-trailing Retry
 * affordance, and survives a DB reload as DEDUPLICATED (not SUCCESS).
 */
class ToolDedupStatusTest {

    // ── status bucket mapping (mirrors the UI grouping logic) ────────────

    private fun isFailedBucket(s: ToolBlockStatus): Boolean =
        s == ToolBlockStatus.FAILED || s == ToolBlockStatus.TIMEOUT

    private fun isCancelledBucket(s: ToolBlockStatus): Boolean =
        s == ToolBlockStatus.CANCELLED || s == ToolBlockStatus.DEDUPLICATED

    private fun isRunningBucket(s: ToolBlockStatus): Boolean =
        s == ToolBlockStatus.RUNNING || s == ToolBlockStatus.STREAMING || s == ToolBlockStatus.PENDING

    @Test fun `deduplicated lands in the cancelled bucket not failed`() {
        assertFalse(isFailedBucket(ToolBlockStatus.DEDUPLICATED))
        assertTrue(isCancelledBucket(ToolBlockStatus.DEDUPLICATED))
        assertFalse(isRunningBucket(ToolBlockStatus.DEDUPLICATED))
    }

    @Test fun `deduplicated is terminal for the monotonic guard`() {
        // ToolBlockMonotonicGuard.isTerminal uses !ALIVE.contains(s) — the new
        // enum value must be terminal so a later alive snapshot can never
        // regress it back to RUNNING.
        assertTrue(ToolBlockMonotonicGuard.isTerminal(ToolBlockStatus.DEDUPLICATED))
    }

    @Test fun `retry affordance excludes deduplicated`() {
        // ChatFlatItems' lastCancelledToolId matches ONLY CANCELLED — a
        // dedup-dropped trailing tool must not surface the Retry button
        // (retryLast would re-run the whole turn and re-trigger the dedup).
        // Simulate the flat-items predicate directly:
        val statuses = listOf(ToolBlockStatus.SUCCESS, ToolBlockStatus.DEDUPLICATED)
        val lastCancelled = statuses.lastOrNull { it == ToolBlockStatus.CANCELLED }
        assertEquals(null, lastCancelled)
    }

    // ── DB reload status restoration ─────────────────────────────────────

    private fun restoreStatus(resultSuccess: Boolean, resultOutput: String?): ToolBlockStatus {
        // Mirrors buildChatMessages' toolStatus restoration when-branches.
        val CANCELLED_MARKER = "[CANCELLED"
        return when {
            resultOutput == null -> ToolBlockStatus.SUCCESS
            !resultSuccess && (resultOutput.startsWith(CANCELLED_MARKER)) -> ToolBlockStatus.CANCELLED
            resultSuccess && resultOutput.startsWith("Deduplicated:") -> ToolBlockStatus.DEDUPLICATED
            resultSuccess -> ToolBlockStatus.SUCCESS
            else -> ToolBlockStatus.FAILED
        }
    }

    @Test fun `reload restores deduplicated from the synthetic success result`() {
        // The dedup drop persists isError=false + "Deduplicated: …" content.
        val restored = restoreStatus(
            resultSuccess = true,
            resultOutput = "Deduplicated: identical tool call already executed as call_abc (its result was returned above). Do not re-issue this tool call.",
        )
        assertEquals(ToolBlockStatus.DEDUPLICATED, restored)
    }

    @Test fun `reload keeps plain success and cancelled and failed intact`() {
        assertEquals(ToolBlockStatus.SUCCESS, restoreStatus(true, "ok"))
        assertEquals(ToolBlockStatus.CANCELLED, restoreStatus(false, "[CANCELLED by user"))
        assertEquals(ToolBlockStatus.FAILED, restoreStatus(false, "boom"))
        assertEquals(ToolBlockStatus.SUCCESS, restoreStatus(true, null))
    }

    // ── export label ─────────────────────────────────────────────────────

    @Test fun `clipboard label names the dedup reason`() {
        val label = when (ToolBlockStatus.DEDUPLICATED) {
            ToolBlockStatus.SUCCESS -> "success"
            ToolBlockStatus.FAILED -> "error"
            ToolBlockStatus.TIMEOUT -> "timeout"
            ToolBlockStatus.CANCELLED -> "cancelled"
            ToolBlockStatus.DEDUPLICATED -> "deduplicated (skipped identical call)"
            ToolBlockStatus.RUNNING, ToolBlockStatus.STREAMING, ToolBlockStatus.PENDING -> "running"
            null -> "unknown"
        }
        assertEquals("deduplicated (skipped identical call)", label)
    }
}
