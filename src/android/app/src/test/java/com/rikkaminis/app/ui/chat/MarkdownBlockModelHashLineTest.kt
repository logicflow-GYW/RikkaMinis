package com.rikkaminis.app.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the `#`-prefixed non-heading hang in [parseMarkdownBlocks]
 * (2026-09-15).
 *
 * Root cause: the heading branch required a space after the `#` run, while the
 * paragraph loop stopped on a bare `startsWith("#")`. A line such as
 * `#196 @68250c1 …`, `#!/bin/sh`, `#include <stdio.h>` or `#hashtag` therefore
 * matched neither branch: the paragraph loop `break`ed on its very first line
 * with nothing collected, `i` never advanced and the parser spun forever at
 * 100 % CPU. The message / file preview then never rendered.
 *
 * On device this was hit by opening the 1.8 MB dev-history archive in the file
 * preview (it contains `#196 @68250c1 …`, i.e. PR references): a core burned for
 * minutes, ~350 MB of garbage churn, and the preview stayed blank.
 *
 * Every parse below runs under a timeout. `parseMarkdownBlocks` calls
 * `coroutineContext.ensureActive()` every 64 lines, so if a future predicate
 * drifts again the loop is cancelled and the test fails in seconds instead of
 * hanging CI until the job timeout.
 */
class MarkdownBlockModelHashLineTest {

    private fun parse(text: String): List<MdBlock> = runBlocking {
        withTimeout(5_000) {
            withContext(Dispatchers.Default) { parseMarkdownBlocks(text) }
        }
    }

    @Test fun `hash-prefixed non-heading lines parse as paragraphs and terminate`() {
        val cases = listOf(
            "#196 @68250c1 **success**（编译+428既有+3新全过）",
            "#!/bin/sh\necho hi",
            "#include <stdio.h>",
            "#hashtag 文本",
            "shell output:\n#!/usr/bin/env python3\nprintf 'x'",
            "#196",
        )
        for (case in cases) {
            val blocks = parse(case)
            assertTrue("no blocks parsed for: $case", blocks.isNotEmpty())
            assertTrue(
                "expected prose only for: $case -> ${blocks.map { it::class.simpleName }}",
                blocks.all { it is MdBlock.Paragraph },
            )
            assertEquals("text changed for: $case", case, blocks.joinToString("\n") { it.raw })
        }
    }

    @Test fun `hash line in the middle of a document does not stall the parse`() {
        val blocks = parse(
            "# Heading\n\nintro text\n\n#196 @deadbeef fix\n\n- item one\n- item two\n\n## Sub\n",
        )
        assertEquals(
            listOf(
                MdBlock.Heading::class,
                MdBlock.Paragraph::class,
                MdBlock.Paragraph::class,
                MdBlock.UnorderedList::class,
                MdBlock.Heading::class,
            ),
            blocks.map { it::class },
        )
        // The `#196 …` line survives as prose — it is not swallowed.
        assertEquals("#196 @deadbeef fix", blocks[2].raw)
    }

    @Test fun `valid ATX headings keep parsing as headings`() {
        val h1 = parse("# Deep").single() as MdBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("Deep", h1.text)

        val h3 = parse("### Deep").single() as MdBlock.Heading
        assertEquals(3, h3.level)
        assertEquals("Deep", h3.text)

        // A bare hash run is an empty heading (CommonMark). It used to fall
        // through to the paragraph branch and hang there.
        val bare = parse("###").single() as MdBlock.Heading
        assertEquals(3, bare.level)
        assertEquals("", bare.text)
    }

    @Test fun `long mixed document keeps every line and terminates`() {
        val text = buildString {
            for (n in 1..50) {
                append("## Entry ").append(n).append("\n\n")
                append("body ").append(n).append("\n\n")
                append("#").append(n).append(" @sha").append(n).append(" fix\n\n")
            }
        }
        val blocks = parse(text)
        assertEquals(150, blocks.size) // 50 headings + 50 bodies + 50 `#<n>` prose
        assertEquals(100, blocks.count { it is MdBlock.Paragraph })
        assertEquals(50, blocks.count { it is MdBlock.Heading })
        assertTrue(blocks.none { it.raw.isBlank() })
    }
}
