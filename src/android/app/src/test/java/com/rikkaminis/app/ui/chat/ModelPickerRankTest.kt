package com.rikkaminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the model picker's search ranking (GH#272).
 *
 * Before this, [ModelPickerSheet] used a boolean `fuzzyMatch`: it decided
 * shown/hidden and nothing else, so every surviving model was equally
 * relevant and the list came out in config order — typing `gpt` put
 * `my-custom-gpt-wrapper` next to `gpt-4o`.
 *
 * These tests pin the ladder, the tie-breaks, and the two properties the
 * composable relies on downstream (stability, and the null-query contract).
 * The composable itself is not reachable from a JVM test, so the
 * `searchText.isEmpty()` short-circuit at both call sites is asserted by
 * inspection of the call sites rather than here.
 */
class ModelPickerRankTest {

    // ── the ladder ──────────────────────────────────────────────────────

    /**
     * The rung numbers are asserted as LITERALS, not via the TIER_* constants.
     * Asserting `assertEquals(TIER_PREFIX, r.tier)` would be tautological: a
     * change that shifted a rung's value would move both sides together and
     * the test would still pass. Verified by mutation — collapsing
     * TIER_PREFIX into TIER_WORD_PREFIX survives that form and dies on this
     * one. The constants are pinned separately below.
     */
    @Test
    fun `the rung constants keep their documented values`() {
        assertEquals(4, TIER_EXACT)
        assertEquals(3, TIER_PREFIX)
        assertEquals(2, TIER_WORD_PREFIX)
        assertEquals(1, TIER_SUBSTRING)
        assertEquals(0, TIER_SCATTERED)
    }

    @Test
    fun `exact match is the top rung`() {
        val r = matchScore("gpt-4o", "gpt-4o")
        assertNotNull(r)
        assertEquals(4, r!!.tier)
        assertEquals(0, r.position)
    }

    @Test
    fun `exact match ignores case`() {
        assertEquals(4, matchScore("GPT-4o", "gpt-4o")!!.tier)
    }

    @Test
    fun `prefix match is the second rung`() {
        val r = matchScore("gpt-4o-mini", "gpt-4o")
        assertNotNull(r)
        assertEquals(3, r!!.tier)
        assertEquals(0, r.position)
    }

    @Test
    fun `match right after a separator is a word prefix`() {
        for (name in listOf("my-custom-gpt", "my_custom_gpt", "my.custom.gpt", "my/custom/gpt", "my:gpt", "my gpt")) {
            val r = matchScore(name, "gpt")
            assertNotNull("$name should match", r)
            assertEquals("$name should be word-prefix", 2, r!!.tier)
        }
    }

    @Test
    fun `plain containment is the substring rung`() {
        val r = matchScore("xxgptxx", "gpt")
        assertNotNull(r)
        assertEquals(1, r!!.tier)
        assertEquals(2, r.position)
    }

    @Test
    fun `scattered characters are the bottom rung`() {
        val r = matchScore("g-x-p-t", "gpt")
        assertNotNull(r)
        assertEquals(0, r!!.tier)
    }

    @Test
    fun `a non-match is null`() {
        assertNull(matchScore("claude-sonnet", "gpt"))
        // Order matters on the scattered path: "tpg" is not a subsequence.
        assertNull(matchScore("gpt", "tpg"))
    }

    @Test
    fun `an empty query is null, not a match-everything`() {
        // Both call sites short-circuit on searchText.isEmpty() and return
        // the unfiltered list, so "" never reaches the ladder. Null makes a
        // future caller that forgets the branch fail loudly (empty list)
        // instead of silently ranking every row at the top rung.
        assertNull(matchScore("anything", ""))
    }

    // ── ranking a list ──────────────────────────────────────────────────

    private data class Row(val name: String, val id: String)

    private fun rank(names: List<String>, query: String): List<String> =
        rankMatches(names.map { Row(it, it) }, query) { listOf(it.name) }.map { it.name }

    @Test
    fun `the ladder orders results, worst-fed-first`() {
        val input = listOf(
            "g-x-p-t",               // scattered
            "xxgptxx",               // substring
            "my-custom-gpt-wrapper", // word prefix
            "gpt-4o-mini",           // prefix
            "gpt",                   // exact
        )
        assertEquals(
            listOf("gpt", "gpt-4o-mini", "my-custom-gpt-wrapper", "xxgptxx", "g-x-p-t"),
            rank(input, "gpt"),
        )
    }

    @Test
    fun `non-matching items are dropped`() {
        assertEquals(listOf("gpt-4o"), rank(listOf("gpt-4o", "claude-sonnet", "llama-3"), "gpt"))
    }

    @Test
    fun `equal ranks keep their incoming order`() {
        // Stability is load-bearing: it is how the pinned / non-pinned split
        // and the configured provider order survive a search. An unstable
        // sort would shuffle Favorites.
        val input = listOf("a-gpt-1", "b-gpt-2", "c-gpt-3")
        assertEquals(input, rank(input, "gpt"))
    }

    @Test
    fun `within a rung, an earlier match wins`() {
        val input = listOf("zzzzzgptx", "zgptx", "zzgptx")
        assertEquals(listOf("zgptx", "zzgptx", "zzzzzgptx"), rank(input, "gptx"))
    }

    @Test
    fun `a rung outranks position`() {
        // `zzzzz-gpt` matches later but after a separator (word prefix);
        // `zgpt` matches earlier as a plain substring. The rung wins.
        assertEquals(
            listOf("zzzzz-gpt", "zgpt"),
            rank(listOf("zgpt", "zzzzz-gpt"), "gpt"),
        )
    }

    @Test
    fun `an item takes its best rank across all of its texts`() {
        val rows = listOf(
            Row("Friendly Name", "gpt-4o"), // exact match on the id
            Row("gpt-4o-wrapper", "zzz"),   // prefix match on the name
        )
        val out = rankMatches(rows, "gpt-4o") { listOf(it.name, it.id) }
        assertEquals(listOf("Friendly Name", "gpt-4o-wrapper"), out.map { it.name })
    }

    @Test
    fun `an item matching only on its id still ranks`() {
        val rows = listOf(Row("Friendly Name", "gpt-4o"))
        assertEquals(
            listOf("Friendly Name"),
            rankMatches(rows, "gpt-4o") { listOf(it.name, it.id) }.map { it.name },
        )
    }

    // ── the regression itself ───────────────────────────────────────────

    @Test
    fun `GH272 - a prefix match outranks a hyphenated wrapper`() {
        // Frozen from the bug report: both used to survive the boolean
        // filter with equal standing, so the order was whatever came in.
        val now = rank(listOf("my-custom-gpt-wrapper", "gpt-4o"), "gpt")
        assertEquals(listOf("gpt-4o", "my-custom-gpt-wrapper"), now)
    }

    @Test
    fun `ranking never introduces or removes a model`() {
        // The accept/reject half is unchanged, so the ranked result is a
        // permutation of the filtered result. Checked over a matrix of
        // names and queries rather than one hand-picked pair.
        val names = listOf(
            "gpt-4o", "gpt-4o-mini", "my-custom-gpt-wrapper", "internal-gpt-bridge",
            "claude-sonnet-4-6", "gemini-2.5-pro", "o3-mini", "g-x-p-t", "xxgptxx",
        )
        for (query in listOf("gpt", "gpt-4o", "o", "claude", "mini", "xyz")) {
            val ranked = rank(names, query)
            assertTrue("$query: ranked has duplicates", ranked.size == ranked.toSet().size)
            for (n in ranked) assertTrue("$query: $n does not match", matchScore(n, query) != null)
            // Anything the old boolean filter accepted must still be present.
            val accepted = names.filter { oldFuzzyMatch(it, query) }
            assertEquals("$query: membership changed", accepted.toSet(), ranked.toSet())
        }
    }

    /** The pre-change predicate, kept here as the control arm. */
    private fun oldFuzzyMatch(text: String, query: String): Boolean {
        if (query.isEmpty()) return true
        val q = query.lowercase()
        val t = text.lowercase()
        if (t.contains(q)) return true
        var idx = 0
        for (ch in q) {
            val found = t.indexOf(ch, idx)
            if (found < 0) return false
            idx = found + 1
        }
        return true
    }
}
