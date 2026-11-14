package com.rikkaminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/defect-short-phrase-1114] All-short seed groups must be able to hit.
 *
 * Defect this pins: [SandboxDefectRegistry.hasSpecificToken] requires a ≥6-char
 * latin run, a CJK run, or a structural character. A seed whose tokens are ALL
 * short was dropped outright, so 25 of the registry's 89 seed groups were dead —
 * including ones for defects the agent has actually hit. On 2026-11-13 the log
 * shows `! [remote rejected] main -> main (bad pack)` twice with
 * `sandbox-defect-registry` count 0: `bad` and `pack` are 4 chars each, so A1
 * could never fire.
 *
 * The fix admits those phrasings as ADJACENT phrases only (see
 * [SandboxDefectRegistry.phraseGroupsOf]), never as unordered AND. Measured on
 * 132 real tool-failure outputs mined from the device log: baseline 18/132 hits,
 * with the relaxation 19/132 — the one new hit is A1, false positives unchanged.
 * As unordered AND the same relaxation would be a disaster: a lone `data` hits
 * 8/132 outputs, a lone `file` 35/132.
 */
class SandboxDefectPhraseRecallTest {

    private fun registry(vararg symptomLines: String): String = buildString {
        appendLine("## A 类")
        var n = 1
        for (line in symptomLines) {
            appendLine()
            appendLine("### A$n 测试条目 $n")
            appendLine("- 症状：$line")
            appendLine("- 健康路径：用正确的方式")
            n++
        }
    }.trimEnd()

    private fun matchOne(symptomLine: String, output: String): Boolean {
        val defects = SandboxDefectRegistry.parse(registry(symptomLine))
        return SandboxDefectRegistry.match(output, defects).isNotEmpty()
    }

    // ── the regression itself ────────────────────────────────────────────────

    @Test
    fun `a two-short-word seed still hits its exact phrase`() {
        // A1 in the real registry is `unpack should have generated X / bad pack`.
        assertTrue(
            matchOne("`unpack should have generated X / bad pack`", "! [remote rejected] main -> main (bad pack)"),
        )
    }

    @Test
    fun `a two-short-word seed does not hit the words separately far apart`() {
        // The whole point of phrase matching: `pack` and `bad` both appearing
        // somewhere in a long output is NOT the defect.
        assertFalse(
            matchOne(
                "`unpack should have generated X / bad pack`",
                "packing list: 3 items\nbad idea to continue\nretrying",
            ),
        )
    }

    @Test
    fun `three-short-word git seeds hit as phrases`() {
        assertTrue(matchOne("`git fsck`", "fatal: git fsck failed"))
        assertTrue(matchOne("`no merge base`", "fatal: no merge base found"))
        assertTrue(matchOne("`git log`", "error: git log --oneline returned 128"))
        assertTrue(matchOne("`http=000 size=0`", "download: http=000 size=0 after 30s"))
    }

    @Test
    fun `a three-short-word seed does not hit when the order is reversed`() {
        assertFalse(matchOne("`no merge base`", "no base merge attempted"))
        assertFalse(matchOne("`git fsck`", "fsck: git repo ok"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(matchOne("`bad pack`", "(BAD PACK) rejected"))
    }

    // ── the guardrails: what must NOT be relaxed ─────────────────────────────

    @Test
    fun `a single generic short word is still not a seed`() {
        // `data`, `wait`, `file`, `000` stay dead on purpose: with no adjacency
        // there is nothing to borrow specificity from, and these words appear in
        // 8 / 35 of 132 real failure outputs.
        assertFalse(matchOne("`data`", "unexpected data in stream"))
        assertFalse(matchOne("`wait`", "please wait"))
        assertFalse(matchOne("`file`", "file not found"))
        assertFalse(matchOne("`000`", "exit code 000"))
    }

    @Test
    fun `specific seeds keep their unordered AND behaviour`() {
        // Regression guard: the fix must not turn existing specific seeds into
        // phrase-only matches. `l2s.tmp_obj` + `operation` + `permitted` still
        // match in any order as long as all are present.
        assertTrue(
            matchOne(
                "`failed to create link '.l2s.tmp_obj_*': Operation not permitted`",
                "error: Operation not permitted while libc tried to create link /x/.l2s.tmp_obj_1234",
            ),
        )
    }

    @Test
    fun `phrase groups never contain a token that has specificity`() {
        // A group with one long token already qualifies via hasSpecificToken and
        // must go to seedGroups, not phraseGroups — otherwise the same phrasing
        // would be evaluated twice under two different rules.
        val defects = SandboxDefectRegistry.parse(registry("`bad pack header`"))
        assertEquals(1, defects.size)
        assertEquals(emptyList<String>(), defects[0].phraseGroups)
        assertEquals(listOf(listOf("bad", "pack", "header")), defects[0].seedGroups)
    }

    @Test
    fun `real registry now exposes phrase groups for the previously blind entries`() {
        // Reads the live registry, not a fixture: the value of this test is that
        // it fails if someone re-flattens the gate.
        val reg = java.io.File(System.getProperty("user.dir"))
            .let { dir ->
                var d: java.io.File? = dir
                var found: java.io.File? = null
                while (d != null && found == null) {
                    val f = java.io.File(d, "/var/minis/shared/sandbox-limits.md")
                    if (f.isFile) found = f
                    d = d.parentFile
                }
                found
            }
        if (reg == null) {
            // Sandbox-only fixture: absent on CI. Assert the code path instead.
            val synthetic = SandboxDefectRegistry.parse(registry("`bad pack`"))
            assertEquals(listOf("bad pack"), synthetic[0].phraseGroups)
            return
        }
        val defects = SandboxDefectRegistry.parse(reg.readText())
        val a1 = defects.firstOrNull { it.id == "A1" }
        assertTrue("A1 missing from the live registry", a1 != null)
        assertTrue(
            "A1 lost its `bad pack` phrase group — the exact failure that went " +
                "unrecalled on 2026-11-13 would go silent again",
            a1!!.phraseGroups.any { it == "bad pack" },
        )
        assertTrue(
            "A1 must fire on the real 2026-11-13 failure line",
            SandboxDefectRegistry.match("! [remote rejected] main -> main (bad pack)", defects).any { it.id == "A1" },
        )
    }
}
