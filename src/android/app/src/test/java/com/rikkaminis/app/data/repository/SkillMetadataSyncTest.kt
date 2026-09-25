package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for [SkillMetadataSync.reconcile] — the decision that keeps the
 * `skills` table in step with the on-disk SKILL.md frontmatter.
 *
 * Regression background (2026-09-25): a row is written once at import time and
 * `reloadFromDisk()` re-reads the DB but never the frontmatter, so a SKILL.md
 * edited outside the in-app editor (agent `file_write`/`file_edit`, or a shell
 * `sed`) left the row stale — and the stored `description` is exactly what
 * `skillPromptFragment` sends to the model in `<available_skills>`.
 *
 * `SkillRepository` needs Android's Context/SQLite, so the pure decision lives
 * in [SkillMetadataSync] and is exercised directly here.
 */
class SkillMetadataSyncTest {

    private fun m(
        name: String = "my-skill",
        description: String = "does things",
        version: String = "1.0.0",
    ) = SkillMetadataSync.Metadata(name, description, version)

    @Test
    fun `matching disk frontmatter needs no write`() {
        assertNull(SkillMetadataSync.reconcile(stored = m(), disk = m()))
    }

    @Test
    fun `missing or unparseable file never touches the row`() {
        assertNull(SkillMetadataSync.reconcile(stored = m(description = "kept"), disk = null))
    }

    @Test
    fun `description edited on disk wins`() {
        val out = SkillMetadataSync.reconcile(
            stored = m(description = "old text"),
            disk = m(description = "new text"),
        )
        assertEquals("new text", out?.description)
        assertEquals("my-skill", out?.name)
        assertEquals("1.0.0", out?.version)
    }

    @Test
    fun `blank disk description keeps the stored one`() {
        val out = SkillMetadataSync.reconcile(
            stored = m(description = "kept"),
            disk = m(description = "", version = "1.2.0"),
        )
        assertEquals("kept", out?.description)
        assertEquals("1.2.0", out?.version)
    }

    @Test
    fun `all blank disk fields are not a change`() {
        assertNull(
            SkillMetadataSync.reconcile(
                stored = m(),
                disk = m(name = "", description = "", version = ""),
            ),
        )
    }

    @Test
    fun `name and version drift are reconciled too`() {
        val out = SkillMetadataSync.reconcile(
            stored = m(name = "old-name", description = "same", version = "1.0.0"),
            disk = m(name = "renamed", description = "same", version = "2.0.0"),
        )
        assertEquals("renamed", out?.name)
        assertEquals("2.0.0", out?.version)
        assertEquals("same", out?.description)
    }
}
