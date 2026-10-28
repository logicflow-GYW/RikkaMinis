package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F-06] YAML block scalars on the SKILL.md WRITE path, and [F-09] CRLF documents
 * on the rename path.
 *
 * Both defects live in the same file and in the same shape of mistake: the writer
 * did not agree with the reader about what a line means.
 *
 *  - [F-06] `parseSkillMd` resolves `description: |` / `>-` into a value WITH
 *    newlines (T151), but `mergeFrontmatter` walked the block line by line: a
 *    managed key's header was rewritten as `description: new` while its body lines
 *    stayed behind, and a multi-line value was emitted raw — its continuation lines
 *    landed at column 0 INSIDE the frontmatter, where the next read took them for
 *    keys (`version: 9.9` rewrote the version) and truncated the value to its first
 *    line.
 *  - [F-09] `renameFrontmatterName` spliced the document at `Σ line.length + 1`.
 *    `String.lines()` drops the whole `\r\n`, so on a CRLF document the offset was
 *    one char per line short and the split landed INSIDE the block: the head ended
 *    mid-`name:`-value and the rename produced `name: newx`.
 *
 * Pure companion functions, so this needs no Android runtime (the same split
 * [SkillFrontmatterMergeTest] uses).
 */
class SkillFrontmatterBlockScalarAndEolTest {

    private fun merge(existing: String?, name: String = "my-skill",
                      description: String = "does things", version: String = "1.0.0",
                      body: String = "Body.") =
        SkillRepository.mergeFrontmatter(existing, name, description, version, body)

    // ── F-06: an existing block scalar on a managed key ──────────────────

    @Test
    fun `managed block scalar body is dropped with its header`() {
        val existing = listOf(
            "---",
            "name: my-skill",
            "description: |",
            "  line one",
            "  line two",
            "version: 1.0.0",
            "subagent: true",
            "---",
            "Body.",
        ).joinToString("\n")

        val out = merge(existing, description = "fresh desc")

        assertEquals(
            listOf(
                "---",
                "name: my-skill",
                "description: fresh desc",
                "version: 1.0.0",
                "subagent: true",
                "---",
                "Body.",
            ).joinToString("\n"),
            out,
        )
        assertFalse("stale block body must not survive", out.contains("line one"))
        assertEquals("fresh desc", parseDescription(out))
    }

    @Test
    fun `folded block scalar with a chomping indicator is recognized`() {
        val existing = listOf(
            "---",
            "name: n",
            "description: >-",
            "  folded away",
            "  entirely",
            "---",
            "B",
        ).joinToString("\n")

        assertEquals(
            listOf("---", "name: n", "description: plain", "version: 1.0.0", "---", "B").joinToString("\n"),
            merge(existing, name = "n", description = "plain", body = "B"),
        )
    }

    @Test
    fun `unmanaged block scalar is carried through verbatim`() {
        val existing = listOf(
            "---",
            "name: n",
            "notes: |",
            "  keep me",
            "  and me",
            "description: d",
            "---",
            "B",
        ).joinToString("\n")

        val out = merge(existing, name = "n2", description = "d2", version = "2.0.0", body = "B")

        assertEquals(
            listOf(
                "---",
                "name: n2",
                "notes: |",
                "  keep me",
                "  and me",
                "description: d2",
                "version: 2.0.0",
                "---",
                "B",
            ).joinToString("\n"),
            out,
        )
    }

    // ── F-06: a multi-line value going IN ────────────────────────────────

    @Test
    fun `multi line value lands as a block scalar, not as raw lines`() {
        val value = "first line\nsecond: injected\nthird line"
        val existing = listOf("---", "name: n", "description: old", "---", "B").joinToString("\n")

        val out = merge(existing, name = "n", description = value, body = "B")

        assertEquals(
            listOf(
                "---",
                "name: n",
                "description: |",
                "  first line",
                "  second: injected",
                "  third line",
                "version: 1.0.0",
                "---",
                "B",
            ).joinToString("\n"),
            out,
        )
        // The damage the raw form caused: `second: injected` read back as a key and
        // the value came back truncated to its first line.
        assertEquals(value, parseDescription(out))
    }

    @Test
    fun `a new skill seeded with a multi line description keeps every line`() {
        // No on-disk file: the canonical block is built from scratch, and the
        // managed-key append path has to speak blocks too.
        val value = "alpha\nbeta"
        val out = merge(null, name = "n", description = value, version = "1", body = "B")

        assertEquals(
            listOf("---", "name: n", "description: |", "  alpha", "  beta", "version: 1", "---", "B").joinToString("\n"),
            out,
        )
        assertEquals(value, parseDescription(out))
    }

    @Test
    fun `single line values stay on one line`() {
        val out = merge("---\nname: n\n---\nB", name = "n", description = "one line", version = "1", body = "B")
        assertTrue(out.contains("description: one line"))
        assertEquals(out, merge(out, name = "n", description = "one line", version = "1", body = "B"))
    }

    /** Minimal description reader: the `description` key of a frontmatter block. */
    private fun parseDescription(md: String): String {
        val lines = md.trimStart().lines()
        var end = lines.size
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") {
                end = i
                break
            }
        }
        val block = lines.subList(1, end)
        for (i in block.indices) {
            val line = block[i]
            if (!line.startsWith("description:")) continue
            val raw = line.substringAfter(':').trim()
            if (!raw.startsWith("|") && !raw.startsWith(">")) return raw
            val body = mutableListOf<String>()
            var j = i + 1
            while (j < block.size && (block[j].isEmpty() || block[j][0].isWhitespace())) {
                body.add(block[j].trim())
                j++
            }
            return body.joinToString("\n")
        }
        return ""
    }

    // ── F-09: CRLF ───────────────────────────────────────────────────────

    @Test
    fun `crlf document is renamed without corrupting the value`() {
        // Four lines inside the block, so the pre-fix offset was 4 chars short and
        // the split landed inside `name: old-name`.
        val md = "---\r\na: 1\r\nname: old-name\r\n---\r\nBody\r\nline2\r\n"
        assertEquals(
            "---\r\na: 1\r\nname: new\r\n---\r\nBody\r\nline2\r\n",
            SkillRepository.renameFrontmatterName(md, "new"),
        )
    }

    @Test
    fun `crlf rename changes nothing but the name line`() {
        val md = "---\r\ndescription: d\r\nname: old\r\nsubagent: true\r\n---\r\nBody\r\n"
        val out = SkillRepository.renameFrontmatterName(md, "renamed")!!
        assertEquals(md.length - "old".length + "renamed".length, out.length)
        assertEquals(md.replace("name: old", "name: renamed"), out)
        assertEquals(6, out.count { it == '\r' })
    }

    @Test
    fun `mixed line endings keep their own terminators`() {
        val md = "---\r\nname: old\nsubagent: true\r\n---\r\n\r\nname: body-line\r\n"
        assertEquals(
            "---\r\nname: new\nsubagent: true\r\n---\r\n\r\nname: body-line\r\n",
            SkillRepository.renameFrontmatterName(md, "new"),
        )
    }

    @Test
    fun `crlf frontmatter without a name line is left alone`() {
        assertNull(SkillRepository.renameFrontmatterName("---\r\ndescription: d\r\n---\r\nB", "new"))
    }

    @Test
    fun `lf rename is unchanged by the rewrite`() {
        val md = "---\nname: old\nsubagent: true\n---\nBody\n"
        assertEquals("---\nname: new\nsubagent: true\n---\nBody\n", SkillRepository.renameFrontmatterName(md, "new"))
    }

    @Test
    fun `renaming to the same name reports no change`() {
        val md = "---\nname: same\n---\nBody"
        assertNull(SkillRepository.renameFrontmatterName(md, "same"))
    }
}
