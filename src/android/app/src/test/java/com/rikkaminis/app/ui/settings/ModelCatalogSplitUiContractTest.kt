package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [model-catalog-split] (FIX-B) Contract tests for the management-sheet pure
 * functions: [buildCatalogRows] (remote catalog ∪ user selections merge) and
 * [decideSelectAllAction] (select-all vs deselect-all decision).
 *
 * The Compose wiring itself cannot run on the JVM; these pin the DECISION
 * layer. Expected values are written as literals per the
 * evidence-discipline rule — never derived from the function under test — so
 * a mutation in the production function cannot silently move the expectation.
 */
class ModelCatalogSplitUiContractTest {

    // ── fixtures (fixed literals; ids chosen to exercise the merge paths) ──

    private fun model(id: String, name: String = id) = LLMModel(
        id = id,
        displayName = name,
        provider = "openai",
    )

    private fun entry(model: LLMModel, uuid: String = "uuid-" + model.id) = ModelEntry(
        providerInstanceId = "inst-1",
        baseModel = model,
        uuid = uuid,
    )

    // Remote catalog: 5 models. Selected: gpt-a (in catalog), custom-x (not).
    private val remote5 = listOf(
        model("gpt-a"),
        model("gpt-b"),
        model("claude-c"),
        model("claude-d"),
        model("o3-e"),
    )
    private val selected2 = listOf(
        entry(remote5[0]),               // gpt-a — folds into its remote row
        entry(model("custom-x")),        // not in catalog — orphan row
    )

    // ── 1. merge: 6 rows, no duplicate ids, orphan row last ──

    @Test
    fun merge_remote5_selected2_yieldsSixRows() {
        val rows = buildCatalogRows(remote5, selected2)
        // 5 remote rows (one of them carrying the gpt-a selection) + 1 orphan
        // selected row = 6, NOT 5: the custom selection must stay visible.
        assertEquals(6, rows.size)
    }

    @Test
    fun merge_noDuplicateModelIds() {
        val rows = buildCatalogRows(remote5, selected2)
        val ids = rows.map { it.first.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(setOf("gpt-a", "gpt-b", "claude-c", "claude-d", "o3-e", "custom-x"), ids.toSet())
    }

    @Test
    fun merge_orphanSelectedRowComesLast() {
        val rows = buildCatalogRows(remote5, selected2)
        assertEquals("custom-x", rows.last().first.id)
        // and the 5 remote ids keep catalog order ahead of it
        assertEquals(listOf("gpt-a", "gpt-b", "claude-c", "claude-d", "o3-e"), rows.dropLast(1).map { it.first.id })
    }

    // ── 2. selected rows carry the right entry ──

    @Test
    fun merge_selectedRowCarriesEntryWithMatchingModelId() {
        val rows = buildCatalogRows(remote5, selected2)
        val gptARow = rows.first { it.first.id == "gpt-a" }
        assertNotNull(gptARow.second)
        assertEquals("gpt-a", gptARow.second!!.baseModel.id)
        // unselected remote rows carry no entry
        assertNull(rows.first { it.first.id == "gpt-b" }.second)
    }

    @Test
    fun merge_selectionOrderDoesNotAffectFold() {
        // same selection, different list order — the fold is by model id, so
        // the carried entry (uuid) must be identical either way
        val shuffled = listOf(selected2[1], selected2[0])
        val rows = buildCatalogRows(remote5, shuffled)
        assertEquals("uuid-gpt-a", rows.first { it.first.id == "gpt-a" }.second!!.id)
    }

    // ── 3. select-all / deselect-all decision ──

    @Test
    fun decide_filteredUncheckedRowsPresent_selectAll() {
        val rows = buildCatalogRows(remote5, selected2)
        // filter "gpt" hits gpt-a (checked) and gpt-b (unchecked)
        val gptFilter: (LLMModel) -> Boolean = { it.id.contains("gpt") }
        assertTrue(decideSelectAllAction(rows, gptFilter))
    }

    @Test
    fun decide_allFilteredRowsChecked_deselect() {
        val rows = buildCatalogRows(remote5, selected2)
        // check gpt-b too, then rerun the same filter
        val rowsAllGptChecked = buildCatalogRows(remote5, selected2 + entry(remote5[1]))
        val gptFilter: (LLMModel) -> Boolean = { it.id.contains("gpt") }
        assertFalse(decideSelectAllAction(rowsAllGptChecked, gptFilter))
        // sanity: the pre-check run on the same filter was select-all
        assertTrue(decideSelectAllAction(rows, gptFilter))
    }

    @Test
    fun decide_filterHitsNothing_fallsToDeselectBranch() {
        val rows = buildCatalogRows(remote5, selected2)
        assertFalse(decideSelectAllAction(rows) { it.id.contains("no-such-id") })
    }

    @Test
    fun decide_emptyRows_false() {
        assertFalse(decideSelectAllAction(emptyList()) { true })
    }

    // ── 4. empty remote catalog: selections still render ──

    @Test
    fun merge_emptyRemote_keepsAllSelections() {
        val rows = buildCatalogRows(emptyList(), selected2)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.second != null })
        // orphan rows keep their selected entries with matching ids
        assertEquals("uuid-gpt-a", rows[0].second!!.id)
        assertEquals("uuid-custom-x", rows[1].second!!.id)
    }

    // ── extra: both empty (fresh sheet before any fetch) ──

    @Test
    fun merge_bothEmpty_emptyRows() {
        assertTrue(buildCatalogRows(emptyList(), emptyList()).isEmpty())
    }
}
