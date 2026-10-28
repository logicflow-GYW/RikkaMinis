package com.rikkaminis.app.data.repository

import com.rikkaminis.app.data.db.ProviderConfigSnapshot
import com.rikkaminis.app.data.db.ProviderModelEntryEntity
import com.rikkaminis.app.data.db.ProviderModelGroupEntity
import com.rikkaminis.app.data.db.restoreFrozenBlobs
import com.rikkaminis.app.data.db.toProviderConfigWithDiagnostics
import com.rikkaminis.app.data.db.toSnapshot
import com.rikkaminis.app.data.model.LLMModel
import com.rikkaminis.app.data.model.ModelOverrides
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [fix/meta1028-d1 F-02] Decode failures on `overrides_json` and
 * `member_entry_ids_json` must not be written back as truth.
 *
 * B07 F-02: `toProviderConfigWithDiagnostics` folded both undecodable blobs into
 * placeholders (default `ModelOverrides`, empty member list) and dropped the row
 * ids on the floor. The very next save re-encoded those placeholders —
 * `overrides_json = null`, `member_entry_ids_json = "[]"` — so a damaged-but-
 * present blob became a permanently erased one. The empty member list had a
 * second consequence: `removeInstance` scans for empty groups and deleted them,
 * which is pinned in [ProviderRemoveInstanceEmptyGroupTest].
 *
 * This test drives the REAL mapping functions (the row → config projection and
 * the persist-time blob substitution) over hand-built rows; expected blob
 * strings are literals.
 */
class ProviderConfigFrozenBlobTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private fun modelJson(modelId: String): String =
        json.encodeToString(LLMModel.serializer(), LLMModel(id = modelId, displayName = modelId, provider = "openAI"))

    private fun entryRow(id: String, overridesJson: String?) = ProviderModelEntryEntity(
        id = id,
        providerInstanceId = "inst-1",
        baseModelJson = modelJson(id.substringAfter('/')),
        overridesJson = overridesJson,
    )

    private fun groupRow(id: String, memberIdsJson: String) = ProviderModelGroupEntity(
        id = id,
        name = id,
        strategy = "fallback",
        fallbackStrategy = "default",
        memberEntryIdsJson = memberIdsJson,
    )

    private val damagedOverrides = """{"maxOutputTokens": 409"""
    private val damagedMembers = """["inst-1/model-a","inst-1/model-"""

    /** One healthy entry, one entry with a damaged overrides blob, one group with a damaged member list. */
    private fun damagedRows(): Triple<List<ProviderModelEntryEntity>, List<ProviderModelGroupEntity>, ProviderConfigSnapshot> {
        val entries = listOf(
            entryRow("inst-1/model-ok", """{"maxOutputTokens":1024}"""),
            entryRow("inst-1/model-bad", damagedOverrides),
        )
        val groups = listOf(
            groupRow("group-ok", """["inst-1/model-ok"]"""),
            groupRow("group-bad", damagedMembers),
        )
        val snapshot = ProviderConfigSnapshot(
            instances = emptyList(),
            entries = entries,
            groups = groups,
            loopIds = emptyList(),
            meta = emptyList(),
        )
        return Triple(entries, groups, snapshot)
    }

    // ── load side ─────────────────────────────────────────────────────────

    @Test
    fun damagedBlobsAreReportedAndTheRowsStayVisible() {
        val (_, _, snapshot) = damagedRows()

        val loaded = snapshot.toProviderConfigWithDiagnostics(json)

        // The model is usable (defaults) rather than hidden: only base_model_json
        // failures drop a row from the list.
        assertEquals(2, loaded.config.modelEntries.size)
        assertTrue(loaded.config.modelEntries.any { it.uuid == "inst-1/model-bad" })
        assertTrue(
            "a damaged overrides blob must be reported so persist can hand the stored blob back",
            loaded.unreadableOverridesEntryIds.contains("inst-1/model-bad"),
        )
        assertTrue(
            "a damaged member list must be reported",
            loaded.unreadableMemberGroupIds.contains("group-bad"),
        )
        assertEquals(
            "base_model_json failures are the OTHER quarantine set",
            emptySet<String>(),
            loaded.quarantinedEntryRowIds,
        )
        // Display layer: the group still renders (empty), nothing is deleted.
        assertEquals(2, loaded.config.modelGroups.size)
        assertEquals(
            emptyList<String>(),
            loaded.config.modelGroups.first { it.id == "group-bad" }.memberEntryIds,
        )
    }

    @Test
    fun healthyRowsAreNotReportedAsDamaged() {
        val (_, _, snapshot) = damagedRows()

        val loaded = snapshot.toProviderConfigWithDiagnostics(json)

        assertTrue(!loaded.unreadableMemberGroupIds.contains("group-ok"))
        assertTrue(!loaded.unreadableOverridesEntryIds.contains("inst-1/model-ok"))
        assertEquals(
            listOf("inst-1/model-ok"),
            loaded.config.modelGroups.first { it.id == "group-ok" }.memberEntryIds,
        )
        assertEquals(
            1024,
            loaded.config.modelEntries.first { it.uuid == "inst-1/model-ok" }.overrides.maxOutputTokens,
        )
    }

    @Test
    fun anAbsentOverridesBlobIsNotADecodeFailure() {
        val snapshot = ProviderConfigSnapshot(
            instances = emptyList(),
            entries = listOf(entryRow("inst-1/model-plain", null)),
            groups = emptyList(),
            loopIds = emptyList(),
            meta = emptyList(),
        )

        val loaded = snapshot.toProviderConfigWithDiagnostics(json)

        assertTrue(
            "null overrides_json means 'this model uses defaults', the normal case",
            loaded.unreadableOverridesEntryIds.isEmpty(),
        )
        assertNull(loaded.config.modelEntries.single().overrides.maxOutputTokens)
    }

    // ── persist side ──────────────────────────────────────────────────────

    /**
     * Contrast arm: WITHOUT the substitution the snapshot carries
     * `overridesJson = null` / `memberEntryIdsJson = "[]"` — exactly what used to
     * be written to Room (the data loss this fix is about).
     */
    @Test
    fun withoutSubstitutionThePlaceholdersBecomeTheNewTruth() {
        val (_, _, snapshotRows) = damagedRows()
        val loaded = snapshotRows.toProviderConfigWithDiagnostics(json)

        val toWrite = loaded.config.toSnapshot(json)

        assertNull(
            "the damaged blob is re-encoded as null (this is the loss being fixed)",
            toWrite.entries.first { it.id == "inst-1/model-bad" }.overridesJson,
        )
        assertEquals(
            "[]",
            toWrite.groups.first { it.id == "group-bad" }.memberEntryIdsJson,
        )
    }

    @Test
    fun frozenBlobsAreRestoredVerbatimBeforeTheWrite() {
        val (storedEntries, storedGroups, snapshotRows) = damagedRows()
        val loaded = snapshotRows.toProviderConfigWithDiagnostics(json)

        val toWrite = restoreFrozenBlobs(
            snapshot = loaded.config.toSnapshot(json),
            storedEntries = storedEntries,
            storedGroups = storedGroups,
            frozenEntryIds = loaded.unreadableOverridesEntryIds,
            frozenGroupIds = loaded.unreadableMemberGroupIds,
        )

        assertEquals(
            "the stored (damaged but present) blob is written back untouched",
            damagedOverrides,
            toWrite.entries.first { it.id == "inst-1/model-bad" }.overridesJson,
        )
        assertEquals(
            damagedMembers,
            toWrite.groups.first { it.id == "group-bad" }.memberEntryIdsJson,
        )
    }

    @Test
    fun substitutionLeavesHealthyRowsAlone() {
        val (storedEntries, storedGroups, snapshotRows) = damagedRows()
        val loaded = snapshotRows.toProviderConfigWithDiagnostics(json)

        val toWrite = restoreFrozenBlobs(
            snapshot = loaded.config.toSnapshot(json),
            storedEntries = storedEntries,
            storedGroups = storedGroups,
            frozenEntryIds = loaded.unreadableOverridesEntryIds,
            frozenGroupIds = loaded.unreadableMemberGroupIds,
        )

        // A healthy row is re-encoded from the model (encodeDefaults=true), so the
        // bytes are not the stored ones — what matters is that the value survives.
        val healthyOverrides =
            json.decodeFromString(
                ModelOverrides.serializer(),
                toWrite.entries.first { it.id == "inst-1/model-ok" }.overridesJson!!,
            )
        assertEquals(1024, healthyOverrides.maxOutputTokens)
        assertEquals(
            """["inst-1/model-ok"]""",
            toWrite.groups.first { it.id == "group-ok" }.memberEntryIdsJson,
        )
    }

    @Test
    fun aRowThatVanishedFromTheDbKeepsThePlaceholder() {
        val (_, _, snapshotRows) = damagedRows()
        val loaded = snapshotRows.toProviderConfigWithDiagnostics(json)

        // Row deleted between load and save: there is no stored blob to hand back.
        val toWrite = restoreFrozenBlobs(
            snapshot = loaded.config.toSnapshot(json),
            storedEntries = emptyList(),
            storedGroups = emptyList(),
            frozenEntryIds = loaded.unreadableOverridesEntryIds,
            frozenGroupIds = loaded.unreadableMemberGroupIds,
        )

        assertNull(toWrite.entries.first { it.id == "inst-1/model-bad" }.overridesJson)
        assertEquals("[]", toWrite.groups.first { it.id == "group-bad" }.memberEntryIdsJson)
    }

    @Test
    fun nothingFrozenIsANoOp() {
        val snapshot = ProviderConfigSnapshot(
            instances = emptyList(),
            entries = listOf(entryRow("inst-1/model-ok", """{"maxOutputTokens":7}""")),
            groups = listOf(groupRow("group-ok", """["inst-1/model-ok"]""")),
            loopIds = emptyList(),
            meta = emptyList(),
        )
        val toWrite = snapshot.toProviderConfigWithDiagnostics(json).config.toSnapshot(json)

        val out = restoreFrozenBlobs(
            snapshot = toWrite,
            storedEntries = emptyList(),
            storedGroups = emptyList(),
            frozenEntryIds = emptySet(),
            frozenGroupIds = emptySet(),
        )

        assertEquals(toWrite, out)
    }
}
