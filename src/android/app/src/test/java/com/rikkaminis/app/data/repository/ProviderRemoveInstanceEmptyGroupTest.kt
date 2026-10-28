package com.rikkaminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [fix/meta1028-d1 F-02] `removeInstance` must delete only the groups IT emptied.
 *
 * B07 F-02's second half: the pre-fix code collected
 * `config.modelGroups.filter { it.memberEntryIds.isEmpty() }` — every empty
 * group in the config, regardless of whether this call touched it. That reached
 * two groups it had no business deleting:
 *  - a group the user left empty on purpose (it survives here), and
 *  - a group whose `member_entry_ids_json` merely failed to decode, whose
 *    in-memory member list is a placeholder ([ProviderConfigFrozenBlobTest]) —
 *    deleting it destroys the only copy of its real member list.
 *
 * The decision is a pure function so the "which groups" question has one testable
 * answer instead of a predicate buried in a 3000-line class.
 */
class ProviderRemoveInstanceEmptyGroupTest {

    // ── arms ──────────────────────────────────────────────────────────────

    @Test
    fun aGroupEmptiedByThisRemovalIsDeleted() {
        val emptied = decideEmptiedGroupIds(
            groups = listOf("g-emptied" to emptyList()),
            touchedGroupIds = setOf("g-emptied"),
            unreadableGroupIds = emptySet(),
        )

        assertEquals(setOf("g-emptied"), emptied)
    }

    @Test
    fun aGroupThatWasAlreadyEmptyIsLeftAlone() {
        val emptied = decideEmptiedGroupIds(
            groups = listOf("g-already-empty" to emptyList()),
            touchedGroupIds = emptySet(),
            unreadableGroupIds = emptySet(),
        )

        assertEquals(emptySet<String>(), emptied)
    }

    @Test
    fun aGroupWithDamagedMembersIsNeverDeletedByARemoval() {
        val emptied = decideEmptiedGroupIds(
            groups = listOf("g-damaged" to emptyList()),
            touchedGroupIds = setOf("g-damaged"),
            unreadableGroupIds = setOf("g-damaged"),
        )

        assertEquals(
            "the placeholder must not be read as 'this group became empty'",
            emptySet<String>(),
            emptied,
        )
    }

    @Test
    fun aGroupThatStillHasMembersIsKept() {
        val emptied = decideEmptiedGroupIds(
            groups = listOf("g-partial" to listOf("inst-2/model-x")),
            touchedGroupIds = setOf("g-partial"),
            unreadableGroupIds = emptySet(),
        )

        assertEquals(emptySet<String>(), emptied)
    }

    @Test
    fun mixedConfigDeletesOnlyWhatItEmptied() {
        val emptied = decideEmptiedGroupIds(
            groups = listOf(
                "g-emptied" to emptyList(),
                "g-already-empty" to emptyList(),
                "g-damaged" to emptyList(),
                "g-partial" to listOf("inst-2/model-x"),
            ),
            touchedGroupIds = setOf("g-emptied", "g-partial", "g-damaged"),
            unreadableGroupIds = setOf("g-damaged"),
        )

        assertEquals(setOf("g-emptied"), emptied)
    }

    // ── contrast arm: the pre-fix predicate ───────────────────────────────

    @Test
    fun contrastLegacyPredicateWouldDeleteEveryEmptyGroup() {
        // Verbatim pre-fix rule: `filter { it.memberEntryIds.isEmpty() }`.
        val legacy = listOf(
            "g-emptied" to emptyList<String>(),
            "g-already-empty" to emptyList(),
            "g-damaged" to emptyList(),
            "g-partial" to listOf("inst-2/model-x"),
        ).filter { it.second.isEmpty() }.map { it.first }.toSet()

        assertEquals(
            "the old predicate deletes three groups that have nothing to do with the removal",
            setOf("g-emptied", "g-already-empty", "g-damaged"),
            legacy,
        )
    }
}
