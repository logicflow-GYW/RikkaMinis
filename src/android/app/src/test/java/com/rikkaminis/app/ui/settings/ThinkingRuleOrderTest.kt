package com.rikkaminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingRuleOrderTest {
    private val ids = listOf("rule-a", "rule-b", "rule-c")
    // Labels are not identifiers. Two otherwise indistinguishable rows can be
    // reordered independently, and both columns must agree after every move.
    private val rows = listOf("same label", "other label", "same label")
    private val current = rows to ids

    @Test fun `downward drag keeps rule payloads with their persisted IDs`() {
        assertEquals(
            listOf("other label", "same label", "same label") to listOf("rule-b", "rule-c", "rule-a"),
            movedThinkingRules(current, ids, 0, 2),
        )
        assertEquals(listOf("rule-a", "rule-b", "rule-c"), current.second)
        assertEquals(listOf("same label", "other label", "same label"), current.first)
    }

    @Test fun `an upward accessibility step changes the same priority order`() {
        assertEquals(
            listOf("other label", "same label", "same label") to listOf("rule-b", "rule-a", "rule-c"),
            movedThinkingRules(current, ids, 1, 0),
        )
    }

    @Test fun `a stale drag does not reorder the live list by outdated positions`() {
        assertNull(movedThinkingRules(current, ids.reversed(), 0, 1))
        assertNull(movedThinkingRules(current, ids.dropLast(1), 0, 1))
    }

    @Test fun `invalid indices and a no-op drop produce no write`() {
        assertNull(movedThinkingRules(current, ids, -1, 0))
        assertNull(movedThinkingRules(current, ids, 0, ids.size))
        assertNull(movedThinkingRules(current, ids, 1, 1))
        assertNull(movedThinkingRules(emptyList<String>() to emptyList(), emptyList(), 0, 1))
    }

    @Test fun `misaligned columns or duplicate identities are not a valid permutation`() {
        assertNull(movedThinkingRules(rows.dropLast(1) to ids, ids, 0, 1))
        val duplicateIds = listOf("rule-a", "rule-b", "rule-a")
        assertNull(movedThinkingRules(rows to duplicateIds, duplicateIds, 0, 1))
    }
}
