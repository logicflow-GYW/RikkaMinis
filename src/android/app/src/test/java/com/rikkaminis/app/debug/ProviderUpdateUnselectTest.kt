package com.rikkaminis.app.debug

import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelEntry
import com.rikkaminis.app.data.model.ModelOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WAVE-1118 D9-F04] `provider.models.update` with a rename **and**
 * `isHidden = true` in the same call.
 *
 * `[da1023-C]` made `updateEntry` answer the canonical persisted id: renaming a
 * custom model moves the row to the composite key `inst-1:new-id`. The unselect
 * that follows a removal request still passed the caller's pre-rename id
 * (`inst-1:old-id`), matched no row, and the RPC answered `unselected: true`
 * anyway — a model the caller believes is out of rotation keeps getting routed
 * to.
 *
 * [persistModelsUpdate] is the production ordering
 * (`ProviderMutationMethods.modelsUpdate` calls it); the repository arrives as
 * two lambdas because a real `ProviderRepository` needs an Android `Context`.
 * Every expectation is a literal: the id the fake update "persists"
 * (`inst-1:new-id`) is deliberately NOT derivable from the entry handed in.
 */
class ProviderUpdateUnselectTest {

    private fun customEntry(modelId: String): ModelEntry = ModelEntry(
        providerInstanceId = "inst-1",
        baseModel = LLMModel(
            id = modelId,
            displayName = "Custom $modelId",
            provider = "custom-endpoint",
        ),
        overrides = ModelOverrides(),
        isCustom = true,
    )

    @Test
    fun `a rename plus removal unselects the persisted id`() {
        val unselected = mutableListOf<String>()
        val persisted = persistModelsUpdate(
            updated = customEntry("new-id"),
            unselectRequested = true,
            // What the real repository does with a rename: the row lands under
            // the new composite key and that is the id it reports back.
            updateEntry = { "inst-1:new-id" },
            unselectEntry = { unselected += it },
        )
        assertEquals("inst-1:new-id", persisted)
        assertEquals("the caller's pre-rename id matches no row", listOf("inst-1:new-id"), unselected)
    }

    @Test
    fun `a removal of an unchanged id unselects that id`() {
        val unselected = mutableListOf<String>()
        persistModelsUpdate(
            updated = customEntry("old-id"),
            unselectRequested = true,
            updateEntry = { "inst-1:old-id" },
            unselectEntry = { unselected += it },
        )
        assertEquals(listOf("inst-1:old-id"), unselected)
    }

    @Test
    fun `no removal request leaves the selection untouched`() {
        val unselected = mutableListOf<String>()
        val persisted = persistModelsUpdate(
            updated = customEntry("new-id"),
            unselectRequested = false,
            updateEntry = { "inst-1:new-id" },
            unselectEntry = { unselected += it },
        )
        assertEquals("inst-1:new-id", persisted)
        assertFalse(unselected.isNotEmpty())
    }

    @Test
    fun `a refused write throws before anything is unselected`() {
        val unselected = mutableListOf<String>()
        var thrown: Throwable? = null
        try {
            persistModelsUpdate(
                updated = customEntry("colliding-id"),
                unselectRequested = true,
                // modelsUpdate's lambda: `repo.updateEntry(updated)
                // ?: throw RPCException(...)` — the fail-closed arm.
                updateEntry = { throw RPCException(-32602, "Entry update rejected") },
                unselectEntry = { unselected += it },
            )
        } catch (t: Throwable) {
            thrown = t
        }
        assertTrue("a rejected write must not be swallowed, got $thrown", thrown is RPCException)
        assertEquals(emptyList<String>(), unselected)
    }
}
