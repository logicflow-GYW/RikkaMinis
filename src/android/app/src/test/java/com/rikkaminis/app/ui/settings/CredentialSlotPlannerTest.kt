package com.rikkaminis.app.ui.settings

import com.rikkaminis.app.data.model.ProviderCredentialMeta
import com.rikkaminis.app.data.repository.CredentialSlotOp
import com.rikkaminis.app.data.repository.planCredentialSlotWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * End-to-end guard for the credential editor's slot math: the real row mapping
 * ([planCredentialSlots]) feeds the real store plan ([planCredentialSlotWrites])
 * against a mutable store, so the assertions below describe what a user's Save
 * actually writes.
 *
 * The bug this pins: rows carried only a positional `draft`, so after deleting
 * row 0 of three the survivors read the slot under their NEW index — K2 came back
 * wearing K1's (deleted) secret while K3's live secret was erased from the tail.
 * Both halves of that were invisible in the UI, which is exactly the class of
 * defect mechanical gates miss.
 */
class CredentialSlotPlannerTest {

    private fun meta(label: String) = ProviderCredentialMeta(label = label)

    private fun loaded(stored: Map<Int, String?>): List<CredentialSlotRow> =
        stored.keys.sorted().map { i ->
            CredentialSlotRow(meta("K${i + 1}"), draft = null, sourceSlot = i)
        }

    /** Apply the REAL store plan to a mutable store (mirrors saveApiKeys). */
    private fun applyToStore(
        store: MutableMap<Int, String?>,
        keys: List<String?>,
        previousCount: Int,
    ) {
        for (op in planCredentialSlotWrites(keys, previousCount)) {
            when (op) {
                is CredentialSlotOp.Put -> store[op.index] = op.value
                is CredentialSlotOp.Remove -> store.remove(op.index)
            }
        }
    }

    @Test
    fun openingTheEditorAndSavingUnchangedWritesTheSameSecrets() {
        val store = mutableMapOf<Int, String?>(0 to "A-key", 1 to "B-key")
        val written = planCredentialSlots(loaded(store), store)
        assertEquals(listOf("A-key", "B-key"), written)
    }

    @Test
    fun deletingTheFirstRowKeepsEverySurvivorOnItsOwnSecret() {
        val store = mutableMapOf<Int, String?>(0 to "A-key", 1 to "B-key", 2 to "C-key")
        val rows = loaded(store)

        val kept = rows.drop(1) // user deleted the first row
        val written = planCredentialSlots(kept, store)
        applyToStore(store, written, previousCount = 3)

        assertEquals(
            "存活行必须各自带走自己的秘密，而不是继承被删行的槽位",
            listOf("B-key", "C-key"),
            written,
        )
        assertEquals(
            "被删密钥必须消失，且活的密钥不能被销毁",
            mapOf(0 to "B-key", 1 to "C-key"),
            store,
        )
    }

    @Test
    fun deletingTheMiddleRowKeepsBothNeighboursOnTheirOwnSecrets() {
        val store = mutableMapOf<Int, String?>(0 to "A-key", 1 to "B-key", 2 to "C-key")
        val rows = loaded(store)

        val kept = rows.filterIndexed { i, _ -> i != 1 }
        val written = planCredentialSlots(kept, store)
        applyToStore(store, written, previousCount = 3)

        assertEquals(listOf("A-key", "C-key"), written)
        assertEquals(mapOf(0 to "A-key", 1 to "C-key"), store)
    }

    @Test
    fun deletingTheLastRowErasesOnlyTheFreedSlot() {
        val store = mutableMapOf<Int, String?>(0 to "A-key", 1 to "B-key", 2 to "C-key")
        val rows = loaded(store)

        val kept = rows.dropLast(1)
        val written = planCredentialSlots(kept, store)
        applyToStore(store, written, previousCount = 3)

        assertEquals(listOf("A-key", "B-key"), written)
        assertEquals(mapOf(0 to "A-key", 1 to "B-key"), store)
    }

    @Test
    fun anAddedRowNeverInheritsADeletedRowsSecret() {
        val store = mutableMapOf<Int, String?>(0 to "A-key", 1 to "B-key", 2 to "C-key")
        val rows = loaded(store)

        // delete the first row and add a fresh one in the same editing session
        val edited = rows.drop(1) +
            CredentialSlotRow(meta("new"), draft = null, sourceSlot = null)
        val written = planCredentialSlots(edited, store)
        applyToStore(store, written, previousCount = 3)

        assertEquals(
            "新行没有槽位，必须是显式空，不能继承同一会话里被删掉的那把",
            listOf("B-key", "C-key", null),
            written,
        )
        assertEquals("C-key 的旧槽位必须被真正清掉", mapOf(0 to "B-key", 1 to "C-key"), store)
    }

    @Test
    fun aTypedDraftWinsAndClearingItFallsBackToTheStoredSecret() {
        val store = mutableMapOf<Int, String?>(0 to "A-key", 1 to "B-key")
        val rows = loaded(store)

        val retyped = rows.mapIndexed { i, r -> if (i == 1) r.copy(draft = "B-new") else r }
        assertEquals(listOf("A-key", "B-new"), planCredentialSlots(retyped, store))

        // Clearing the field restores the "leave the stored secret alone"
        // semantics — the reason a draft is nullable in the first place.
        val cleared = retyped.mapIndexed { i, r -> if (i == 1) r.copy(draft = null) else r }
        assertEquals(listOf("A-key", "B-key"), planCredentialSlots(cleared, store))
    }

    @Test
    fun displayAndSaveAgreeForEveryRow() {
        val store = mutableMapOf<Int, String?>(0 to "A-key", 1 to "B-key", 2 to "C-key")
        val rows = loaded(store).drop(1)
        val written = planCredentialSlots(rows, store)
        assertEquals(
            "UI 显示的秘密必须与保存写入的秘密逐行一致（错位就是漏看的根源）",
            rows.map { it.effectiveSecret(store) },
            written,
        )
        assertNull("新增空行显示为空", CredentialSlotRow(meta("x"), null, null).effectiveSecret(store))
    }
}
