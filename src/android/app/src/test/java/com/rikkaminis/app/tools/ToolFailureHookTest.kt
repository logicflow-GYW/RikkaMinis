package com.rikkaminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for ToolFailureHook — deduplication logic + block format
 * correctness.
 *
 * Guards the invariant that the hook is a pure side-channel (no-return, no
 * exception) and that the same recurring failure does not flood ERRORS.md.
 */
class ToolFailureHookTest {

    // ─── helpers ───────────────────────────────────────────────────────────
    private fun hookWithClock(
        writes: MutableList<String> = mutableListOf(),
        clock: () -> Long = { 0L },
        dedupeWindowMs: Long = ToolFailureHook.DEDUPE_WINDOW_MS,
    ) = ToolFailureHook(
        writeErrorBlock = { block -> writes.add(block) },
        clock = clock,
        dedupeWindowMs = dedupeWindowMs,
    )

    // ─── happy path: first failure writes ──────────────────────────────────
    @Test fun firstFailure_writesBlock() {
        val writes = mutableListOf<String>()
        val hook = hookWithClock(writes)

        val written = hook.recordFailure("shell_execute", "command not found: foo", sessionId = "sid-123")

        assertTrue("first failure must write", written)
        assertEquals("exactly one write", 1, writes.size)
        assertTrue("block starts with ## [ERR-", writes[0].startsWith("## [ERR-"))
    }

    // ─── dedupe: same key within window ────────────────────────────────────
    @Test fun sameKey_withinWindow_skipsWrite() {
        val writes = mutableListOf<String>()
        val clock = FakeClock()
        val hook = hookWithClock(writes, clock = clock)

        val first = hook.recordFailure("shell_execute", "command not found: foo", sessionId = "s")
        val second = hook.recordFailure("shell_execute", "command not found: foo", sessionId = "s")

        assertTrue("first write", first)
        assertFalse("second skipped (dedupe)", second)
        assertEquals("only one block written", 1, writes.size)
    }

    // ─── dedupe: same key, different toolName ──────────────────────────────
    @Test fun differentToolName_writesSeparately() {
        val writes = mutableListOf<String>()
        val hook = hookWithClock(writes)

        val a = hook.recordFailure("shell_execute", "command not found: foo", sessionId = "s")
        val b = hook.recordFailure("file_write", "command not found: foo", sessionId = "s")

        assertTrue("shell_execute write", a)
        assertTrue("file_write write (different tool)", b)
        assertEquals("two blocks", 2, writes.size)
    }

    // ─── dedupe: same key, different summary ───────────────────────────────
    @Test fun differentSummary_writesSeparately() {
        val writes = mutableListOf<String>()
        val hook = hookWithClock(writes)

        val a = hook.recordFailure("shell_execute", "command not found: foo", sessionId = "s")
        val b = hook.recordFailure("shell_execute", "command not found: bar", sessionId = "s")

        assertTrue("first write", a)
        assertTrue("second write (different summary)", b)
        assertEquals("two blocks", 2, writes.size)
    }

    // ─── dedupe: after window expiry, writes again ─────────────────────────
    @Test fun sameKey_afterWindowExpiry_writesAgain() {
        val writes = mutableListOf<String>()
        val clock = FakeClock()
        val hook = hookWithClock(writes, clock = clock, dedupeWindowMs = 10_000)

        val first = hook.recordFailure("shell_execute", "command not found: foo", sessionId = "s")
        clock.advance(10_001) // just past the window
        val second = hook.recordFailure("shell_execute", "command not found: foo", sessionId = "s")

        assertTrue("first write", first)
        assertTrue("second write after window expiry", second)
        assertEquals("two blocks", 2, writes.size)
    }

    // ─── block format correctness ──────────────────────────────────────────
    @Test fun blockFormat_containsAllSections() {
        val writes = mutableListOf<String>()
        val hook = hookWithClock(writes)

        hook.recordFailure("shell_execute", "failed: oops", argsJson = """{"command":"echo hi"}""", sessionId = "sid-abc")

        assertEquals(1, writes.size)
        val block = writes[0]

        // Header: ## [ERR-YYYYMMDD-XXX] toolName
        assertTrue("block matches header regex", block.matches(Regex("""## \[ERR-\d{8}-[A-Z0-9]{3}\] shell_execute\n.*""", RegexOption.DOT_MATCHES_ALL)))

        // Sections present
        assertTrue("has ### 摘要", block.contains("### 摘要"))
        assertTrue("has summary text", block.contains("failed: oops"))
        assertTrue("has ### Error", block.contains("### Error"))
        assertTrue("has error body", block.contains("failed: oops"))
        assertTrue("has ### Context", block.contains("### Context"))
        assertTrue("has tool name in context", block.contains("shell_execute"))
        assertTrue("has args in context", block.contains("echo hi"))
        assertTrue("has session id", block.contains("sid-abc"))
        assertTrue("has ### 建议修复", block.contains("### 建议修复"))
        assertTrue("has ### 元数据", block.contains("### 元数据"))
        assertTrue("has footer ---", block.contains("---"))

        // Timestamp format
        assertTrue("has ISO timestamp", block.contains("**记录时间**: "))
    }

    // ─── large output truncation ───────────────────────────────────────────
    @Test fun largeOutput_truncatedToMaxLength() {
        val writes = mutableListOf<String>()
        val hook = hookWithClock(writes)
        val large = "x".repeat(5000)

        hook.recordFailure("shell_execute", large, sessionId = "s")

        val block = writes[0]
        // The Error block contains the truncated body (2000 chars) inside ```
        val errorStart = block.indexOf("```\n") + 4
        val errorEnd = block.indexOf("\n```", errorStart)
        val errorBody = block.substring(errorStart, errorEnd)
        assertTrue("error body truncated to 2000", errorBody.length <= ToolFailureHook.ERROR_BODY_MAX_LENGTH)
        assertEquals(ToolFailureHook.ERROR_BODY_MAX_LENGTH, errorBody.length)
    }

    // ─── empty output summary ──────────────────────────────────────────────
    @Test fun emptyOutput_summarizesAsEmpty() {
        val writes = mutableListOf<String>()
        val hook = hookWithClock(writes)

        hook.recordFailure("shell_execute", "   ", sessionId = "s")

        val block = writes[0]
        assertTrue("empty summary uses '(empty)'", block.contains("(empty)"))
    }

    // ─── summary truncation ────────────────────────────────────────────────
    @Test fun summaryTruncated_toMaxLength() {
        val writes = mutableListOf<String>()
        val hook = hookWithClock(writes)
        val longLine = "a".repeat(300)

        hook.recordFailure("shell_execute", longLine, sessionId = "s")

        val block = writes[0]
        // Summary line is inside ### 摘要 section, first line after it
        val summaryStart = block.indexOf("### 摘要\n") + "### 摘要\n".length
        val summaryEnd = block.indexOf("\n", summaryStart)
        val summary = block.substring(summaryStart, summaryEnd).trim()
        assertTrue("summary truncated to 120", summary.length <= ToolFailureHook.SUMMARY_MAX_LENGTH)
        assertEquals("summary should be exactly 120 chars for 300-char input", ToolFailureHook.SUMMARY_MAX_LENGTH.toLong(), summary.length.toLong())
    }

    // ─── multi-line output: summary is first line ───────────────────────────
    @Test fun multiLineOutput_summaryIsFirstLine() {
        val writes = mutableListOf<String>()
        val hook = hookWithClock(writes)

        hook.recordFailure("shell_execute", "first line\nsecond line\nthird line", sessionId = "s")

        val block = writes[0]
        assertTrue("summary is first line", block.contains("first line"))
        // second/third lines should NOT appear in the summary (they're in Error body)
        assertTrue("Error body contains second line", block.contains("second line"))
        assertTrue("Error body contains third line", block.contains("third line"))
    }

    // ─── return value false when deduplicated ──────────────────────────────
    @Test fun deduplicated_call_returnsFalse() {
        val writes = mutableListOf<String>()
        val hook = hookWithClock(writes)

        hook.recordFailure("shell_execute", "oops", sessionId = "s")
        val result = hook.recordFailure("shell_execute", "oops", sessionId = "s")

        assertFalse(result)
    }

    // ─── no sessionId / argsJson ───────────────────────────────────────────
    @Test fun nullArgsAndSession_doesNotCrash() {
        val writes = mutableListOf<String>()
        val hook = hookWithClock(writes)

        // Should not throw
        hook.recordFailure("shell_execute", "oops")
        hook.recordFailure("shell_execute", "oops2")

        assertEquals(2, writes.size)
    }

    // ─── FakeClock ─────────────────────────────────────────────────────────
    private class FakeClock(private var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(ms: Long) { now += ms }
    }
}