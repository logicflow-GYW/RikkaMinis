package com.rikkaminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [FULLSCAN-1119 F-13] `file_read` with `direction="tail"` and no `lines` used
 * to fall through to the HEAD branch (`skip = 0`, `limit = null`): the caller
 * asked for the end of the file and got the beginning, and the header claimed
 * the whole file.
 *
 * Driven through [FileReadTool.readFileText] — the real production reader —
 * with a real 200-line temp [File]; no Android Context, no PRoot resolution
 * (the tool's `execute` only prepends path resolution and argument parsing, so
 * everything that decides WHICH LINES come back is under test here).
 *
 * The head direction is asserted alongside the tail on purpose: the historical
 * "no `lines` = read until max_length" contract belongs to head only, and a
 * tail fix that changed it would be a second, silent regression.
 */
class FileReadToolTailWindowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun twoHundredLineFile(): File {
        val f = tmp.newFile("two-hundred.txt")
        f.writeText((1..200).joinToString("\n") { "line%03d".format(it) })
        return f
    }

    private fun read(f: File, direction: String, lines: Int?): ToolExecutionResult =
        FileReadTool.readFileText(
            file = f,
            displayPath = "/tmp/two-hundred.txt",
            direction = direction,
            offset = 1,
            requestedLines = lines,
            maxLength = 15_000,
            toolTitle = "Read tail",
        )

    private val ToolExecutionResult.header: String
        get() = output.substringBefore('\n')

    @Test
    fun `tail without lines returns the LAST default window and says so in the header`() {
        val result = read(twoHundredLineFile(), "tail", null)

        assertTrue("call succeeded", result.success)
        assertTrue(
            "header must place the window at the end of the file, got: ${result.header}",
            result.header.contains("showing 101-200 of 200"),
        )
        assertTrue("last line present", result.output.contains("line200"))
        assertTrue("window starts at line 101", result.output.contains("line101"))
        assertFalse("must not contain the head of the file", result.output.contains("line100"))
        assertFalse("must not contain the very first line", result.output.contains("line001"))
    }

    @Test
    fun `head without lines is unchanged - it still reads from the top`() {
        val result = read(twoHundredLineFile(), "head", null)

        assertTrue("call succeeded", result.success)
        assertTrue(
            "head keeps the whole-file window, got: ${result.header}",
            result.header.contains("showing 1-200 of 200"),
        )
        assertTrue("first line present", result.output.contains("line001"))
        assertTrue("last line present (under the 15 KB cap)", result.output.contains("line200"))
    }

    @Test
    fun `tail with an explicit count still uses that count`() {
        val result = read(twoHundredLineFile(), "tail", 10)

        assertTrue(result.success)
        assertTrue(
            "explicit tail window, got: ${result.header}",
            result.header.contains("showing 191-200 of 200"),
        )
        assertTrue(result.output.contains("line191"))
        assertFalse(result.output.contains("line190"))
    }

    @Test
    fun `head with an explicit count is unchanged`() {
        val result = read(twoHundredLineFile(), "head", 10)

        assertTrue(result.success)
        assertTrue("explicit head window, got: ${result.header}", result.header.contains("showing 1-10 of 200"))
        assertTrue(result.output.contains("line010"))
        assertFalse(result.output.contains("line011"))
    }

    @Test
    fun `a tail window larger than the file still reports the whole file`() {
        val result = read(twoHundredLineFile(), "tail", 500)

        assertTrue(result.success)
        assertTrue("clamped to the file, got: ${result.header}", result.header.contains("showing 1-200 of 200"))
        assertTrue(result.output.contains("line001"))
    }

    @Test
    fun `the head direction keeps the negative-count failure shape`() {
        try {
            read(twoHundredLineFile(), "head", -3)
            throw AssertionError("expected the legacy subList-shaped IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("message names the indices: ${e.message}", e.message!!.contains("fromIndex(0) > toIndex(-3)"))
        }
    }

    @Test
    fun `effective line count maps only the tail direction to the default window`() {
        assertEquals(100, DEFAULT_TAIL_LINES)
        assertEquals(100, effectiveLineCount("tail", null))
        assertNull("head keeps 'read until max_length'", effectiveLineCount("head", null))
        assertEquals(7, effectiveLineCount("tail", 7))
        assertEquals(7, effectiveLineCount("head", 7))
        assertEquals(-1, effectiveLineCount("tail", -1))
    }
}
