package com.rikkaminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [INCR-1113 F-02] The append-path entry-id pairing must use each row's
 * ORIGINAL `models[]` index, not its position in the filtered list.
 *
 * ## The defect this pins
 *
 * `ConfigBackup` restored a provider that did not exist locally by calling
 * `ProviderRepository.importInstanceJSON` and then pairing the backup's
 * `_entryIds` with `orderedEntryIds(newInstanceId)` **position by position**.
 * But the parse drops rows the selection-only rule refuses
 * (`isHidden && !isCustom` — the pre-split catalog copies a backup taken
 * before the migration carries), so the restored list is SHORTER than
 * `_entryIds`. From the first dropped row on, every later pairing shifted by
 * one: model group members, agent-loop bindings and `compactModelEntryId`
 * landed on the wrong entry or dangled.
 *
 * The fix carries the source index through the parse and pairs by it
 * ([pairEntryIdsById]). A dropped row contributes no mapping at all — the
 * honest answer, since that entry does not exist on this install.
 */
class ImportEntryIdPairingTest {

    // ── the pairing rule itself ────────────────────────────────────────────

    @Test
    fun `a dropped leading row does not shift the surviving pairing`() {
        // models[] = [hidden, visible]; _entryIds = [a, b]; the hidden row is
        // filtered out, so only index 1 survives.
        val map = pairEntryIdsById(
            srcEntryIds = listOf("a", "b"),
            sourceIndexes = listOf(1),
            resolvedIds = listOf("new-b"),
        )

        assertEquals(mapOf("b" to "new-b"), map)
        assertTrue("the filtered row must not be mapped", "a" !in map)
    }

    @Test
    fun `a dropped row in the middle keeps both neighbours on their own ids`() {
        // models[] = [v1, hidden, v2]; the hidden row at index 1 disappears.
        val map = pairEntryIdsById(
            srcEntryIds = listOf("id-v1", "id-hidden", "id-v2"),
            sourceIndexes = listOf(0, 2),
            resolvedIds = listOf("new-v1", "new-v2"),
        )

        assertEquals(mapOf("id-v1" to "new-v1", "id-v2" to "new-v2"), map)
    }

    @Test
    fun `the positional pairing this replaces would have been wrong`() {
        // The regression witness, spelled out: pairing by POSITION in the
        // filtered list maps the surviving row onto the FIRST source id.
        val srcEntryIds = listOf("id-hidden", "id-visible")
        val filtered = listOf("new-visible")

        val positional = srcEntryIds.take(filtered.size)
            .zip(filtered)
            .toMap()
        val correct = pairEntryIdsById(
            srcEntryIds = srcEntryIds,
            sourceIndexes = listOf(1),
            resolvedIds = filtered,
        )

        // Positional: the visible row is claimed by the hidden row's id.
        assertEquals(mapOf("id-hidden" to "new-visible"), positional)
        // Index-paired: the visible row keeps its own id, the hidden one maps
        // to nothing.
        assertEquals(mapOf("id-visible" to "new-visible"), correct)
    }

    @Test
    fun `every surviving row maps when nothing was dropped`() {
        val map = pairEntryIdsById(
            srcEntryIds = listOf("a", "b", "c"),
            sourceIndexes = listOf(0, 1, 2),
            resolvedIds = listOf("A", "B", "C"),
        )
        assertEquals(mapOf("a" to "A", "b" to "B", "c" to "C"), map)
    }

    @Test
    fun `an index the payload does not describe maps nothing`() {
        val map = pairEntryIdsById(
            srcEntryIds = listOf("a"),
            sourceIndexes = listOf(0, 5),
            resolvedIds = listOf("A", "B"),
        )
        assertEquals(mapOf("a" to "A"), map)
    }

    @Test
    fun `empty source ids are not mapped`() {
        val map = pairEntryIdsById(
            srcEntryIds = listOf("", "b"),
            sourceIndexes = listOf(0, 1),
            resolvedIds = listOf("A", "B"),
        )
        assertEquals(mapOf("b" to "B"), map)
    }

    @Test
    fun `a backup with no _entryIds maps nothing`() {
        assertTrue(
            pairEntryIdsById(
                srcEntryIds = emptyList(),
                sourceIndexes = listOf(0, 1),
                resolvedIds = listOf("A", "B"),
            ).isEmpty(),
        )
    }

    // ── the annotation reader ──────────────────────────────────────────────

    @Test
    fun `readSrcEntryIds reads the annotation in models order`() {
        val dict = JSONObject().put("_entryIds", JSONArray().put("a").put("b"))
        assertEquals(listOf("a", "b"), readSrcEntryIds(dict))
    }

    @Test
    fun `readSrcEntryIds is empty when the annotation is absent`() {
        assertEquals(emptyList<String>(), readSrcEntryIds(JSONObject()))
    }
}
