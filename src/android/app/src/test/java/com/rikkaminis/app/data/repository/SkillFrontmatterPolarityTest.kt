package com.rikkaminis.app.data.repository

import com.rikkaminis.app.tools.SubagentSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * deep-audit-1023 ISSUE-05 (P1) — the SKILL.md merge base must be memory-first,
 * and the extended frontmatter keys must survive the update chain.
 *
 * The bug had two halves that only cancel out when both are pinned:
 *
 *  1. `writeSkillMd` seeded its merge with `existing ?: skill.frontmatter`, i.e.
 *     the ON-DISK document shadowed the block the caller was landing. Any path
 *     that already had a file — `updateFromURL`, "update from file", an editor
 *     save — refreshed name / description / body / version while `subagent`,
 *     `max_turns` and `allowed_tools` silently stayed whatever the disk said.
 *  2. `update()` had no `frontmatter` parameter at all, so `updateFromURL`
 *     could not hand the remote block over even if the polarity had been right.
 *
 * [SkillRepository.resolveMergeBase] is the single exit for the polarity
 * decision, so it is testable without Android; the tests below mirror the exact
 * call shape `writeSkillMd` uses (resolveMergeBase → mergeFrontmatter) and then
 * extract the landed block back out, to prove the extended keys are still
 * reachable after the landing — not just present somewhere in the document.
 *
 * Expectations are hard-coded literals, never derived from the functions under
 * test (a derived expectation would let a mutation pass while wearing its own
 * teeth).
 */
class SkillFrontmatterPolarityTest {

    private val diskDocument = """
        ---
        name: Old Name
        description: Old description
        version: 1.0.0
        subagent: true
        max_turns: 7
        allowed_tools: [file_read, shell_execute]
        ---
        Old body on disk.
    """.trimIndent()

    /** What a fresh SKILL.md from the catalogue carries. */
    private val incomingBlock = """
        ---
        name: Old Name
        description: Old description
        version: 1.0.0
        subagent: true
        max_turns: 50
        allowed_tools: [file_read, shell_execute]
        ---
    """.trimIndent()

    // ── 1. polarity of the single exit ───────────────────────────────────

    @Test
    fun `resolveMergeBase prefers the caller block over the on-disk document`() {
        assertEquals(incomingBlock, SkillRepository.resolveMergeBase(incomingBlock, diskDocument))
    }

    @Test
    fun `resolveMergeBase falls back to the disk when the caller block is empty`() {
        assertEquals(diskDocument, SkillRepository.resolveMergeBase("", diskDocument))
        assertEquals(diskDocument, SkillRepository.resolveMergeBase("   \n", diskDocument))
        assertEquals(diskDocument, SkillRepository.resolveMergeBase(null, diskDocument))
    }

    @Test
    fun `resolveMergeBase yields null when neither side has a block`() {
        assertNull(SkillRepository.resolveMergeBase(null, null))
        assertNull(SkillRepository.resolveMergeBase("", null))
        assertNull(SkillRepository.resolveMergeBase("  ", null))
    }

    /**
     * The pre-fix expression, written out so the difference is executable:
     * `existing ?: skill.frontmatter` returned the DISK document here, which is
     * precisely the polarity inversion this branch fixes.
     */
    @Test
    fun `pre-fix expression would have chosen the disk copy`() {
        val oldWay: String? = diskDocument ?: incomingBlock.ifBlank { null }
        val newWay = SkillRepository.resolveMergeBase(incomingBlock, diskDocument)
        assertEquals(diskDocument, oldWay)
        assertEquals(incomingBlock, newWay)
    }

    // ── 2. what actually lands on disk (writeSkillMd call shape) ─────────

    /** Mirror of `writeSkillMd`: resolveMergeBase(skill.frontmatter, existing) → mergeFrontmatter. */
    private fun landing(callerFrontmatter: String?, diskContent: String?,
                        name: String, description: String, version: String, body: String): String =
        SkillRepository.mergeFrontmatter(
            existing = SkillRepository.resolveMergeBase(callerFrontmatter, diskContent),
            name = name,
            description = description,
            version = version,
            body = body,
        )

    @Test
    fun `url update lands the remote max_turns instead of keeping the disk value`() {
        val written = landing(
            callerFrontmatter = incomingBlock,
            diskContent = diskDocument,
            name = "Researcher",
            description = "Deep research",
            version = "2.1.0",
            body = "New body from the catalogue.",
        )
        assertEquals(
            """
            ---
            name: Researcher
            description: Deep research
            version: 2.1.0
            subagent: true
            max_turns: 50
            allowed_tools: [file_read, shell_execute]
            ---
            New body from the catalogue.
            """.trimIndent(),
            written,
        )
        assertFalse("stale disk budget must not survive", written.contains("max_turns: 7"))
        assertFalse("stale disk name must not survive", written.contains("name: Old Name"))
    }

    @Test
    fun `the consumer reads the landed budget back off the document`() {
        val written = landing(
            callerFrontmatter = incomingBlock,
            diskContent = diskDocument,
            name = "Researcher",
            description = "Deep research",
            version = "2.1.0",
            body = "You are a researcher. Do research.",
        )
        val block = SubagentSkill.extractFrontmatterBlock(written)
        assertTrue("the subagent flag must survive the landing", block.contains("subagent: true"))
        assertTrue("the landed budget must be readable", block.contains("max_turns: 50"))
        assertTrue(
            "the landed allowlist must be readable",
            block.contains("allowed_tools: [file_read, shell_execute]"),
        )
    }

    @Test
    fun `deleting an extended key upstream reaches the disk`() {
        val withoutFlag = """
            ---
            name: Old Name
            description: Old description
            version: 1.0.0
            max_turns: 9
            ---
        """.trimIndent()
        val written = landing(
            callerFrontmatter = withoutFlag,
            diskContent = diskDocument,
            name = "Old Name",
            description = "Old description",
            version = "1.0.0",
            body = "Plain skill body.",
        )
        assertFalse(
            "a removed `subagent: true` must not be resurrected from the disk block",
            written.contains("subagent:"),
        )
        assertTrue(written.contains("max_turns: 9"))
        val block = SubagentSkill.extractFrontmatterBlock(written)
        assertFalse("no subagent flag may appear", block.contains("subagent:"))
        assertTrue("the surviving budget must be readable", block.contains("max_turns: 9"))
    }

    @Test
    fun `a plain rename re-seeded from disk keeps keys edited outside the app`() {
        // update(frontmatter = null) re-reads the on-disk block, so the merge
        // base is the disk document — an externally added `max_turns: 30`
        // (file_write / sed in the sandbox) must survive a UI rename.
        val externallyEdited = """
            ---
            name: Old Name
            description: Old description
            version: 1.0.0
            subagent: true
            max_turns: 30
            ---
            Body the app never reloaded.
        """.trimIndent()
        val written = landing(
            callerFrontmatter = "",
            diskContent = externallyEdited,
            name = "Renamed",
            description = "Old description",
            version = "1.0.0",
            body = "Body the app never reloaded.",
        )
        assertEquals(
            """
            ---
            name: Renamed
            description: Old description
            version: 1.0.0
            subagent: true
            max_turns: 30
            ---
            Body the app never reloaded.
            """.trimIndent(),
            written,
        )
    }

    // ── 3. round-trip through the field the UI reads ─────────────────────

    @Test
    fun `frontmatter block survives an extract and merge round-trip byte for byte`() {
        val merged = landing(
            callerFrontmatter = incomingBlock,
            diskContent = null,
            name = "Old Name",
            description = "Old description",
            version = "1.0.0",
            body = "Body.",
        )
        val block = SubagentSkill.extractFrontmatterBlock(merged)
        // Managed keys are rewritten in place; every other line rides through
        // verbatim, so re-merging the extracted block is a fixed point.
        val again = SkillRepository.mergeFrontmatter(
            existing = block,
            name = "Old Name",
            description = "Old description",
            version = "1.0.0",
            body = "Body.",
        )
        assertEquals(merged, again)
        assertEquals(incomingBlock, block)
    }

    // ── 4. the write exit must emit a parsable document ──────────────────

    /**
     * deep-audit-1023 ISSUE-05 · same-family finding, caught by this harness:
     * the pre-fix walk included line 0 (the opening `---`) and then emitted
     * another one, so every rewrite of a skill whose base carried a block
     * produced `---\n---\nname: …`. parseSkillMd reads that as a 2-line
     * frontmatter with no `name:` and returns null; extractFrontmatterBlock
     * truncates it to `---\n---`. This pins the single-delimiter head.
     */
    @Test
    fun `rewrite of a block-carrying base emits a single opening delimiter`() {
        val written = landing(
            callerFrontmatter = "",
            diskContent = diskDocument,
            name = "Researcher",
            description = "Old description",
            version = "1.0.0",
            body = "Body.",
        )
        assertFalse(
            "doubled opening delimiter makes the document unparsable",
            written.startsWith("---\n---"),
        )
        assertTrue(written.startsWith("---\nname: Researcher"))
        // …and the real consumer can still read the extended keys off it.
        val block = SubagentSkill.extractFrontmatterBlock(written)
        assertTrue(block.contains("subagent: true"))
        assertTrue(block.contains("max_turns: 7"))
    }

    @Test
    fun `rewrite seeded from a caller block also emits a single delimiter`() {
        val written = landing(
            callerFrontmatter = incomingBlock,
            diskContent = null,
            name = "Old Name",
            description = "Old description",
            version = "1.0.0",
            body = "Body.",
        )
        assertFalse(written.startsWith("---\n---"))
        assertEquals(
            """
            ---
            name: Old Name
            description: Old description
            version: 1.0.0
            subagent: true
            max_turns: 50
            allowed_tools: [file_read, shell_execute]
            ---
            Body.
            """.trimIndent(),
            written,
        )
    }

    @Test
    fun `no block anywhere still emits the canonical three-key document`() {
        val written = SkillRepository.mergeFrontmatter(
            existing = null,
            name = "Fresh",
            description = "Nothing else",
            version = "3.0.0",
            body = "Just the body.",
        )
        assertEquals(
            """
            ---
            name: Fresh
            description: Nothing else
            version: 3.0.0
            ---
            Just the body.
            """.trimIndent(),
            written,
        )
    }

    // ── 5. the browser refresh must hand the incoming block over ─────────

    /**
     * deep-audit-1030 ISSUE-02 — the skills-BROWSER refresh called
     * `update(id, name, description, version, body)` and omitted `frontmatter`,
     * so `update()` re-seeded its copy from the on-disk SKILL.md and the
     * incoming `subagent` / `max_turns` / `allowed_tools` were shadowed by
     * whatever the disk already said — a catalogue edit under a refactor never
     * landed, silently. Both polarities are pinned against the exact call shape
     * the two callers produce:
     *   - `parsed.frontmatter.ifBlank { null }` (fixed) → the incoming block wins
     *   - `null` (the omission)                        → the disk block wins
     * A single-sided assertion would go green on either polarity.
     */
    @Test
    fun `browser refresh hands the incoming block over so it beats the disk copy`() {
        val fixed = landing(
            callerFrontmatter = incomingBlock.ifBlank { null },
            diskContent = diskDocument,
            name = "Old Name",
            description = "Old description",
            version = "1.0.0",
            body = "Body.",
        )
        assertTrue("the incoming budget must land", fixed.contains("max_turns: 50"))
        assertFalse("the stale disk budget must not survive", fixed.contains("max_turns: 7"))
        // …and the real consumer reads the extended keys back off the document,
        // so "landed" means reachable, not merely present in a string.
        val block = SubagentSkill.extractFrontmatterBlock(fixed)
        assertTrue("the incoming flag must land", block.contains("subagent: true"))
        assertTrue("the incoming budget must land", block.contains("max_turns: 50"))

        // The pre-fix call shape: update() re-seeds from readSkillMdFrontmatter(id),
        // i.e. the on-disk block becomes the merge base.
        val omitted = landing(
            callerFrontmatter = null,
            diskContent = diskDocument,
            name = "Old Name",
            description = "Old description",
            version = "1.0.0",
            body = "Body.",
        )
        assertTrue("the disk budget wins when the block is omitted", omitted.contains("max_turns: 7"))
        assertFalse(omitted.contains("max_turns: 50"))
    }
}
