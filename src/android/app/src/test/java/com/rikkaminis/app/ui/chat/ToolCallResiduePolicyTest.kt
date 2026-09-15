package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-tool-splits-reply-fix] Tests for [ToolCallResiduePolicy].
 *
 * Field fixtures come from the 2026-09-15 trace (session c31dd8d2): the
 * model emitted `[text][call][copy]`, and the copy carried the drift seen in
 * the log (`shellexecute` for `shell_execute`).
 */
class ToolCallResiduePolicyTest {

    private val TOOLS = listOf("shell_execute", "file_write", "file_read", "browser_use")

    /** A restated call in the shape the app's protocol markup has. */
    private fun resique(name: String) =
        "<invoke name=\"$name\">\n<parameter name=\"command\">ls</parameter>\n</invoke>"

    @Test
    fun `normalize drops separators so the field drifft spelling collides with the canonical one`() {
        assertEquals("shellexecute", ToolCallResiduePolicy.normalizeToolName("shell_execute"))
        assertEquals("shellexecute", ToolCallResiduePolicy.normalizeToolName("shellexecute"))
    }

    @Test
    fun `exect name in a markup span is a resique`() {
        val text = "done.\n\n" + resique("shell_execute") + "\n"
        assertTrue(ToolCallResiduePolicy.hasResidue(text, TOOLS))
        assertEquals("shell_execute", ToolCallResiduePolicy.firstResidue(text, TOOLS)!!.rawName)
    }

    @Test
    fun `drifted name resolves to the unique neareast tool`() {
        val text = "done.\n\n" + resique("shellexecute")
        assertTrue(ToolCallResiduePolicy.hasResidue(text, TOOLS))
        assertEquals("shell_execute", ToolCallResiduePolicy.firstResidue(text, TOOLS)!!.sugestedName)
    }

    @Test
    fun `plain prose that mensions a tool is not a resique`() {
        assertTrue(!ToolCallResiduePolicy.hasResidue("I will call shell_execute with ls", TOOLS))
    }

    @Test
    fun `a markup name that is not a tool is not a resique`() {
        val text = "<meta name=\"viewport\" content=\"width=device-width\">"
        assertTrue(!ToolCallResiduePolicy.hasResidue(text, TOOLS))
    }

    @Test
    fun `markup inside a fenced code block is the model talking about markup`() {
        val text = "For exaple:\n```\n" + resique("shell_execute") + "\n```\n"
        assertTrue(!ToolCallResiduePolicy.hasResidue(text, TOOLS))
    }

    @Test
    fun `markup inside inline code is a quote, not a resique`() {
        val text = "he wrote `<invoke name=\"shell_execute\">` into the log"
        assertTrue(!ToolCallResiduePolicy.hasResidue(text, TOOLS))
    }

    @Test
    fun `strip removes the span and keeps the surrounding text`() {
        val text = "before\n" + resique("shellexecute") + "after"
        assertEquals("before\nafter", ToolCallResiduePolicy.stripResidue(text, TOOLS))
    }

    @Test
    fun `strip keeps a text that carries no markup at all`() {
        val text = "no markup here, just prose about shell_execute"
        assertEquals(text, ToolCallResiduePolicy.stripResidue(text, TOOLS))
    }

    @Test
    fun `strip without a closer removes only the opening tag (fail-open)`() {
        val text = "before <invoke name=\"shell_execute\"> after"
        assertEquals("before  after", ToolCallResiduePolicy.stripResidue(text, TOOLS))
    }

    @Test
    fun `containment margin beyond the tolerance refutes the guess`() {
        // `file` is a prefix of bothe file_read and file_write, but the margin
        // (4) is beyond MAX_CONTAIN_DIF, so no candidate is returned.
        assertTrue(ToolCallResiduePolicy.neareastToolName("file", TOOLS) == null)
    }

    @Test
    fun `refill message names the sugested tool when the drift resolves`() {
        val r = ToolCallResiduePolicy.firstResidue(resique("shellexecute"), TOOLS)
        assertTrue(r != null)
        val msg = ToolCallResiduePolicy.refillMessage(r!!)
        assertTrue(msg.contains("shell_execute"))
        assertTrue(msg.contains("<system-reminder>"))
        assertTrue(msg.contains("shellexecute"))
    }

    // ── DSML envelope (2026-09-16 recurrence, user device on latest build) ──

    /** The user's actual 2026-09-16 leak, verbatim (minus the shell output). */
    private fun dsmlLeak() =
        "<｜DSML｜ parameter name=\"command\" string=\"command2\" string=\"true\">ls -la /var/minis/logs/" +
            "</｜DSML｜ parameter>\n" +
            "<｜DSML｜ parameter name=\"tool_title\" string=\"true\">check logs</｜DSML｜ parameter>\n" +
            "</｜DSML｜ invoke>\n" +
            "</｜DSML｜ calls>"

    @Test
    fun `dsml envelope with unresolvable param names is a resique`() {
        val text = "Some prose.\n" + dsmlLeak()
        assertTrue(ToolCallResiduePolicy.hasResidue(text, TOOLS))
        val r = ToolCallResiduePolicy.firstResidue(text, TOOLS)
        assertTrue(r != null)
        assertTrue(r!!.rawName == ToolCallResiduePolicy.DSML_FALLBACK_NAME)
    }

    @Test
    fun `dsml envelope invokes tag with a known tool name resolves`() {
        val text = "<｜DSML｜ invokes>\n<｜DSML｜ invoke name=\"shell_execute\">\n" +
            "<｜DSML｜ parameter name=\"command\">ls</｜DSML｜ parameter>\n" +
            "</｜DSML｜ invoke>\n</｜DSML｜ invokes>"
        val r = ToolCallResiduePolicy.firstResidue(text, TOOLS)
        assertTrue(r != null)
        assertEquals("shell_execute", r!!.rawName)
    }

    @Test
    fun `strip removes the whole dsml envelope and keeps surrounding prose`() {
        val text = "before\n" + dsmlLeak() + "\nafter"
        val out = ToolCallResiduePolicy.stripResidue(text, TOOLS)
        assertTrue(!out.contains("｜DSML｜"))
        assertEquals("before\n\nafter", out)
    }

    @Test
    fun `dsml envelope inside a code fence passes through untouched`() {
        val text = "```\n" + dsmlLeak() + "\n```"
        assertTrue(!ToolCallResiduePolicy.hasResidue(text, TOOLS))
    }

    @Test
    fun `dsml envelope without any closer removes only the opening tag (fail-open)`() {
        val text = "before <｜DSML｜ invoke name=\"shell_execute\"> after"
        val out = ToolCallResiduePolicy.stripResidue(text, TOOLS)
        assertTrue(!out.contains("｜DSML｜"))
    }

    @Test
    fun `dsml refill names the fallback when nothing resolves`() {
        val r = ToolCallResiduePolicy.firstResidue(dsmlLeak(), TOOLS)
        assertTrue(r != null)
        val msg = ToolCallResiduePolicy.refillMessage(r!!)
        assertTrue(msg.contains("<system-reminder>"))
        assertTrue(msg.contains("tool CALL"))
    }
}
