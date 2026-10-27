package com.rikkaminis.app.provider.thinking

import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [da1030-C ISSUE-05] Revision semantics of the process-wide custom-rule cache.
 *
 * The cache is mutated from outside any screen (repository load, worker restore,
 * `minis-config` write, backup import), so [ThinkingRuleResolver.customRulesRevision]
 * is what the rules screen keys its `remember` on. These tests pin the contract the
 * UI depends on:
 *
 *  1. every public mutator bumps it — a mutator that forgets to bump is invisible to
 *     an active collector, which is exactly the stale-list regression being fixed;
 *  2. the bump is strictly monotonic (never reset) — a "back to 0" value would look
 *     like no change to a collector that already saw 0;
 *  3. one logical write bumps exactly once — `restoreCustomRulesFromJson` used to
 *     delegate to `setCustomRules`, which would have double-counted a single restore.
 *
 * Expectations are literal deltas (1L), never re-derived from the code under test.
 */
class ThinkingRuleResolverRevisionTest {

    @After
    fun tearDown() {
        // Never leak cache state into other tests in the same JVM.
        ThinkingRuleResolver.setAllCustomRules(emptyMap())
    }

    private fun rule(id: String, label: String) = ThinkingRule(
        kind = ThinkingRule.Kind.CUSTOM,
        scope = ThinkingRule.Scope.AllModels,
        wireFormat = ThinkingWireFormat.OmitEverything,
        label = label,
        id = id,
    )

    private fun encodeRule(id: String, label: String) =
        ThinkingRuleCoding.encodeRuleJson(rule(id, label))

    @Test
    fun `each mutator bumps the revision by exactly one`() {
        val start = ThinkingRuleResolver.customRulesRevision.value

        ThinkingRuleResolver.setAllCustomRules(emptyMap())
        assertEquals("setAllCustomRules", start + 1L, ThinkingRuleResolver.customRulesRevision.value)

        ThinkingRuleResolver.setCustomRules("inst-rev", listOf(rule("r1", "first")))
        assertEquals("setCustomRules", start + 2L, ThinkingRuleResolver.customRulesRevision.value)

        // A single restore is ONE logical write: it must bump once, not once for
        // the map surgery plus once more for a delegated setCustomRules.
        val restored = ThinkingRuleResolver.restoreCustomRulesFromJson(
            "inst-rev2",
            JSONArray().apply { put(encodeRule("r2", "second")) },
        )
        assertEquals(1, restored)
        assertEquals("restoreCustomRulesFromJson", start + 3L, ThinkingRuleResolver.customRulesRevision.value)

        // restore with an absent field is still a cache mutation (it clears the
        // entry) and must be counted the same way.
        ThinkingRuleResolver.restoreCustomRulesFromJson("inst-rev2", null)
        assertEquals("restore(null field)", start + 4L, ThinkingRuleResolver.customRulesRevision.value)
    }

    @Test
    fun `re-writing identical content still bumps the revision`() {
        // Deliberately simple semantics: the mutators do NOT deep-compare the
        // incoming list against the cached one. Comparing would mean an O(n)
        // structural walk on every write (every add/edit/delete/reorder from the
        // UI and every restore from the worker), to save a recomposition that
        // only happens when nothing changed. A monotonic counter that
        // over-reports is cheap; a counter that under-reports is the bug being
        // fixed.
        ThinkingRuleResolver.setCustomRules("inst-same", listOf(rule("r1", "same")))
        val afterFirst = ThinkingRuleResolver.customRulesRevision.value

        ThinkingRuleResolver.setCustomRules("inst-same", listOf(rule("r1", "same")))
        assertEquals(afterFirst + 1L, ThinkingRuleResolver.customRulesRevision.value)
    }

    @Test
    fun `revision never decreases across a long mutation sequence`() {
        val seen = ArrayList<Long>()
        seen.add(ThinkingRuleResolver.customRulesRevision.value)
        ThinkingRuleResolver.setAllCustomRules(emptyMap())
        seen.add(ThinkingRuleResolver.customRulesRevision.value)
        ThinkingRuleResolver.setCustomRules("inst-a", listOf(rule("a1", "a")))
        seen.add(ThinkingRuleResolver.customRulesRevision.value)
        ThinkingRuleResolver.setCustomRules("inst-a", emptyList())
        seen.add(ThinkingRuleResolver.customRulesRevision.value)
        ThinkingRuleResolver.setCustomRules("inst-b", listOf(rule("b1", "b")))
        seen.add(ThinkingRuleResolver.customRulesRevision.value)
        ThinkingRuleResolver.restoreCustomRulesFromJson("inst-b", JSONArray())
        seen.add(ThinkingRuleResolver.customRulesRevision.value)

        for (i in 1 until seen.size) {
            assertTrue("revision went backwards at step $i: $seen", seen[i] > seen[i - 1])
        }
    }

    @Test
    fun `an empty restore clears the entry and bumps once`() {
        ThinkingRuleResolver.setCustomRules("inst-empty", listOf(rule("r1", "present")))
        assertEquals(1, ThinkingRuleResolver.customRulesFor("inst-empty").size)
        val before = ThinkingRuleResolver.customRulesRevision.value

        val restored = ThinkingRuleResolver.restoreCustomRulesFromJson("inst-empty", JSONArray())

        assertEquals(0, restored)
        assertEquals(before + 1L, ThinkingRuleResolver.customRulesRevision.value)
        assertEquals(0, ThinkingRuleResolver.customRulesFor("inst-empty").size)
    }
}
