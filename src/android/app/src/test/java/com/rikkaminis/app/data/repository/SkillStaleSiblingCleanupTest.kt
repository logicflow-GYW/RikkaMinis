package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F-10] The stale-sibling decision: after a manifest-backed sync (zip overwrite
 * import, "Update from URL"), which local files must go?
 *
 * Both landing paths only ever WROTE files. An archive or an upstream directory
 * that no longer ships `scripts/old.py` left it on disk forever, so the skill kept
 * running code its source had deleted while the UI reported a completed update —
 * `updateFromURL`'s own KDoc promises "bundled scripts/references stay in sync with
 * upstream", which the pre-fix write-only walk could not deliver.
 *
 * The decision is a pure companion function so it is testable without Android; the
 * deletion loop around it (`deleteStaleSiblingFiles`) needs a real tree and is
 * covered by the branch probe instead (verbatim-extracted, run against a temp dir).
 */
class SkillStaleSiblingCleanupTest {

    private fun plan(existing: List<String>, keep: List<String>) =
        SkillRepository.planStaleSiblingDeletions(existing, keep)

    @Test
    fun `files the manifest no longer lists are stale`() {
        assertEquals(
            listOf("scripts/b.py", "scripts/legacy/c.py"),
            plan(
                listOf("SKILL.md", "scripts/a.py", "scripts/b.py", "scripts/legacy/c.py"),
                listOf("scripts/a.py"),
            ),
        )
    }

    @Test
    fun `files the manifest still lists are kept`() {
        assertTrue(
            plan(
                listOf("SKILL.md", "scripts/a.py", "references/doc.md"),
                listOf("scripts/a.py", "references/doc.md"),
            ).isEmpty(),
        )
    }

    @Test
    fun `the top level SKILL md is never stale`() {
        // writeSkillMd owns that file; a manifest that omits it (a GitHub directory
        // walk skips the root SKILL.md on purpose) must not make it a candidate.
        assertEquals(emptyList<String>(), plan(listOf("SKILL.md"), listOf("scripts/a.py")))
        assertEquals(emptyList<String>(), plan(listOf("SKILL.MD", "scripts/a.py"), listOf("scripts/a.py")))
    }

    @Test
    fun `an empty manifest deletes nothing`() {
        // Fail-closed: an empty listing is indistinguishable from a truncated API
        // response, and acting on that guess would wipe the skill's whole payload.
        assertEquals(emptyList<String>(), plan(listOf("SKILL.md", "scripts/a.py"), emptyList()))
    }

    @Test
    fun `manifest paths are compared after normalisation`() {
        assertEquals(
            listOf("scripts/b.py"),
            plan(
                listOf("scripts\\a.py", "./scripts\\b.py"),
                listOf("scripts/a.py"),
            ),
        )
    }

    @Test
    fun `duplicate entries collapse and the result is ordered`() {
        assertEquals(
            listOf("a.py", "z.py"),
            plan(listOf("z.py", "a.py", "./a.py", "a.py"), listOf("keep.py")),
        )
    }

    @Test
    fun `a manifest that is a superset of the directory deletes nothing`() {
        assertEquals(emptyList<String>(), plan(listOf("a.py"), listOf("a.py", "b.py")))
    }

    @Test
    fun `blank entries are ignored`() {
        assertEquals(emptyList<String>(), plan(listOf(""), listOf("a.py")))
    }
}
