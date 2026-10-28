package com.rikkaminis.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [meta-audit-1029 R3 B11] F-01 — the summarizer input framing, pinned by
 * literal.
 *
 * [ContextCompactor.compactInputText] is the single place that decides whether
 * a pass sees the previous rounds' summary. The halving recursion relies on it
 * (the first half is summarized ON TOP of the pushed-down summary), so the
 * framing text is asserted verbatim here instead of being re-spelled in the
 * caller.
 */
class ContextCompactorCompactInputTest {

    @Test
    fun `no previous summary yields the transcript alone`() {
        assertEquals("TRANSCRIPT", ContextCompactor.compactInputText(null, "TRANSCRIPT"))
        assertEquals("TRANSCRIPT", ContextCompactor.compactInputText("", "TRANSCRIPT"))
        assertEquals("TRANSCRIPT", ContextCompactor.compactInputText("   \n\t ", "TRANSCRIPT"))
    }

    @Test
    fun `previous summary is framed ahead of the transcript`() {
        assertEquals(
            "Previous context summary:\nOLD SUMMARY\n\nNew conversation to merge:\nTRANSCRIPT",
            ContextCompactor.compactInputText("OLD SUMMARY", "TRANSCRIPT"),
        )
    }
}
