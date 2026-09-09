package com.rikkaminis.app.backup

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [fix-audit-finding-1] Pure-JVM contract test for the entry-id ordering.
 *
 * The bug: `orderedEntryIds` produced `_entryIds` as `visible + hidden` (two
 * concatenated disjoint slices), while `exportInstanceJSON` emits its `models`
 * array in the underlying list's *append* order (visible and hidden
 * interleaved). On merge-import those two orders are positional-paired, so
 * interleaved entries mapped model-group members onto the wrong entry. The fix
 * made `orderedEntryIds` use `entriesFor` (the same `filter {
 * providerInstanceId == instanceId }` as `exportInstanceJSON`) — both are now
 * the identity projection over the same filter.
 *
 * [ProviderRepository] needs an Android Context, so the real
 * export→import→merge path can't run here. What's pinned instead is the
 * *ordering contract* that the whole pairing correctness rests on:
 * [entryIdsInExportOrder] (used by `orderedEntryIds`) must be the identity
 * projection — it may not re-sort visible entries ahead of hidden ones.
 *
 * Contract-under-test is the PROJECTION ORDER, not the id values (ids are
 * random UUIDs minted by [ModelEntry]), so every assertion is positional:
 * element i of the projection must be `entries[i].id`.
 */
class EntryIdOrderContractTest {

    /** A single model entry; [ModelEntry.id] is a random UUID we cannot seed. */
    private fun entry(modelId: String, isHidden: Boolean) = ModelEntry(
        providerInstanceId = "i1",
        baseModel = LLMModel(modelId, modelId, "OpenAI"),
        isHidden = isHidden,
    )

    /**
     * Core contract: for an interleaved visible/hidden list the projection is
     * the identity — element i is `entries[i].id`, i.e. the exact append order
     * (same order exportInstanceJSON emits its `models` array).
     */
    @Test
    fun `interleaved visible-hidden entries keep their append order`() {
        // Deliberately interleaved: a fresh refresh very often returns hidden
        // catalog entries mixed among the visible ones.
        val entries = listOf(
            entry("model-A", isHidden = false),
            entry("model-B", isHidden = true),   // hidden between visibles
            entry("model-C", isHidden = false),
            entry("model-D", isHidden = true),   // hidden trailing
            entry("model-E", isHidden = false),
        )

        val entryIds = entryIdsInExportOrder(entries)
        // Positional identity: _entryIds[i] must be entries[i].id, in the same
        // order the `models` array emits (entries.map { baseModel.id }).
        assertEquals(entries.map { it.id }, entryIds)
        assertEquals(entries.map { it.baseModel.id },
            entryIds.mapIndexed { i, _ -> entries[i].baseModel.id })
    }

    /**
     * Regression guard for the exact bug: the OLD implementation (`visible +
     * hidden`) re-sorted the list — hidden entries jumped to the tail. Assert
     * our projection does NOT reproduce that re-sorted order for interleaved
     * input; the hidden entry must stay at its original index.
     */
    @Test
    fun `append-order projection does not re-sort visible before hidden`() {
        val entries = listOf(
            entry("model-1", isHidden = false),
            entry("model-H", isHidden = true),   // interleaved hidden at index 1
            entry("model-2", isHidden = false),
        )

        val fixed = entryIdsInExportOrder(entries)
        // The pre-fix order re-sorted to visible-first: [i0, i2 (visible), i1 (hidden)].
        assertNotEquals(
            "append-order projection must NOT re-sort visible ahead of hidden",
            listOf(entries[0].id, entries[2].id, entries[1].id),
            fixed,
        )
        // And the fixed order keeps the hidden entry at its original index 1.
        assertEquals(entries.map { it.id }, fixed)
    }

    /**
     * The merge path pairs `_entryIds[i]` with `models[i]`; with interleaved
     * entries the append order keeps every source id at the same index as its
     * own modelId, so a model-group member resolves to the correct entry.
     * Model that positional pairing and assert no cross-mapping occurs.
     */
    @Test
    fun `append-order keeps source-id paired with its own model at each index`() {
        val entries = listOf(
            entry("model-X", isHidden = false),
            entry("model-Y", isHidden = true),
            entry("model-Z", isHidden = false),
        )
        val srcEntryIds = entryIdsInExportOrder(entries)   // _entryIds
        val modelIds = entries.map { it.baseModel.id }      // models array order

        // Positional pairing — exactly what mergeImportInstanceJSON does: index
        // i of _entryIds pairs with index i of models.
        for (i in srcEntryIds.indices) {
            val srcId = srcEntryIds[i]
            val pairedEntry = entries.first { it.id == srcId }
            // The source id at index i must be the SAME entry as models[i] —
            // no shuffled neighbour.
            assertEquals(
                "source id at index $i must pair with its own model",
                entries[i].baseModel.id,
                pairedEntry.baseModel.id,
            )
        }
        assertEquals(listOf("model-X", "model-Y", "model-Z"), modelIds)
    }
}
