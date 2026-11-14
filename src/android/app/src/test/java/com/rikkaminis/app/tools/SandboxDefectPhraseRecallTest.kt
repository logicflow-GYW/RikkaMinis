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

    // ── [fix/fold-paste-split-1114][F3] the right boundary ──────────────────

    @Test
    fun `a short phrase does not hit as a PREFIX of a longer word`() {
        // Without a right boundary `bad pack` matched `bad package` — and the
        // device log holds 21 real `bad package` lines, an npm error unrelated to
        // git's `bad pack`. Measured false positives removed by the guard:
        // `bad package` (npm), `git logger`, `merge baseline`.
        assertFalse(
            "`bad pack` must not fire on npm's `bad package`",
            matchOne("`unpack should have generated X / bad pack`", "npm ERR! bad package name in scope"),
        )
        assertFalse(
            "`git log` must not fire on `git logger`",
            matchOne("`git log`", "git logger: 488 lines"),
        )
        assertFalse(
            "`merge base` must not fire on `merge baseline`",
            matchOne("`no merge base`", "merge baseline mismatch in config"),
        )
    }

    @Test
    fun `the right boundary does not break the real hit`() {
        // Guarding the guard: the boundary must not eat the true positive, where
        // the phrase is followed by punctuation or end-of-string.
        assertTrue(matchOne("`bad pack`", "fatal: bad pack header"))
        assertTrue(matchOne("`bad pack`", "! [remote rejected] main -> main (bad pack)"))
        assertTrue(matchOne("`bad pack`", "error: bad pack"))
        assertTrue(matchOne("`git fsck`", "fatal: git-fsck failed"))
    }

    // ── [fix/fold-paste-split-1114][F4] template seeds ──────────────────────

    @Test
    fun `a command template never becomes a phrase`() {
        // `grep -n X file` → `grep file`: the tokens are short because they are
        // command syntax. It fired on "grep, file and directory all fine".
        assertFalse(
            matchOne("`grep -n X file` 只回 `binary file matches`", "grep, file and directory all fine"),
        )
        // A path template decomposes into three generic words.
        assertFalse(
            matchOne("`/etc/apk/world` 记录已装包列表", "ls: /etc/apk/world not found"),
        )
    }

    @Test
    fun `a placeholder snippet never becomes a phrase`() {
        // `val s = "\0CODE\0"` → `val 0code`, where CODE is a stand-in.
        //
        // Asserted on the derived phraseGroups, not on a match: the haystack in a
        // match-based test would have to contain `val 0code` ADJACENT to trip the
        // phrase, and any such haystack is contrived enough that the assertion
        // passes for the wrong reason (verified — a match-based version stayed
        // green with the filter disabled).
        val d = SandboxDefectRegistry.parse(registry("""遇到 `val s = "\0CODE\0"` 这种写法"""))
        assertTrue(
            "a placeholder-bearing snippet must not yield a phrase: ${d[0].phraseGroups}",
            d[0].phraseGroups.isEmpty(),
        )
    }

    @Test
    fun `a real error wording still becomes a phrase`() {
        // Guarding the guard: the template filter must not eat genuine seeds,
        // including the `=`-bearing one that S29 depends on.
        val d1 = SandboxDefectRegistry.parse(registry("`bad pack`"))
        assertEquals(listOf("bad pack"), d1[0].phraseGroups)
        val d2 = SandboxDefectRegistry.parse(registry("`http=000 size=0 after 30s`"))
        assertTrue("`=` must NOT be treated as a template marker", d2[0].phraseGroups.isNotEmpty())
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
        val reg = findRegistryAncestor(System.getProperty("user.dir") ?: ".")
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

    /** Walk up from [start] looking for the host-side registry copy (sandbox only). */
    private fun findRegistryAncestor(start: String): java.io.File? {
        var dir: java.io.File? = java.io.File(start)
        while (dir != null) {
            val candidate = java.io.File(dir, "/var/minis/shared/sandbox-limits.md")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
