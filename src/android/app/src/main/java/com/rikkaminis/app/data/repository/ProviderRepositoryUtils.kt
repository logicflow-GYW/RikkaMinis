package com.rikkaminis.app.data.repository

import org.json.JSONObject

/**
 * Pure utility functions extracted from [ProviderRepository] so they can be
 * JVM-unit-tested without Android dependencies.
 *
 * Each function's original location in [ProviderRepository] is noted.
 */

// Modality bitfield constants (private in ProviderRepository, replicated here
// so the extracted pure functions are self-contained).
private const val MODALITY_BIT_TEXT_IN = 1 shl 0
private const val MODALITY_BIT_TEXT_OUT = 1 shl 1
private const val MODALITY_BIT_IMG_IN = 1 shl 2
private const val MODALITY_BIT_PDF_IN = 1 shl 3
private const val MODALITY_BIT_AUD_IN = 1 shl 4
private const val MODALITY_BIT_VID_IN = 1 shl 5
private const val MODALITY_BIT_IMG_OUT = 1 shl 6
private const val MODALITY_BIT_AUD_OUT = 1 shl 7
private const val MODALITY_BIT_VID_OUT = 1 shl 8

/**
 * (was ProviderRepository.hashJsonMirror)
 * SHA-256 hex digest of a string. Used to detect whether the JSON mirror
 * file on disk has changed.
 */
internal fun hashJsonMirror(str: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(str.toByteArray())
    return buildString(digest.size * 2) {
        for (b in digest) {
            val v = b.toInt() and 0xFF
            append(Character.forDigit(v shr 4, 16))
            append(Character.forDigit(v and 0xF, 16))
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────
// [fix/meta1028-d1 F-01] DB ↔ JSON-mirror arbitration.
//
// The invariant the store relies on: `meta.json_sync_hash` is a FACT about what
// the prefs mirror holds, and the cold-start rule is "hash mismatch ⇒ the mirror
// is fresher than the DB, re-import it". The pre-fix code recorded
// hash(mirror-we-hoped-to-write) BEFORE the commit, so a rejected commit left
// DB(new rows) + hash(new) + mirror(OLD string on disk) — which the load path
// reads as "mirror is fresher" and back-fills the OLD config over the rows the
// user just saved.
//
// [decideSyncHash] is the fix and [decideConfigSource] is the read side it has
// to stay consistent with; both are pure so the whole crash/restart cycle is
// unit-testable without Room or a Context.
// ──────────────────────────────────────────────────────────────────────────

/** What a cold-start load should read — see [decideConfigSource]. */
internal enum class ConfigLoadSource {
    /** Use the DB snapshot as-is; the mirror is byte-identical to what we last wrote. */
    DB,

    /** Decode the JSON mirror and (on success) reseed the DB from it. */
    MIRROR,
}

/**
 * [fix/meta1028-d1 F-01] Read-side arbitration between provider.db and the
 * legacy JSON mirror.
 *
 * @param hasDbConfig the DB has rows AND they loaded (instanceCount > 0 and the
 *   snapshot query did not throw).
 * @param hasMirror the prefs mirror string is present and non-null.
 * @param liveHashMatchesStored `sha256(mirror) == meta.json_sync_hash`.
 *
 * Contract (unchanged by this fix — the extraction is a refactor, the fix is on
 * the write side): matching hashes mean the DB is in step; a mismatch means the
 * mirror changed behind our back (older build during a downgrade window, or a
 * save whose DB write never landed) and must be re-imported.
 */
internal fun decideConfigSource(
    hasDbConfig: Boolean,
    hasMirror: Boolean,
    liveHashMatchesStored: Boolean,
): ConfigLoadSource = when {
    hasDbConfig && hasMirror && liveHashMatchesStored -> ConfigLoadSource.DB
    hasMirror -> ConfigLoadSource.MIRROR
    // No mirror to decode: the caller falls through to its DB / empty tail.
    else -> ConfigLoadSource.DB
}

/**
 * [fix/meta1028-d1 F-01] The value to store in `meta.json_sync_hash` for the
 * save that just (tried to) write [intendedMirror] to prefs.
 *
 * The rule: hash WHAT THE PREFS FILE ACTUALLY HOLDS, not what we hoped to write.
 * prefs is the single authority on what survived, and reading it back is the only
 * way the hash is true in every outcome:
 *
 *  - commit ok → [liveMirror] is what we wrote ⇒ DB and mirror are in step.
 *  - commit ok but the DB write then fails → hash(new) + DB(old rows) ⇒ mismatch
 *    ⇒ the next cold start re-imports the mirror ⇒ the save is RECOVERED (this is
 *    the mirror-only degradation path working as designed).
 *  - commit REJECTED → [liveMirror] is still the OLD string ⇒ hash(old) + DB(old
 *    rows) ⇒ in step. The save is lost — and reported as lost — but the DB is
 *    never asked to claim a mirror it did not write, so the load path can no
 *    longer mistake a rejected commit for a downgrade and roll the config back.
 *
 * @param intendedMirror the string handed to `commit()`.
 * @param liveMirror `prefs.getString("config", null)` AFTER the commit — pass the
 *   read-back value, never the intended one, or the whole point is lost.
 * @return the hash to store, or null when there is no mirror to name (null makes
 *   the next load take the re-import branch rather than trust a hash that names
 *   nothing).
 */
internal fun decideSyncHash(
    intendedMirror: String,
    liveMirror: String?,
): String? = when {
    // The committed value is what we asked for (or prefs reports nothing yet —
    // a SharedPreferences that lies about a successful commit is not something
    // we can defend against here; hash the intended string and let the load
    // path's re-import branch arbitrate).
    liveMirror == null -> hashJsonMirror(intendedMirror)
    else -> hashJsonMirror(liveMirror)
}

/**
 * [fix/meta1028-d1 F-02] Which model groups a provider removal must delete.
 *
 * The pre-fix `removeInstance` deleted EVERY group with an empty member list,
 * which is the wrong question twice over:
 *  - a group that was already empty before this call is not "emptied by this
 *    removal" — deleting it throws away a group the user still means to fill;
 *  - an UNDECODABLE `member_entry_ids_json` also projects as empty (the load
 *    path's placeholder), so a decode failure turned "delete one provider" into
 *    "delete every group whose blob is damaged", together with their
 *    defaultPrimarySubGroup / voice / agent-loop bindings — and the group row
 *    (the only copy of its real member list) went with it.
 *
 * @param groups (groupId → member ids) AFTER the removal has been applied.
 * @param touchedGroupIds groups that contained at least one removed entry id
 *   (computed BEFORE the removal) — the only groups this call can have emptied.
 * @param unreadableGroupIds groups whose member list is a placeholder; never
 *   deletable from this path.
 */
internal fun decideEmptiedGroupIds(
    groups: List<Pair<String, List<String>>>,
    touchedGroupIds: Set<String>,
    unreadableGroupIds: Set<String>,
): Set<String> = groups
    .filter { (id, members) ->
        id in touchedGroupIds && members.isEmpty() && id !in unreadableGroupIds
    }
    .map { it.first }
    .toSet()

/**
 * (was ProviderRepository.isSameCalendarDay)
 * Check whether two timestamps (millis) fall on the same calendar day.
 * Uses the system timezone.
 */
internal fun isSameCalendarDay(aMs: Long, bMs: Long): Boolean {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = aMs
    val aYear = cal.get(java.util.Calendar.YEAR)
    val aDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = bMs
    return aYear == cal.get(java.util.Calendar.YEAR)
        && aDay == cal.get(java.util.Calendar.DAY_OF_YEAR)
}

/**
 * (was ProviderRepository.modalityBitfieldFromLists)
 * Encode input/output modality string lists into a bitfield.
 */
internal fun modalityBitfieldFromLists(
    inputs: List<String>?,
    outputs: List<String>?,
): Int {
    var bits = 0
    inputs?.forEach { raw ->
        when (raw.lowercase()) {
            "text" -> bits = bits or MODALITY_BIT_TEXT_IN
            "image" -> bits = bits or MODALITY_BIT_IMG_IN
            "pdf" -> bits = bits or MODALITY_BIT_PDF_IN
            "audio" -> bits = bits or MODALITY_BIT_AUD_IN
            "video" -> bits = bits or MODALITY_BIT_VID_IN
        }
    }
    outputs?.forEach { raw ->
        when (raw.lowercase()) {
            "text" -> bits = bits or MODALITY_BIT_TEXT_OUT
            "image" -> bits = bits or MODALITY_BIT_IMG_OUT
            "audio" -> bits = bits or MODALITY_BIT_AUD_OUT
            "video" -> bits = bits or MODALITY_BIT_VID_OUT
        }
    }
    return bits
}

/**
 * (was ProviderRepository.modalityListsFromBitfield)
 * Decode a bitfield back into input/output modality string lists.
 * Returns null pair when the bitfield is 0 (no modality info).
 */
internal fun modalityListsFromBitfield(bits: Int): Pair<List<String>?, List<String>?> {
    if (bits == 0) return null to null
    val inputs = buildList {
        if (bits and MODALITY_BIT_TEXT_IN != 0) add("text")
        if (bits and MODALITY_BIT_IMG_IN != 0) add("image")
        if (bits and MODALITY_BIT_PDF_IN != 0) add("pdf")
        if (bits and MODALITY_BIT_AUD_IN != 0) add("audio")
        if (bits and MODALITY_BIT_VID_IN != 0) add("video")
    }
    val outputs = buildList {
        if (bits and MODALITY_BIT_TEXT_OUT != 0) add("text")
        if (bits and MODALITY_BIT_IMG_OUT != 0) add("image")
        if (bits and MODALITY_BIT_AUD_OUT != 0) add("audio")
        if (bits and MODALITY_BIT_VID_OUT != 0) add("video")
    }
    return inputs.ifEmpty { null } to outputs.ifEmpty { null }
}

/**
 * (was ProviderRepository.readModalitiesWithBitfieldFallback)
 * Read modality info from a JSON object.
 * Returns (inputs, outputs):
 *   - native `inputModalities` / `outputModalities` list keys take precedence
 *   - if neither list is present, decode iOS's `modalityOverride` bitfield
 *   - if neither shape is present, returns null pair
 */
internal fun readModalitiesWithBitfieldFallback(
    obj: JSONObject,
): Pair<List<String>?, List<String>?> {
    val nativeIn = obj.optJSONArray("inputModalities")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
    }
    val nativeOut = obj.optJSONArray("outputModalities")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
    }
    if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
    if (!obj.has("modalityOverride")) return null to null
    val bits = obj.optInt("modalityOverride", 0)
    return modalityListsFromBitfield(bits)
}

/**
 * [da1022-E] Decision core for [ProviderRepository.updateEntry]'s id-rename
 * path, extracted here so it can be JVM-unit-tested (ProviderRepository
 * itself needs a Room-backed Context and is not JVM-constructible).
 *
 * A custom model id edit changes the composite entry key
 * ("{instanceId}/{modelId}" — the shape data.db.compositeEntryKey mints and
 * every persisted row's primary key). The decision:
 *  - a blank model id would mint the key "instanceId/" and orphan every
 *    downstream lookup → fail-closed (rejected);
 *  - a rename whose new (instance, model) pair already exists in another
 *    entry would make [syncAllIncremental]'s upsertEntries REPLACE the
 *    existing row (same primary key) — the duplicate entry would silently
 *    vanish on next load → fail-closed (rejected);
 *  - otherwise, when the composite key actually changed, [oldKey]/[newKey]
 *    carry the rename remap (uuid canonicalization + reference sync).
 *
 * @param existingUuid the entry's current id (= uuid, canonicalized to the
 *   composite shape by saveConfig's round-trip)
 * @param updatedInstanceId / updatedModelId the proposed new (instance, model)
 * @param others (providerInstanceId, baseModel.id) of every OTHER entry
 */
internal data class EntryRenameDecision(
    val allowed: Boolean,
    /** Rejection reason for the log line; null when allowed. */
    val rejectionReason: String? = null,
    /** Old composite key; non-null only on an allowed rename. */
    val oldKey: String? = null,
    /** New composite key; non-null only on an allowed rename. */
    val newKey: String? = null,
)

internal fun decideEntryRename(
    existingUuid: String,
    updatedInstanceId: String,
    updatedModelId: String,
    others: List<Pair<String, String>>,
): EntryRenameDecision {
    if (updatedModelId.isBlank()) {
        return EntryRenameDecision(allowed = false, rejectionReason = "blank model id")
    }
    val newKey = "$updatedInstanceId/$updatedModelId"
    if (newKey == existingUuid) return EntryRenameDecision(allowed = true)
    val collides = others.any { it.first == updatedInstanceId && it.second == updatedModelId }
    if (collides) {
        return EntryRenameDecision(
            allowed = false,
            rejectionReason = "composite key already exists: $newKey",
        )
    }
    return EntryRenameDecision(allowed = true, oldKey = existingUuid, newKey = newKey)
}