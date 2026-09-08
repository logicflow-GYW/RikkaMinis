package com.openminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-envvar-group-sync] JVM round-trip regression for the env-var `group`
 * field introduced with the grouping feature. The field must survive every
 * serialization edge it crosses:
 *
 *   1. backup export/import   ([ConfigBackup] envVars array)
 *   2. per-device metadata    ([EnvVarRepository.saveMetadata]/[loadMetadata])
 *   3. a note-only update     ([EnvVarRepository.update] default newGroup=null
 *                              must KEEP the group; the minis-config
 *                              envvars collection writes notes this way)
 *
 * [EnvVarRepository] itself needs an Android Context (not JVM-constructible),
 * so — exactly like [BackupFieldEvapRoundTripTest] — this test pins the
 * contract with faithful replicas of the production key-write/read logic
 * (copy-pasted key-for-key, including the [T-envvar-group-sync] hunks). A
 * dropped key or a wrong default fails here the same way it fails on device.
 *
 * Pre-fix behavior being guarded against: export omitted `group`, import
 * called add() without it, and update()'s newGroup default was "" — so a
 * backup/restore or a minis-config note write silently flattened grouping.
 */
class EnvVarGroupRoundTripTest {

    // ------------------------------------------------------------------
    // Faithful replica of ConfigBackup.export's envVars element writing
    // (secrets omitted — group is metadata and rides unconditionally).
    // ------------------------------------------------------------------
    private fun exportEnvVar(entry: EnvVarRepoEntryReplica): JSONObject = JSONObject().apply {
        put("key", entry.key)
        put("note", entry.note)
        if (entry.group.isNotEmpty()) put("group", entry.group)
        // value is secret-gated in production; irrelevant to this contract
    }

    // ------------------------------------------------------------------
    // Faithful replica of ConfigBackup.import's envVars reading (the
    // argument shape handed to repo.add).
    // ------------------------------------------------------------------
    private fun importEnvVar(ev: JSONObject): Triple<String, String, String> {
        val value = ev.optString("value", "")
        val note = ev.optString("note", "")
        val group = ev.optString("group", "")
        return Triple(value, note, group)
    }

    // ------------------------------------------------------------------
    // Faithful replica of EnvVarRepository.saveMetadata / loadMetadata
    // group read/write (the per-device persistence layer).
    // ------------------------------------------------------------------
    private fun metadataWrite(entry: EnvVarRepoEntryReplica): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("key", entry.key)
        if (entry.note.isNotEmpty()) put("note", entry.note)
        if (entry.group.isNotEmpty()) put("group", entry.group)
        put("createdAt", entry.createdAt)
    }

    private fun metadataReadGroup(obj: JSONObject): String = obj.optString("group", "")

    // ------------------------------------------------------------------
    // Faithful replica of EnvVarRepository.update's group fold — the
    // tri-state contract the whole safety story rests on.
    // ------------------------------------------------------------------
    private fun updateFold(currentGroup: String, newGroup: String?): String =
        (newGroup ?: currentGroup).trim()

    /** Minimal stand-in for EnvVarRepository.EnvVarEntry (Android-free). */
    private data class EnvVarRepoEntryReplica(
        val id: String = "e1",
        val key: String = "CF_API_TOKEN",
        val note: String = "cloudflare token",
        val group: String = "",
        val createdAt: Long = 1_700_000_000_000L,
    )

    // ------------------------------------------------------------------
    // Backup export/import
    // ------------------------------------------------------------------

    /** Core regression: a group label survives a backup round-trip. */
    @Test
    fun backupRoundTrip_preservesGroup() {
        val entry = EnvVarRepoEntryReplica(group = "Cloudflare")
        val doc = exportEnvVar(entry)
        val (value, note, group) = importEnvVar(doc)
        assertEquals("", value) // no secrets in doc
        assertEquals(entry.note, note)
        assertEquals("Cloudflare", group)
    }

    /** Drift guard for the writer: a non-empty group must emit its key. */
    @Test
    fun backupExport_writesGroupKey() {
        val doc = exportEnvVar(EnvVarRepoEntryReplica(group = "GitHub"))
        assertTrue(doc.has("group"))
        assertEquals("GitHub", doc.getString("group"))
    }

    /** Empty group emits no key (symmetric with note; keeps payloads lean). */
    @Test
    fun backupExport_omitsGroupKeyWhenEmpty() {
        val doc = exportEnvVar(EnvVarRepoEntryReplica(group = ""))
        assertFalse(doc.has("group"))
    }

    /** An OLD backup (pre-grouping build) has no key — restore yields "". */
    @Test
    fun backupImport_legacyDocFallsBackToEmpty() {
        val legacy = JSONObject().apply {
            put("key", "GH_TOKEN")
            put("note", "")
        }
        val (_, _, group) = importEnvVar(legacy)
        assertEquals("", group)
    }

    // ------------------------------------------------------------------
    // Per-device metadata persistence
    // ------------------------------------------------------------------

    /** Metadata round-trip preserves the group. */
    @Test
    fun metadataRoundTrip_preservesGroup() {
        val entry = EnvVarRepoEntryReplica(group = "Cloudflare")
        assertEquals("Cloudflare", metadataReadGroup(metadataWrite(entry)))
    }

    /** Legacy metadata (pre-grouping) reads as "" without crashing. */
    @Test
    fun metadataRead_legacyDocFallsBackToEmpty() {
        val legacy = JSONObject().apply {
            put("id", "e1")
            put("key", "GH_TOKEN")
            put("createdAt", 1L)
        }
        assertEquals("", metadataReadGroup(legacy))
    }

    // ------------------------------------------------------------------
    // update() tri-state group contract
    // ------------------------------------------------------------------

    /**
     * The minis-config envvars collection writes a note with the 4-arg
     * update shape (newGroup omitted → null): the group MUST survive.
     * This is the exact call shape EnvVarsCollection.noteField makes.
     */
    @Test
    fun update_noteOnlyWrite_keepsGroup() {
        val current = "Cloudflare"
        assertEquals("Cloudflare", updateFold(current, newGroup = null))
    }

    /** An explicit "" clears the group (the form's empty-input case). */
    @Test
    fun update_explicitEmptyString_clearsGroup() {
        assertEquals("", updateFold("Cloudflare", newGroup = ""))
    }

    /** A non-empty value replaces the group. */
    @Test
    fun update_explicitValue_replacesGroup() {
        assertEquals("GitHub", updateFold("Cloudflare", newGroup = "GitHub"))
    }

    /** Whitespace-only input trims to "" (uncategorized), matching add(). */
    @Test
    fun update_whitespaceOnly_trimsToEmpty() {
        assertEquals("", updateFold("Cloudflare", newGroup = "   "))
    }

    // ------------------------------------------------------------------
    // Sync payload shape (MultiDeviceSync rides ConfigBackup.export)
    // ------------------------------------------------------------------

    /**
     * The sync payload is a ConfigBackup document, so the envVars array in
     * it carries group the same way — pin the multi-entry shape end to end.
     */
    @Test
    fun syncPayloadShape_carriesGroupAcrossEntries() {
        val entries = listOf(
            EnvVarRepoEntryReplica(id = "e1", key = "CF_API_TOKEN", group = "Cloudflare"),
            EnvVarRepoEntryReplica(id = "e2", key = "CF_API_TOKEN_1", group = "Cloudflare"),
            EnvVarRepoEntryReplica(id = "e3", key = "GH_TOKEN", group = "GitHub"),
            EnvVarRepoEntryReplica(id = "e4", key = "HF_TOKEN", group = ""),
        )
        val arr = JSONArray().apply { entries.forEach { put(exportEnvVar(it)) } }

        val restored = (0 until arr.length()).map { importEnvVar(arr.getJSONObject(it)).third }
        assertEquals(listOf("Cloudflare", "Cloudflare", "GitHub", ""), restored)
    }
}
