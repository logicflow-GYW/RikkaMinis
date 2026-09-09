package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure formatting functions in [ChatToolFormatting].
 *
 * These functions are defined in ChatToolFormatting.kt alongside
 * Compose-dependent functions (toolAccentColor, toolIconFor). Since
 * the JVM only resolves constant pool entries lazily, calling only
 * pure functions from tests works without Compose on the classpath.
 */
class ChatFormattingTest {

    // ── formatStepDuration ────────────────────────────────────────────

    @Test
    fun `formatStepDuration 0 seconds`() {
        assertEquals("0s", formatStepDuration(0, false))
    }

    @Test
    fun `formatStepDuration under 60 seconds`() {
        assertEquals("45s", formatStepDuration(45, false))
    }

    @Test
    fun `formatStepDuration exactly 60 seconds`() {
        assertEquals("1m", formatStepDuration(60, false))
    }

    @Test
    fun `formatStepDuration 90 seconds`() {
        assertEquals("1m30s", formatStepDuration(90, false))
    }

    @Test
    fun `formatStepDuration exactly 1 hour`() {
        assertEquals("1h", formatStepDuration(3600, false))
    }

    @Test
    fun `formatStepDuration 1h 12m`() {
        assertEquals("1h12m", formatStepDuration(4320, false))
    }

    @Test
    fun `formatStepDuration 2h 0m`() {
        assertEquals("2h", formatStepDuration(7200, false))
    }

    @Test
    fun `formatStepDuration still running suffix`() {
        assertEquals("12s…", formatStepDuration(12, true))
    }

    @Test
    fun `formatStepDuration still running with minutes`() {
        assertEquals("2m30s…", formatStepDuration(150, true))
    }

    @Test
    fun `formatStepDuration negative clamps to 0`() {
        assertEquals("0s", formatStepDuration(-5, false))
    }

    @Test
    fun `formatStepDuration 59 seconds`() {
        assertEquals("59s", formatStepDuration(59, false))
    }

    @Test
    fun `formatStepDuration 1s with still running`() {
        assertEquals("1s…", formatStepDuration(1, true))
    }

    // ── formatToolDuration ────────────────────────────────────────────

    @Test
    fun `formatToolDuration under 1 second`() {
        assertEquals("0.5s", formatToolDuration(500))
    }

    @Test
    fun `formatToolDuration exactly 1 second`() {
        assertEquals("1s", formatToolDuration(1000))
    }

    @Test
    fun `formatToolDuration 45 seconds`() {
        assertEquals("45s", formatToolDuration(45000))
    }

    @Test
    fun `formatToolDuration 1 minute`() {
        assertEquals("1m 0s", formatToolDuration(60000))
    }

    @Test
    fun `formatToolDuration 2m 30s`() {
        assertEquals("2m 30s", formatToolDuration(150000))
    }

    @Test
    fun `formatToolDuration 0 ms`() {
        assertEquals("0.0s", formatToolDuration(0))
    }

    // ── toolDisplayName ───────────────────────────────────────────────

    @Test
    fun `toolDisplayName shell_execute`() {
        assertEquals("terminal", toolDisplayName("shell_execute"))
    }

    @Test
    fun `toolDisplayName file_read`() {
        assertEquals("file reader", toolDisplayName("file_read"))
    }

    @Test
    fun `toolDisplayName unknown tool returns name`() {
        assertEquals("my_custom_tool", toolDisplayName("my_custom_tool"))
    }

    @Test
    fun `toolDisplayName browser_use`() {
        assertEquals("browser", toolDisplayName("browser_use"))
    }

    // ── toolTitleLabel ────────────────────────────────────────────────

    @Test
    fun `toolTitleLabel shell_execute`() {
        assertEquals("RikkaMinis is using Shell", toolTitleLabel("shell_execute"))
    }

    @Test
    fun `toolTitleLabel unknown tool uses display name`() {
        assertEquals("RikkaMinis is using my_custom_tool", toolTitleLabel("my_custom_tool"))
    }

    @Test
    fun `toolTitleLabel memory_write`() {
        assertEquals("RikkaMinis is using Memory", toolTitleLabel("memory_write"))
    }

    @Test
    fun `toolTitleLabel web_search`() {
        assertEquals("RikkaMinis is using Search", toolTitleLabel("web_search"))
    }
}