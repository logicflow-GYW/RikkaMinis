package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [meta-audit-1028 E5 / B06 F-11] URL normalization for the skills browser.
 *
 * The pre-fix defect, restated as the invariant under test: the raw URL the
 * browser fetches must never carry an anchor or a query, because
 * `toRawGitHubUrl` splices `/SKILL.md` onto the path — an anchor left in place
 * lands in the path and the request 404s on the directory.
 *
 * Expectations are literals (never derived from the code under test), so a
 * mutation of the implementation cannot ride along as PASS.
 */
class SkillSourceUrlTest {

    // -- ① stripUrlAnchor: what "normalized" means --

    @Test
    fun stripUrlAnchor_table() {
        // (input, expected) — literal pairs, table-driven.
        val table = listOf(
            // fragment on a /tree/ page (the report's case)
            "https://github.com/o/r/tree/main/exa-search#install" to
                "https://github.com/o/r/tree/main/exa-search",
            // query on a /tree/ page
            "https://github.com/o/r/tree/main/exa-search?tab=readme" to
                "https://github.com/o/r/tree/main/exa-search",
            // both, fragment last
            "https://github.com/o/r/tree/main/exa-search?tab=readme#install" to
                "https://github.com/o/r/tree/main/exa-search",
            // '?' inside the fragment must not survive as a query
            "https://github.com/o/r/tree/main/x?a=b#c?d" to
                "https://github.com/o/r/tree/main/x",
            // hash inside a query value: cut at '#' first, then '?'
            "https://github.com/o/r/tree/main/x?a=b#c" to
                "https://github.com/o/r/tree/main/x",
            // nothing to strip
            "https://github.com/o/r/tree/main/x" to
                "https://github.com/o/r/tree/main/x",
            // non-GitHub raw URL with an anchor
            "https://raw.githubusercontent.com/o/r/main/x/SKILL.md#usage" to
                "https://raw.githubusercontent.com/o/r/main/x/SKILL.md",
        )
        for ((input, expected) in table) {
            assertEquals("stripUrlAnchor($input)", expected, stripUrlAnchor(input))
        }
    }

    // -- ② toRawGitHubUrl: the derived fetch URL carries no anchor/query --

    @Test
    fun toRawGitHubUrl_treePageWithFragment_derivesCleanSkillMdUrl() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/exa-search/SKILL.md",
            toRawGitHubUrl("https://github.com/o/r/tree/main/exa-search#install"),
        )
    }

    @Test
    fun toRawGitHubUrl_treePageWithQuery_derivesCleanSkillMdUrl() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/exa-search/SKILL.md",
            toRawGitHubUrl("https://github.com/o/r/tree/main/exa-search?tab=readme"),
        )
    }

    @Test
    fun toRawGitHubUrl_blobPageWithFragment_derivesCleanSkillMdUrl() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/exa-search/SKILL.md",
            toRawGitHubUrl("https://github.com/o/r/blob/main/exa-search/SKILL.md#usage"),
        )
    }

    @Test
    fun toRawGitHubUrl_trailingSlashAndAnchor_trimmedThenAppended() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/exa-search/SKILL.md",
            toRawGitHubUrl("https://github.com/o/r/tree/main/exa-search/#install"),
        )
    }

    @Test
    fun toRawGitHubUrl_rawSkillMdWithAnchor_isReturnedWithoutAnchor() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/main/exa-search/SKILL.md",
            toRawGitHubUrl("https://raw.githubusercontent.com/o/r/main/exa-search/SKILL.md#usage"),
        )
    }

    /**
     * The invariant, stated independently of the exact derivations above: no
     * anchor/query byte survives into the URL the browser actually fetches.
     */
    @Test
    fun toRawGitHubUrl_neverLeaksAnchorOrQuery() {
        val inputs = listOf(
            "https://github.com/o/r/tree/main/x#a",
            "https://github.com/o/r/tree/main/x?tab=readme",
            "https://github.com/o/r/blob/main/x/SKILL.md#a?b",
            "https://github.com/o/r/tree/main/x?tab=readme#a",
        )
        for (input in inputs) {
            val raw = toRawGitHubUrl(input)
            assertTrue("'#' leaked into $raw (from $input)", !raw.contains('#'))
            assertTrue("'?' leaked into $raw (from $input)", !raw.contains('?'))
            assertTrue("SKILL.md missing from $raw (from $input)", raw.endsWith("/SKILL.md"))
        }
    }

    // -- ③ isSkillDirectoryUrl: anchor/query must not change the verdict --

    @Test
    fun isSkillDirectoryUrl_anchorAndQueryDoNotChangeVerdict() {
        val skillDir = "https://github.com/o/r/tree/main/exa-search"
        assertTrue(isSkillDirectoryUrl(skillDir))
        assertTrue(isSkillDirectoryUrl("$skillDir#install"))
        assertTrue(isSkillDirectoryUrl("$skillDir?tab=readme"))
        assertTrue(isSkillDirectoryUrl("$skillDir?tab=readme#install"))
        // A tree page whose QUERY carries a trailing SKILL.md must not be
        // mistaken for the blob form of the same page.
        assertFalse(isSkillDirectoryUrl("https://github.com/o/r/tree/main?SKILL.md"))
        // /blob/…/SKILL.md — with and without an anchor
        assertTrue(isSkillDirectoryUrl("https://github.com/o/r/blob/main/x/SKILL.md"))
        assertTrue(isSkillDirectoryUrl("https://github.com/o/r/blob/main/x/SKILL.md#usage"))
        // non-GitHub URLs are left to the caller to try
        assertTrue(isSkillDirectoryUrl("https://example.com/skills/x#anchor"))
        // repo root stays rejected, anchor or not
        assertFalse(isSkillDirectoryUrl("https://github.com/o/r/tree/main"))
        assertFalse(isSkillDirectoryUrl("https://github.com/o/r/tree/main#readme"))
        // known non-skill pages stay rejected even with an anchor
        assertFalse(isSkillDirectoryUrl("https://github.com/o/r/issues#top"))
        // An anchor must not be able to FAKE a skill path: a repo-root page
        // whose fragment happens to end in "SKILL.md" is still a repo root,
        // and an un-stripped URL answers true on the `endsWith("SKILL.md")`
        // shortcut — the browser would then try to import the root.
        assertFalse(isSkillDirectoryUrl("https://github.com/o/r#SKILL.md"))
    }

    @Test
    fun isSkillDirectoryUrl_repoRootWithQuery_isRejected() {
        assertFalse(isSkillDirectoryUrl("https://github.com/o/r?tab=repositories"))
        // Same fake as the fragment case, through the query instead: a root
        // page whose query ends in "SKILL.md" is still a root.
        assertFalse(isSkillDirectoryUrl("https://github.com/o/r?SKILL.md"))
        assertFalse(isSkillDirectoryUrl("https://github.com/o/r?path=/tree/main/fake"))
    }
}
